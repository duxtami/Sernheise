package com.sernheise.app.gl

import android.opengl.GLES11Ext
import android.opengl.GLES20

class StructurePipeline {

    companion object {
        private const val AMOUNT_EPSILON = 0.004f
    }

    private var resolveOesProgram: GlProgram? = null
    private var resolveImageProgram: GlProgram? = null
    private var blurProgram: GlProgram? = null
    private var bilateralProgram: GlProgram? = null
    private var compositeProgram: GlProgram? = null

    private val quad = Quad()

    private var fullW = 0
    private var fullH = 0
    private var smallW = 0
    private var smallH = 0

    private var resolvedTex = -1; private var resolvedFbo = -1
    private var downscaledTex = -1; private var downscaledFbo = -1
    private var blurHTex = -1; private var blurHFbo = -1
    private var clarityBlurTex = -1; private var clarityBlurFbo = -1
    private var structureBlurTex = -1; private var structureBlurFbo = -1
    private var outputTex = -1; private var outputFbo = -1

    private inline fun drawPass(program: GlProgram, block: (GlProgram) -> Unit) {
        program.use()
        quad.setOrientation(0, false, false, true) // Internal FBO passes use standard mapping
        block(program)
        quad.draw(program.attrib("a_position"), program.attrib("a_texCoord"))
    }

    fun process(
        sourceTextureId: Int,
        sourceIsOES: Boolean,
        sourceTexMatrix: FloatArray,
        contentWidth: Int,
        contentHeight: Int,
        structureAmount: Float,
        clarityAmount: Float
    ): Int {
        ensurePrograms()
        ensureAllocated(contentWidth, contentHeight)

        // 1. Resolve to RGB
        resolveToPipeline(sourceTextureId, sourceIsOES, sourceTexMatrix)

        if (Math.abs(structureAmount) <= AMOUNT_EPSILON && Math.abs(clarityAmount) <= AMOUNT_EPSILON) {
            return resolvedTex
        }

        // 2. Create Downscaled Image
        drawPass(resolveImageProgram!!) { program ->
            GLES20.glViewport(0, 0, smallW, smallH)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, downscaledFbo)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, resolvedTex)
            GLES20.glUniform1i(program.uniform("u_original"), 0)
        }

        // 3. Blur Horizontal for Clarity
        drawPass(blurProgram!!) { program ->
            GLES20.glViewport(0, 0, smallW, smallH)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, blurHFbo)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, downscaledTex)
            GLES20.glUniform1i(program.uniform("u_texture"), 0)
            GLES20.glUniform2f(program.uniform("u_step"), 1.0f / smallW, 0f)
        }

        // 4. Blur Vertical for Clarity -> clarityBlurTex
        drawPass(blurProgram!!) { program ->
            GLES20.glViewport(0, 0, smallW, smallH)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, clarityBlurFbo)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blurHTex)
            GLES20.glUniform1i(program.uniform("u_texture"), 0)
            GLES20.glUniform2f(program.uniform("u_step"), 0f, 1.0f / smallH)
        }

        // 5. Bilateral Blur for Structure -> structureBlurTex
        drawPass(bilateralProgram!!) { program ->
            GLES20.glViewport(0, 0, fullW, fullH)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, structureBlurFbo)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, resolvedTex)
            GLES20.glUniform1i(program.uniform("u_original"), 0)
            GLES20.glUniform2f(program.uniform("u_texel"), 1.0f / fullW, 1.0f / fullH)
        }

        // 6. Composite
        drawPass(compositeProgram!!) { program ->
            GLES20.glViewport(0, 0, fullW, fullH)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFbo)
            
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, resolvedTex)
            GLES20.glUniform1i(program.uniform("u_original"), 0)
            
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, clarityBlurTex)
            GLES20.glUniform1i(program.uniform("u_clarityBlur"), 1)
            
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, structureBlurTex)
            GLES20.glUniform1i(program.uniform("u_structureBlur"), 2)
            
            GLES20.glUniform1f(program.uniform("u_clarityAmount"), clarityAmount)
            GLES20.glUniform1f(program.uniform("u_structureAmount"), structureAmount)
        }

        return outputTex
    }

    private fun resolveToPipeline(sourceTex: Int, isOES: Boolean, texMatrix: FloatArray) {
        val program = if (isOES) resolveOesProgram!! else resolveImageProgram!!
        val target = if (isOES) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D

        program.use()
        quad.setOrientation(0, false, false, isOES) // Initial resolve uses specific source mapping
        
        GLES20.glViewport(0, 0, fullW, fullH)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, resolvedFbo)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, sourceTex)
        GLES20.glUniform1i(program.uniform("u_original"), 0)
        
        if (isOES) {
            GLES20.glUniformMatrix4fv(program.uniform("u_texMatrix"), 1, false, texMatrix, 0)
        }
        
        quad.draw(program.attrib("a_position"), program.attrib("a_texCoord"))
    }

    private fun ensurePrograms() {
        if (resolveOesProgram == null) resolveOesProgram = GlProgram(StructureShaders.PASSTHROUGH_VERTEX, StructureShaders.RESOLVE_OES)
        if (resolveImageProgram == null) resolveImageProgram = GlProgram(StructureShaders.PASSTHROUGH_VERTEX, StructureShaders.RESOLVE_2D)
        if (blurProgram == null) blurProgram = GlProgram(StructureShaders.PASSTHROUGH_VERTEX, StructureShaders.BLUR_FRAGMENT)
        if (bilateralProgram == null) bilateralProgram = GlProgram(StructureShaders.PASSTHROUGH_VERTEX, StructureShaders.BILATERAL_FRAGMENT)
        if (compositeProgram == null) compositeProgram = GlProgram(StructureShaders.PASSTHROUGH_VERTEX, StructureShaders.COMPOSITE_FRAGMENT)
    }

    private fun ensureAllocated(w: Int, h: Int) {
        if (w == fullW && h == fullH && resolvedTex != -1) return

        releaseTargets()

        fullW = w
        fullH = h
        smallW = Math.max(1, w / 4)
        smallH = Math.max(1, h / 4)

        val rt1 = createRenderTarget(fullW, fullH); resolvedTex = rt1.first; resolvedFbo = rt1.second
        val rt2 = createRenderTarget(smallW, smallH); downscaledTex = rt2.first; downscaledFbo = rt2.second
        val rt3 = createRenderTarget(smallW, smallH); blurHTex = rt3.first; blurHFbo = rt3.second
        val rt4 = createRenderTarget(smallW, smallH); clarityBlurTex = rt4.first; clarityBlurFbo = rt4.second
        val rt5 = createRenderTarget(fullW, fullH); structureBlurTex = rt5.first; structureBlurFbo = rt5.second
        val rt6 = createRenderTarget(fullW, fullH); outputTex = rt6.first; outputFbo = rt6.second
    }

    private fun createRenderTarget(width: Int, height: Int): Pair<Int, Int> {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val tex = textures[0]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val fbos = IntArray(1)
        GLES20.glGenFramebuffers(1, fbos, 0)
        val fbo = fbos[0]

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0)

        return Pair(tex, fbo)
    }

    fun release() {
        releaseTargets()
        resolveOesProgram?.let { GLES20.glDeleteProgram(it.handle) }; resolveOesProgram = null
        resolveImageProgram?.let { GLES20.glDeleteProgram(it.handle) }; resolveImageProgram = null
        blurProgram?.let { GLES20.glDeleteProgram(it.handle) }; blurProgram = null
        bilateralProgram?.let { GLES20.glDeleteProgram(it.handle) }; bilateralProgram = null
        compositeProgram?.let { GLES20.glDeleteProgram(it.handle) }; compositeProgram = null
    }

    private fun releaseTargets() {
        val textures = intArrayOf(resolvedTex, downscaledTex, blurHTex, clarityBlurTex, structureBlurTex, outputTex).filter { it != -1 }.toIntArray()
        if (textures.isNotEmpty()) GLES20.glDeleteTextures(textures.size, textures, 0)

        val fbos = intArrayOf(resolvedFbo, downscaledFbo, blurHFbo, clarityBlurFbo, structureBlurFbo, outputFbo).filter { it != -1 }.toIntArray()
        if (fbos.isNotEmpty()) GLES20.glDeleteFramebuffers(fbos.size, fbos, 0)

        resolvedTex = -1; downscaledTex = -1; blurHTex = -1; clarityBlurTex = -1; structureBlurTex = -1; outputTex = -1
        resolvedFbo = -1; downscaledFbo = -1; blurHFbo = -1; clarityBlurFbo = -1; structureBlurFbo = -1; outputFbo = -1
    }
}
