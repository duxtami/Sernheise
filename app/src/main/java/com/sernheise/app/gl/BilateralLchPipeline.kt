package com.sernheise.app.gl

import android.opengl.GLES11Ext
import android.opengl.GLES20

/**
 * Alternate "Structure" pipeline: joint bilateral diff extraction + LCH chroma/hue lock.
 * See [BilateralLchShaders] for the shader-level rationale and the two dead-code/portability
 * fixes made versus the original two source shaders.
 *
 * This is a second, independent implementation added alongside [StructurePipeline] (which
 * is left completely untouched) -- not a replacement. [StructurePipeline]'s own header
 * comment documents, at length, why the guided filter was adopted specifically to avoid
 * the edge-leakage a blur-difference signal (bilateral included) cannot fully avoid; this
 * class exists because it was explicitly requested as an additional, selectable technique,
 * not because that guided-filter rationale no longer applies. If both are wired into the UI,
 * treat them as two different "looks" rather than one superseding the other.
 *
 * Same external shape as [StructurePipeline.process] on purpose -- a drop-in call for
 * whichever technique is selected at the call site (e.g. `if (useBilateralLch) bilateralLchPipeline.process(...) else structurePipeline.process(...)`).
 */
class BilateralLchPipeline {

    companion object {
        private const val AMOUNT_EPSILON = 0.004f
        // Spatial step between taps, in source pixels -- matches the original shader's
        // externally-supplied `bstep`. Kept small since TAP_RADIUS=5 already spans an 11x11
        // tap footprint; a larger step would make the bilateral window very wide.
        private const val TAP_STEP_PX = 1.4f
        // float(RADIUS*RADIUS) * SPACESMOOTH * SPACESMOOTH from the source shader, with
        // RADIUS=5 (see BilateralLchShaders.TAP_RADIUS) and SPACESMOOTH = 2/1.41.
        private const val SPACE_SMOOTH2 = 25f * (2f / 1.41f) * (2f / 1.41f)
        // u_intensity scale applied on top of the externally-supplied 0..1 amount -- the
        // source shader's `intensity` uniform was applied directly to a +/-1-range decoded
        // diff, so values above 1 are usable (and expected) for a visible effect.
        private const val INTENSITY_SCALE = 2.2f
    }

    private var resolveOesProgram: GlProgram? = null
    private var resolveImageProgram: GlProgram? = null
    private var diffProgram: GlProgram? = null
    private var compositeProgram: GlProgram? = null

    private val quad = Quad()

    private var fullW = 0
    private var fullH = 0

    private var resolvedTex = -1; private var resolvedFbo = -1
    private var diffTex = -1; private var diffFbo = -1
    private var outputTex = -1; private var outputFbo = -1

    /**
     * Same contract as [StructurePipeline.process]: returns a plain 2D texture id holding
     * the (optionally structure-enhanced) result at [contentWidth]x[contentHeight].
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

        runDiff(originalTex)
        composite(originalTex, structureAmount)
        return outputTex
    }

    private fun ensurePrograms() {
        if (resolveOesProgram == null) resolveOesProgram = GlProgram(StructureShaders.PASSTHROUGH_VERTEX, StructureShaders.RESOLVE_OES)
        if (resolveImageProgram == null) resolveImageProgram = GlProgram(StructureShaders.PASSTHROUGH_VERTEX, StructureShaders.RESOLVE_2D)
        if (diffProgram == null) diffProgram = GlProgram(StructureShaders.PASSTHROUGH_VERTEX, BilateralLchShaders.BILATERAL_DIFF_FRAGMENT)
        if (compositeProgram == null) compositeProgram = GlProgram(StructureShaders.PASSTHROUGH_VERTEX, BilateralLchShaders.LCH_COMPOSITE_FRAGMENT)
    }

    private fun ensureAllocated(contentWidth: Int, contentHeight: Int) {
        if (contentWidth == fullW && contentHeight == fullH && resolvedTex != -1 && outputTex != -1) return

        releaseTargets()

        fullW = contentWidth
        fullH = contentHeight

        val (rt, rf) = createRenderTarget(fullW, fullH); resolvedTex = rt; resolvedFbo = rf
        val (dt, df) = createRenderTarget(fullW, fullH); diffTex = dt; diffFbo = df
        val (ot, of) = createRenderTarget(fullW, fullH); outputTex = ot; outputFbo = of
    }

    private fun createRenderTarget(width: Int, height: Int): Pair<Int, Int> {
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val fboIds = IntArray(1)
        GLES20.glGenFramebuffers(1, fboIds, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texIds[0], 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return texIds[0] to fboIds[0]
    }

    private fun releaseTargets() {
        val texes = intArrayOf(resolvedTex, diffTex, outputTex).filter { it >= 0 }
        val fbos = intArrayOf(resolvedFbo, diffFbo, outputFbo).filter { it >= 0 }
        if (texes.isNotEmpty()) GLES20.glDeleteTextures(texes.size, texes.toIntArray(), 0)
        if (fbos.isNotEmpty()) GLES20.glDeleteFramebuffers(fbos.size, fbos.toIntArray(), 0)
    }

    /** Same source-specific orientation handling as StructurePipeline -- see its doc for why OES/image conventions must not be mixed. */
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

    private fun runDiff(originalTex: Int) {
        val program = diffProgram ?: return
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, diffFbo)
        GLES20.glViewport(0, 0, fullW, fullH)
        program.use()
        quad.setOrientation(0, flipHorizontal = false, flipVertical = false, sourceIsVideo = true)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, originalTex)
        GLES20.glUniform1i(program.uniform("u_original"), 0)
        GLES20.glUniform2f(program.uniform("u_texel"), 1f / fullW, 1f / fullH)
        GLES20.glUniform1f(program.uniform("u_radiusPx"), TAP_STEP_PX)
        GLES20.glUniform1f(program.uniform("u_spaceSmooth2"), SPACE_SMOOTH2)
        drawFullScreenQuad(program)
    }

    private fun composite(originalTex: Int, structureAmount: Float) {
        val program = compositeProgram ?: return
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFbo)
        GLES20.glViewport(0, 0, fullW, fullH)
        program.use()
        quad.setOrientation(0, flipHorizontal = false, flipVertical = false, sourceIsVideo = true)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, originalTex)
        GLES20.glUniform1i(program.uniform("u_original"), 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, diffTex)
        GLES20.glUniform1i(program.uniform("u_diff"), 1)

        GLES20.glUniform1f(program.uniform("u_intensity"), structureAmount.coerceIn(0f, 1f) * INTENSITY_SCALE)
        drawFullScreenQuad(program)
    }

    private fun drawFullScreenQuad(program: GlProgram) {
        quad.draw(program.attrib("a_position"), program.attrib("a_texCoord"))
    }
}
