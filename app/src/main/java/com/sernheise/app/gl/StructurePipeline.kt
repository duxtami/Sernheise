package com.sernheise.app.gl

import android.opengl.GLES11Ext
import android.opengl.GLES20

/**
 * Real guided-filter-based "Structure" pipeline. See [StructureShaders] for the full
 * rationale for replacing the previous Laplacian/blur-difference pyramid with a guided
 * filter -- in short: a guided filter's edge-preserving output tracks real edges almost
 * exactly (by construction, via a per-pixel local linear model), so the detail signal
 * derived from it is naturally near-zero at edges, rather than merely suppressed there
 * after the fact the way a blur-difference signal has to be.
 *
 * Orientation: identical mechanism to before -- every source (photo or video frame) is
 * first resolved into [resolvedTex] via [resolveToPipeline], and everything downstream
 * samples one of the pipeline's *own* render-to-texture outputs using the single, uniform
 * plain convention (`sourceIsVideo = true`), never the source-specific one a raw Bitmap
 * upload needs. See the prior fix for why mixing those conventions inside the pipeline
 * caused a vertical-flip bug.
 *
 * Two guided-filter radii are computed (a small one for fine detail, a larger one for
 * broader local-contrast "richness"), each via: box-blur(I, I^2) -> pointwise (a, b) ->
 * box-blur(a, b) + reconstruct q against the true original. The larger radius runs at a
 * capped resolution (coarse structure isn't resolution-sensitive the way fine detail is);
 * the smaller radius runs at full resolution, since it's the band most sensitive to
 * resolution loss.
 */
class StructurePipeline {

    companion object {
        private const val MAX_LARGE_SCALE_DIMENSION = 1600
        private const val AMOUNT_EPSILON = 0.004f
        private const val SMALL_RADIUS = 2.0f
        private const val LARGE_RADIUS = 10.0f
        // Guided filter epsilon (in normalized-luminance-squared units): controls the
        // variance threshold below which a region is treated as "flat, just smooth it"
        // rather than "real structure, preserve it". Deliberately not tiny -- a very small
        // epsilon would treat ordinary sensor noise as structure to preserve, which is the
        // opposite of what a clean, premium-looking result needs.
        private const val GUIDED_EPSILON = 0.0009f
        // Wider than either guided-filter radius on purpose -- glow is meant to be a soft,
        // diffuse spread of light around highlights, not a local-contrast operation.
        private const val GLOW_RADIUS = 20.0f
        private const val GL_HALF_FLOAT_OES = 0x8D61
    }

    private var resolveOesProgram: GlProgram? = null
    private var resolveImageProgram: GlProgram? = null
    private var packLumProgram: GlProgram? = null
    private var boxBlurProgram: GlProgram? = null
    private var guidedAbProgram: GlProgram? = null
    private var guidedQProgram: GlProgram? = null
    private var glowExtractProgram: GlProgram? = null
    private var compositeProgram: GlProgram? = null

    private val quad = Quad()

    private var usingHalfFloat = false
    private var halfFloatChecked = false

    private var fullW = 0
    private var fullH = 0
    private var largeW = 0
    private var largeH = 0

    private var resolvedTex = -1; private var resolvedFbo = -1
    private var packedLumTex = -1; private var packedLumFbo = -1
    private var outputTex = -1; private var outputFbo = -1

    // Small scale (full resolution).
    private var smallScratchTex = -1; private var smallScratchFbo = -1
    private var smallMeanCorrTex = -1; private var smallMeanCorrFbo = -1
    private var smallAbTex = -1; private var smallAbFbo = -1
    private var qSmallTex = -1; private var qSmallFbo = -1

    // Large scale (capped resolution).
    private var largeScratchTex = -1; private var largeScratchFbo = -1
    private var largeMeanCorrTex = -1; private var largeMeanCorrFbo = -1
    private var largeAbTex = -1; private var largeAbFbo = -1
    private var qLargeTex = -1; private var qLargeFbo = -1

    // Glow: bright-highlight extraction (full res) then a wide blur (capped resolution,
    // reusing the same cap as the large guided-filter scale -- glow is meant to be soft
    // and diffuse, so it needs even less resolution fidelity than coarse structure).
    private var glowSourceTex = -1; private var glowSourceFbo = -1
    private var glowScratchTex = -1; private var glowScratchFbo = -1
    private var glowBlurredTex = -1; private var glowBlurredFbo = -1

    /**
     * @param sourceTextureId GL texture id: a samplerExternalOES id if [sourceIsOES], else a plain 2D texture.
     * @param sourceTexMatrix SurfaceTexture's current transform matrix (ignored if !sourceIsOES).
     * @return a plain 2D texture id holding the (optionally structure-enhanced) result at
     *         [contentWidth]x[contentHeight], in the same orientation convention as a plain image.
     *
     * Leaves the currently-bound framebuffer/viewport as whatever its own last internal pass
     * used -- callers must rebind their own target FBO and viewport before their next draw call.
     */
    fun process(
        sourceTextureId: Int,
        sourceIsOES: Boolean,
        sourceTexMatrix: FloatArray,
        contentWidth: Int,
        contentHeight: Int,
        structureAmount: Float
    ): Int {
        ensurePrograms()
        ensureAllocated(contentWidth, contentHeight)

        resolveToPipeline(sourceTextureId, sourceIsOES, sourceTexMatrix)
        val originalTex = resolvedTex

        if (structureAmount <= AMOUNT_EPSILON) {
            return originalTex
        }

        packLuminance(originalTex)

        runGuidedFilterScale(
            outputW = fullW, outputH = fullH, radius = SMALL_RADIUS,
            meanCorrScratchFbo = smallScratchFbo, meanCorrFbo = smallMeanCorrFbo, meanCorrTex = smallMeanCorrTex,
            abFbo = smallAbFbo, abTex = smallAbTex,
            qScratchFbo = smallScratchFbo, qFbo = qSmallFbo
        )
        runGuidedFilterScale(
            outputW = largeW, outputH = largeH, radius = LARGE_RADIUS,
            meanCorrScratchFbo = largeScratchFbo, meanCorrFbo = largeMeanCorrFbo, meanCorrTex = largeMeanCorrTex,
            abFbo = largeAbFbo, abTex = largeAbTex,
            qScratchFbo = largeScratchFbo, qFbo = qLargeFbo
        )
        runGlow(originalTex)

        composite(originalTex, structureAmount)
        return outputTex
    }

    private fun ensurePrograms() {
        if (resolveOesProgram == null) resolveOesProgram = GlProgram(StructureShaders.PASSTHROUGH_VERTEX, StructureShaders.RESOLVE_FRAGMENT_OES)
        if (resolveImageProgram == null) resolveImageProgram = GlProgram(StructureShaders.PASSTHROUGH_VERTEX, StructureShaders.RESOLVE_FRAGMENT_2D)
        if (packLumProgram == null) packLumProgram = GlProgram(StructureShaders.PASSTHROUGH_VERTEX, StructureShaders.PACK_LUM_FRAGMENT)
        if (boxBlurProgram == null) boxBlurProgram = GlProgram(StructureShaders.PASSTHROUGH_VERTEX, StructureShaders.BOX_BLUR_RG_FRAGMENT)
        if (guidedAbProgram == null) guidedAbProgram = GlProgram(StructureShaders.PASSTHROUGH_VERTEX, StructureShaders.GUIDED_AB_FRAGMENT)
        if (guidedQProgram == null) guidedQProgram = GlProgram(StructureShaders.PASSTHROUGH_VERTEX, StructureShaders.GUIDED_Q_FRAGMENT)
        if (glowExtractProgram == null) glowExtractProgram = GlProgram(StructureShaders.PASSTHROUGH_VERTEX, StructureShaders.GLOW_EXTRACT_FRAGMENT)
        if (compositeProgram == null) compositeProgram = GlProgram(StructureShaders.PASSTHROUGH_VERTEX, StructureShaders.COMPOSITE_FRAGMENT)
    }

    private fun ensureAllocated(contentWidth: Int, contentHeight: Int) {
        if (contentWidth == fullW && contentHeight == fullH && resolvedTex != -1 && outputTex != -1) return

        releaseTargets()
        checkHalfFloatSupport()

        fullW = contentWidth
        fullH = contentHeight
        val scale = minOf(1f, MAX_LARGE_SCALE_DIMENSION.toFloat() / maxOf(contentWidth, contentHeight).toFloat())
        largeW = maxOf(1, (contentWidth * scale).toInt())
        largeH = maxOf(1, (contentHeight * scale).toInt())

        val (rt, rf) = createRenderTarget(fullW, fullH); resolvedTex = rt; resolvedFbo = rf
        val (pt, pf) = createRenderTarget(fullW, fullH); packedLumTex = pt; packedLumFbo = pf
        val (ot, of) = createRenderTarget(fullW, fullH); outputTex = ot; outputFbo = of

        val (sst, ssf) = createRenderTarget(fullW, fullH); smallScratchTex = sst; smallScratchFbo = ssf
        val (smt, smf) = createRenderTarget(fullW, fullH); smallMeanCorrTex = smt; smallMeanCorrFbo = smf
        val (sat, saf) = createRenderTarget(fullW, fullH); smallAbTex = sat; smallAbFbo = saf
        val (sqt, sqf) = createRenderTarget(fullW, fullH); qSmallTex = sqt; qSmallFbo = sqf

        val (lst, lsf) = createRenderTarget(largeW, largeH); largeScratchTex = lst; largeScratchFbo = lsf
        val (lmt, lmf) = createRenderTarget(largeW, largeH); largeMeanCorrTex = lmt; largeMeanCorrFbo = lmf
        val (lat, laf) = createRenderTarget(largeW, largeH); largeAbTex = lat; largeAbFbo = laf
        val (lqt, lqf) = createRenderTarget(largeW, largeH); qLargeTex = lqt; qLargeFbo = lqf

        val (gst, gsf) = createRenderTarget(fullW, fullH); glowSourceTex = gst; glowSourceFbo = gsf
        val (gct, gcf) = createRenderTarget(largeW, largeH); glowScratchTex = gct; glowScratchFbo = gcf
        val (gbt, gbf) = createRenderTarget(largeW, largeH); glowBlurredTex = gbt; glowBlurredFbo = gbf
    }

    private fun checkHalfFloatSupport() {
        if (halfFloatChecked) return
        halfFloatChecked = true
        val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
        if (!extensions.contains("GL_OES_texture_half_float")) {
            usingHalfFloat = false
            return
        }
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 4, 4, 0, GLES20.GL_RGBA, GL_HALF_FLOAT_OES, null)
        val fboIds = IntArray(1)
        GLES20.glGenFramebuffers(1, fboIds, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texIds[0], 0)
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        usingHalfFloat = status == GLES20.GL_FRAMEBUFFER_COMPLETE
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glDeleteFramebuffers(1, fboIds, 0)
        GLES20.glDeleteTextures(1, texIds, 0)
    }

    private fun createRenderTarget(width: Int, height: Int): Pair<Int, Int> {
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
        val type = if (usingHalfFloat) GL_HALF_FLOAT_OES else GLES20.GL_UNSIGNED_BYTE
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, type, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val fboIds = IntArray(1)
        GLES20.glGenFramebuffers(1, fboIds, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texIds[0], 0)
        val complete = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        if (!complete && usingHalfFloat) {
            usingHalfFloat = false
            GLES20.glDeleteTextures(1, texIds, 0)
            GLES20.glDeleteFramebuffers(1, fboIds, 0)
            return createRenderTarget(width, height)
        }
        return texIds[0] to fboIds[0]
    }

    private fun releaseTargets() {
        val texes = intArrayOf(
            resolvedTex, packedLumTex, outputTex,
            smallScratchTex, smallMeanCorrTex, smallAbTex, qSmallTex,
            largeScratchTex, largeMeanCorrTex, largeAbTex, qLargeTex,
            glowSourceTex, glowScratchTex, glowBlurredTex
        ).filter { it >= 0 }
        val fbos = intArrayOf(
            resolvedFbo, packedLumFbo, outputFbo,
            smallScratchFbo, smallMeanCorrFbo, smallAbFbo, qSmallFbo,
            largeScratchFbo, largeMeanCorrFbo, largeAbFbo, qLargeFbo,
            glowSourceFbo, glowScratchFbo, glowBlurredFbo
        ).filter { it >= 0 }
        if (texes.isNotEmpty()) GLES20.glDeleteTextures(texes.size, texes.toIntArray(), 0)
        if (fbos.isNotEmpty()) GLES20.glDeleteFramebuffers(fbos.size, fbos.toIntArray(), 0)
    }

    /** Normalizes any source into [resolvedTex] -- the one place a source-specific orientation convention is used. */
    private fun resolveToPipeline(sourceTextureId: Int, sourceIsOES: Boolean, texMatrix: FloatArray) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, resolvedFbo)
        GLES20.glViewport(0, 0, fullW, fullH)

        if (sourceIsOES) {
            val program = resolveOesProgram ?: return
            program.use()
            quad.setOrientation(0, flipHorizontal = false, flipVertical = false, sourceIsVideo = true)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, sourceTextureId)
            GLES20.glUniform1i(program.uniform("u_texture"), 0)
            GLES20.glUniformMatrix4fv(program.uniform("u_texMatrix"), 1, false, texMatrix, 0)
            drawFullScreenQuad(program)
        } else {
            val program = resolveImageProgram ?: return
            program.use()
            quad.setOrientation(0, flipHorizontal = false, flipVertical = false, sourceIsVideo = false)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sourceTextureId)
            GLES20.glUniform1i(program.uniform("u_texture"), 0)
            drawFullScreenQuad(program)
        }
    }

    private fun packLuminance(sourceTex: Int) {
        val program = packLumProgram ?: return
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, packedLumFbo)
        GLES20.glViewport(0, 0, fullW, fullH)
        program.use()
        quad.setOrientation(0, flipHorizontal = false, flipVertical = false, sourceIsVideo = true)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sourceTex)
        GLES20.glUniform1i(program.uniform("u_texture"), 0)
        drawFullScreenQuad(program)
    }

    /**
     * Runs one full guided-filter scale: box-blur(I, I^2) at [outputW]x[outputH] -> pointwise
     * (a, b) -> box-blur(a, b) + reconstruct q against the true original (resolvedTex).
     * Always reads packedLuminance (full resolution) as its ultimate mean/variance source;
     * only the OUTPUT resolution of this particular scale differs (full for small, capped
     * for large), matching the same "process the reference small, apply full-res" approach
     * used for the pyramid in the previous architecture.
     */
    private fun runGuidedFilterScale(
        outputW: Int, outputH: Int, radius: Float,
        meanCorrScratchFbo: Int, meanCorrFbo: Int, meanCorrTex: Int,
        abFbo: Int, abTex: Int,
        qScratchFbo: Int, qFbo: Int
    ) {
        val blur = boxBlurProgram ?: return
        val ab = guidedAbProgram ?: return
        val q = guidedQProgram ?: return

        quad.setOrientation(0, flipHorizontal = false, flipVertical = false, sourceIsVideo = true)

        // mean(I), mean(I^2) at this scale's radius/resolution.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, meanCorrScratchFbo)
        GLES20.glViewport(0, 0, outputW, outputH)
        blur.use()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, packedLumTex)
        GLES20.glUniform1i(blur.uniform("u_texture"), 0)
        GLES20.glUniform2f(blur.uniform("u_texel"), 1f / fullW, 1f / fullH)
        GLES20.glUniform2f(blur.uniform("u_direction"), 1f, 0f)
        GLES20.glUniform1f(blur.uniform("u_radius"), radius)
        drawFullScreenQuad(blur)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, meanCorrFbo)
        GLES20.glViewport(0, 0, outputW, outputH)
        blur.use()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, meanCorrTexOf(meanCorrScratchFbo))
        GLES20.glUniform1i(blur.uniform("u_texture"), 0)
        GLES20.glUniform2f(blur.uniform("u_texel"), 1f / outputW, 1f / outputH)
        GLES20.glUniform2f(blur.uniform("u_direction"), 0f, 1f)
        GLES20.glUniform1f(blur.uniform("u_radius"), radius)
        drawFullScreenQuad(blur)

        // Pointwise (a, b) from local mean/variance.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, abFbo)
        GLES20.glViewport(0, 0, outputW, outputH)
        ab.use()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, meanCorrTex)
        GLES20.glUniform1i(ab.uniform("u_meanCorr"), 0)
        GLES20.glUniform1f(ab.uniform("u_epsilon"), GUIDED_EPSILON)
        drawFullScreenQuad(ab)

        // mean(a), mean(b), then reconstruct q = mean_a * I + mean_b against the true original.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, qScratchFbo)
        GLES20.glViewport(0, 0, outputW, outputH)
        q.use()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, abTex)
        GLES20.glUniform1i(q.uniform("u_abTexture"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, resolvedTex)
        GLES20.glUniform1i(q.uniform("u_original"), 1)
        GLES20.glUniform2f(q.uniform("u_texel"), 1f / outputW, 1f / outputH)
        GLES20.glUniform2f(q.uniform("u_direction"), 1f, 0f)
        GLES20.glUniform1f(q.uniform("u_radius"), radius)
        GLES20.glUniform1f(q.uniform("u_isFinalPass"), 0f)
        drawFullScreenQuad(q)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, qFbo)
        GLES20.glViewport(0, 0, outputW, outputH)
        q.use()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, qScratchTexOf(qScratchFbo))
        GLES20.glUniform1i(q.uniform("u_abTexture"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, resolvedTex)
        GLES20.glUniform1i(q.uniform("u_original"), 1)
        GLES20.glUniform2f(q.uniform("u_texel"), 1f / outputW, 1f / outputH)
        GLES20.glUniform2f(q.uniform("u_direction"), 0f, 1f)
        GLES20.glUniform1f(q.uniform("u_radius"), radius)
        GLES20.glUniform1f(q.uniform("u_isFinalPass"), 1f)
        drawFullScreenQuad(q)
    }

    /** The two scratch FBOs are reused for two different intermediate values in sequence; map back to their texture id. */
    private fun meanCorrTexOf(scratchFbo: Int): Int = when (scratchFbo) {
        smallScratchFbo -> smallScratchTex
        largeScratchFbo -> largeScratchTex
        else -> throw IllegalArgumentException("Unknown scratch FBO")
    }

    private fun qScratchTexOf(scratchFbo: Int): Int = meanCorrTexOf(scratchFbo)

    /** Extracts bright/highlight luminance from the original, then blurs it widely (H+V) for the composite's screen blend. */
    private fun runGlow(originalTex: Int) {
        val extract = glowExtractProgram ?: return
        val blur = boxBlurProgram ?: return

        quad.setOrientation(0, flipHorizontal = false, flipVertical = false, sourceIsVideo = true)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, glowSourceFbo)
        GLES20.glViewport(0, 0, fullW, fullH)
        extract.use()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, originalTex)
        GLES20.glUniform1i(extract.uniform("u_original"), 0)
        drawFullScreenQuad(extract)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, glowScratchFbo)
        GLES20.glViewport(0, 0, largeW, largeH)
        blur.use()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, glowSourceTex)
        GLES20.glUniform1i(blur.uniform("u_texture"), 0)
        GLES20.glUniform2f(blur.uniform("u_texel"), 1f / fullW, 1f / fullH)
        GLES20.glUniform2f(blur.uniform("u_direction"), 1f, 0f)
        GLES20.glUniform1f(blur.uniform("u_radius"), GLOW_RADIUS)
        drawFullScreenQuad(blur)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, glowBlurredFbo)
        GLES20.glViewport(0, 0, largeW, largeH)
        blur.use()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, glowScratchTex)
        GLES20.glUniform1i(blur.uniform("u_texture"), 0)
        GLES20.glUniform2f(blur.uniform("u_texel"), 1f / largeW, 1f / largeH)
        GLES20.glUniform2f(blur.uniform("u_direction"), 0f, 1f)
        GLES20.glUniform1f(blur.uniform("u_radius"), GLOW_RADIUS)
        drawFullScreenQuad(blur)
    }

    private fun composite(originalTex: Int, structureAmount: Float) {
        val program = compositeProgram ?: return
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFbo)
        GLES20.glViewport(0, 0, fullW, fullH)
        program.use()
        quad.setOrientation(0, flipHorizontal = false, flipVertical = false, sourceIsVideo = true)

        bindSampler(program, "u_original", 0, originalTex)
        bindSampler(program, "u_qSmall", 1, qSmallTex)
        bindSampler(program, "u_qLarge", 2, qLargeTex)
        bindSampler(program, "u_glow", 3, glowBlurredTex)
        GLES20.glUniform1f(program.uniform("u_amount"), structureAmount.coerceIn(0f, 1f))
        drawFullScreenQuad(program)
    }

    private fun bindSampler(program: GlProgram, uniformName: String, unit: Int, textureId: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(program.uniform(uniformName), unit)
    }

    private fun drawFullScreenQuad(program: GlProgram) {
        quad.draw(program.attrib("a_position"), program.attrib("a_texCoord"))
    }
}
