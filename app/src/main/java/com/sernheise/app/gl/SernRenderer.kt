package com.sernheise.app.gl

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

enum class SourceMode { NONE, IMAGE, VIDEO }

/**
 * Renders either a static [Bitmap] or a live video [SurfaceTexture] through the
 * Sernheise adjustment shader. One instance is shared by the on-screen preview.
 *
 * Rotation and flip are applied via [Quad.setOrientation] (a texture-coordinate
 * permutation), never via a vertex rotation matrix -- see [Quad] for why. The MVP
 * matrix here is responsible only for aspect-ratio letterboxing, computed from the
 * *effective* (already rotation-swapped) content dimensions.
 *
 * Structure is handled by [StructurePipeline] as a pre-pass: every frame, the source
 * (a photo's plain 2D texture, or a video frame resolved from OES) is run through the
 * pipeline first, producing a plain 2D texture that is always in the same orientation
 * convention regardless of source type. The main tonal shader (and the final rotation-
 * aware draw) then always operates on that normalized result, which is why there is only
 * one [ShaderProgram] variant here now instead of separate image/video ones.
 */
class SernRenderer(private val listener: Listener) : android.opengl.GLSurfaceView.Renderer {

    interface Listener {
        /** Called once the video input SurfaceTexture is ready to receive a MediaPlayer output. */
        fun onVideoSurfaceReady(surfaceTexture: SurfaceTexture)
        fun onFrameDrawn()
    }

    @Volatile
    var adjustments: Adjustments = Adjustments()

    /**
     * Clockwise rotation (0/90/180/270) baked into the *source* itself -- for video this
     * comes from the container's rotation metadata (read once via [com.sernheise.app.utils.MediaUtils.readVideoGeometry]);
     * for images it is always 0 because EXIF rotation is already baked into pixels at
     * decode time. The user's own Rotate-tool taps live separately in [Adjustments.rotation]
     * and are combined with this at render time -- never baked into the source.
     */
    @Volatile
    var sourceRotationDegrees: Int = 0

    @Volatile
    private var mode: SourceMode = SourceMode.NONE

    @Volatile
    private var pendingBitmap: Bitmap? = null

    private var mainProgram: ShaderProgram? = null
    private val quad = Quad()
    private val structurePipeline = StructurePipeline()

    private var imageTextureId = -1
    private var videoTextureId = -1
    private var videoSurfaceTexture: SurfaceTexture? = null

    private var contentWidth = 1
    private var contentHeight = 1
    private var viewWidth = 1
    private var viewHeight = 1

    private val mvpMatrix = FloatArray(16)
    private val texMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val identityMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    fun setBitmap(bitmap: Bitmap) {
        pendingBitmap = bitmap
        mode = SourceMode.IMAGE
        contentWidth = bitmap.width
        contentHeight = bitmap.height
    }

    fun prepareForVideo(width: Int, height: Int) {
        contentWidth = width
        contentHeight = height
        mode = SourceMode.VIDEO
    }

    fun updateAdjustments(newAdjustments: Adjustments) {
        adjustments = newAdjustments
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        mainProgram = ShaderProgram(SernShaders.VERTEX, SernShaders.FRAGMENT_2D)

        imageTextureId = createTexture(GLES20.GL_TEXTURE_2D)
        videoTextureId = createTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        videoSurfaceTexture = SurfaceTexture(videoTextureId)
        listener.onVideoSurfaceReady(videoSurfaceTexture!!)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        when (mode) {
            SourceMode.IMAGE -> drawImage()
            SourceMode.VIDEO -> drawVideo()
            SourceMode.NONE -> { /* nothing imported yet */ }
        }

        listener.onFrameDrawn()
    }

    private fun totalRotation(): Int = ((sourceRotationDegrees + adjustments.rotation) % 360 + 360) % 360

    private fun drawImage() {
        val bmp = pendingBitmap
        if (bmp != null) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTextureId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            pendingBitmap = null
        }

        val structureAmount = (adjustments.structure / 100f).coerceIn(0f, 1f)
        val finalTex = structurePipeline.process(
            sourceTextureId = imageTextureId,
            sourceIsOES = false,
            sourceTexMatrix = identityMatrix,
            contentWidth = contentWidth,
            contentHeight = contentHeight,
            structureAmount = structureAmount
        )

        // The pipeline may have changed the bound framebuffer/viewport internally --
        // this is the on-screen path, so rebind the default framebuffer before drawing.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, viewWidth, viewHeight)

        val program = mainProgram ?: return
        program.use()

        computeMvp()
        GLES20.glUniformMatrix4fv(program.uMvpMatrix, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(program.uTexMatrix, 1, false, identityMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, finalTex)
        GLES20.glUniform1i(program.uTexture, 0)

        program.applyAdjustments(adjustments, 1f / contentWidth, 1f / contentHeight)
        // sourceIsVideo = true: finalTex is always the structure pipeline's own render-to-
        // texture output now (even when Structure is off -- see StructurePipeline), which
        // needs the plain/standard convention, not the Bitmap-upload-specific one.
        quad.setOrientation(totalRotation(), adjustments.flipHorizontal, adjustments.flipVertical, sourceIsVideo = true)
        quad.draw(program)
    }

    private fun drawVideo() {
        val surfaceTexture = videoSurfaceTexture ?: return
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(texMatrix)

        val structureAmount = (adjustments.structure / 100f).coerceIn(0f, 1f)
        // Video always goes through the pipeline's resolve step (OES -> plain 2D), even
        // when Structure is off, so the main shader below never needs to know or care
        // whether the source was a photo or a video frame.
        val finalTex = structurePipeline.process(
            sourceTextureId = videoTextureId,
            sourceIsOES = true,
            sourceTexMatrix = texMatrix,
            contentWidth = contentWidth,
            contentHeight = contentHeight,
            structureAmount = structureAmount
        )

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, viewWidth, viewHeight)

        val program = mainProgram ?: return
        program.use()

        computeMvp()
        GLES20.glUniformMatrix4fv(program.uMvpMatrix, 1, false, mvpMatrix, 0)
        // The real SurfaceTexture transform was already consumed inside the pipeline's
        // resolve pass -- finalTex is a plain, already-corrected 2D texture, so this is identity.
        GLES20.glUniformMatrix4fv(program.uTexMatrix, 1, false, identityMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, finalTex)
        GLES20.glUniform1i(program.uTexture, 0)

        program.applyAdjustments(adjustments, 1f / contentWidth, 1f / contentHeight)
        // sourceIsVideo = true: finalTex is the pipeline's own render-to-texture output,
        // which needs the plain/standard convention regardless of the original source.
        quad.setOrientation(totalRotation(), adjustments.flipHorizontal, adjustments.flipVertical, sourceIsVideo = true)
        quad.draw(program)
    }

    /**
     * Fits the content rectangle inside the view bounds, preserving aspect ratio (letterbox).
     * This handles ONLY the fit scale; rotation/flip are applied separately via
     * [Quad.setOrientation] so this matrix never needs to encode a rotation itself.
     */
    private fun computeMvp() {
        Matrix.setIdentityM(mvpMatrix, 0)
        if (viewWidth == 0 || viewHeight == 0 || contentWidth == 0 || contentHeight == 0) return

        val (effectiveWidth, effectiveHeight) = RotationUtils.effectiveSize(contentWidth, contentHeight, totalRotation())

        val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()
        val contentAspect = effectiveWidth.toFloat() / effectiveHeight.toFloat()

        var scaleX = 1f
        var scaleY = 1f
        if (contentAspect > viewAspect) {
            scaleY = viewAspect / contentAspect
        } else {
            scaleX = contentAspect / viewAspect
        }
        Matrix.scaleM(mvpMatrix, 0, scaleX, scaleY, 1f)
    }

    /**
     * Renders the currently loaded still image at full resolution with the given
     * [exportAdjustments] into an off-screen framebuffer and reads the pixels back.
     * Must be invoked on the GL thread (e.g. via `GLSurfaceView.queueEvent`).
     */
    fun renderImageToBitmap(exportWidth: Int, exportHeight: Int, exportAdjustments: Adjustments): Bitmap {
        val program = mainProgram ?: throw IllegalStateException("Renderer not initialized")

        val structureAmount = (exportAdjustments.structure / 100f).coerceIn(0f, 1f)
        val sourceTex = structurePipeline.process(
            sourceTextureId = imageTextureId,
            sourceIsOES = false,
            sourceTexMatrix = identityMatrix,
            contentWidth = contentWidth,
            contentHeight = contentHeight,
            structureAmount = structureAmount
        )

        val frameBuffers = IntArray(1)
        val renderTextures = IntArray(1)
        GLES20.glGenFramebuffers(1, frameBuffers, 0)
        GLES20.glGenTextures(1, renderTextures, 0)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, renderTextures[0])
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, exportWidth, exportHeight, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBuffers[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, renderTextures[0], 0
        )

        GLES20.glViewport(0, 0, exportWidth, exportHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        program.use()
        Matrix.setIdentityM(mvpMatrix, 0) // full-frame, no letterboxing on export -- output is already the correct rotated aspect
        GLES20.glUniformMatrix4fv(program.uMvpMatrix, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(program.uTexMatrix, 1, false, identityMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sourceTex)
        GLES20.glUniform1i(program.uTexture, 0)
        // Texel size must match the SOURCE texture's native resolution (contentWidth/Height),
        // not the output framebuffer's (exportWidth/Height) -- those differ whenever rotation
        // is 90/270, and using the wrong one would skew Sharpen's neighbor sampling.
        program.applyAdjustments(exportAdjustments, 1f / contentWidth, 1f / contentHeight)
        // Images have no source rotation of their own (EXIF is already baked into pixels at
        // decode time) -- only the user's own Rotate/Flip state applies here. sourceIsVideo =
        // true because sourceTex is the pipeline's own render-to-texture output (see above).
        quad.setOrientation(exportAdjustments.rotation, exportAdjustments.flipHorizontal, exportAdjustments.flipVertical, sourceIsVideo = true)
        quad.draw(program)

        val buffer = java.nio.ByteBuffer.allocateDirect(exportWidth * exportHeight * 4)
        buffer.order(java.nio.ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, exportWidth, exportHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)

        val bitmap = Bitmap.createBitmap(exportWidth, exportHeight, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)

        // GL reads bottom-up; flip vertically to match standard bitmap orientation.
        val flipMatrix = android.graphics.Matrix().apply { postScale(1f, -1f, exportWidth / 2f, exportHeight / 2f) }
        val flipped = Bitmap.createBitmap(bitmap, 0, 0, exportWidth, exportHeight, flipMatrix, true)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glDeleteFramebuffers(1, frameBuffers, 0)
        GLES20.glDeleteTextures(1, renderTextures, 0)
        GLES20.glViewport(0, 0, viewWidth, viewHeight)

        return flipped
    }

    private fun createTexture(target: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(target, ids[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }
}
