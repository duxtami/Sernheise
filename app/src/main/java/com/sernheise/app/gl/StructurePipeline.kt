package com.sernheise.app.gl

import android.opengl.GLES11Ext
import android.opengl.GLES20

/**
 * The "Structure" effect (see [StructureShaders]): a bilateral-blur detail map,
 * added back as original + intensity * (original - bilateralBlur).
 *
 * Every source is first resolved into [resolvedTex] via [resolveToPipeline]; later passes
 * sample the pipeline's own textures with the plain convention (`sourceIsVideo = true`).
 */
class StructurePipeline {

    companion object {
        private const val GL_HALF_FLOAT_OES = 0x8D61
        private const val AMOUNT_EPSILON = 0.004f
        // Slider is 0..1; full slider = intensity 1.5.
        private const val MAX_INTENSITY = 1.5f
    }

    private var resolveOesProgram: GlProgram? = null
    private var resolveImageProgram: GlProgram? = null
    private var diffProgram: GlProgram? = null
    private var compositeProgram: GlProgram? = null

    private val quad = Quad()

    private var usingHalfFloat = false
    private var halfFloatChecked = false

    private var fullW = 0
    private var fullH = 0

    private var resolvedTex = -1; private var resolvedFbo = -1
    private var diffTex = -1; private var diffFbo = -1
    private var outputTex = -1; private var outputFbo = -1

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
        if (structureAmount <= AMOUNT_EPSILON) return resolvedTex

        runDiff()
        composite(structureAmount)
        return outputTex
    }

    private fun ensurePrograms() {
        if (resolveOesProgram == null) resolveOesProgram = GlProgram(StructureShaders.PASSTHROUGH_VERTEX, StructureShaders.RESOLVE_FRAGMENT_OES)
        if (resolveImageProgram == null) resolveImageProgram = GlProgram(StructureShaders.PASSTHROUGH_VERTEX, StructureShaders.RESOLVE_FRAGMENT_2D)
        if (diffProgram == null) diffProgram = GlProgram(StructureShaders.PASSTHROUGH_VERTEX, StructureShaders.DIFF_FRAGMENT)
        if (compositeProgram == null) compositeProgram = GlProgram(StructureShaders.PASSTHROUGH_VERTEX, StructureShaders.COMPOSITE_FRAGMENT)
    }

    private fun ensureAllocated(contentWidth: Int, contentHeight: Int) {
        if (contentWidth == fullW && contentHeight == fullH && resolvedTex != -1 && outputTex != -1) return

        releaseTargets()
        checkHalfFloatSupport()

        fullW = contentWidth
        fullH = contentHeight

        val (rt, rf) = createRenderTarget(fullW, fullH); resolvedTex = rt; resolvedFbo = rf
        val (dt, df) = createRenderTarget(fullW, fullH); diffTex = dt; diffFbo = df
        val (ot, of) = createRenderTarget(fullW, fullH); outputTex = ot; outputFbo = of
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
        val texes = intArrayOf(resolvedTex, diffTex, outputTex).filter { it >= 0 }
        val fbos = intArrayOf(resolvedFbo, diffFbo, outputFbo).filter { it >= 0 }
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

    /** Pass 1: bilateral detail map into [diffTex]. */
    private fun runDiff() {
        val program = diffProgram ?: return
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, diffFbo)
        GLES20.glViewport(0, 0, fullW, fullH)
        program.use()
        quad.setOrientation(0, flipHorizontal = false, flipVertical = false, sourceIsVideo = true)
        bindSampler(program, "u_texture", 0, resolvedTex)
        // bstep = max(1, max(W, H) / 500) with integer division, so sample spacing scales with resolution.
        val bstep = maxOf(1f, (maxOf(fullW, fullH) / 500).toFloat())
        GLES20.glUniform2f(program.uniform("u_step"), bstep / fullW, bstep / fullH)
        drawFullScreenQuad(program)
    }

    /** Pass 2: original + intensity * detail. */
    private fun composite(structureAmount: Float) {
        val program = compositeProgram ?: return
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFbo)
        GLES20.glViewport(0, 0, fullW, fullH)
        program.use()
        quad.setOrientation(0, flipHorizontal = false, flipVertical = false, sourceIsVideo = true)
        bindSampler(program, "u_original", 0, resolvedTex)
        bindSampler(program, "u_diff", 1, diffTex)
        GLES20.glUniform1f(program.uniform("u_intensity"), structureAmount.coerceIn(0f, 1f) * MAX_INTENSITY)
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
