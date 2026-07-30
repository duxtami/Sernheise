package com.sernheise.app.video

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.Surface
import com.sernheise.app.gl.Adjustments
import com.sernheise.app.gl.Quad
import com.sernheise.app.gl.RotationUtils
import com.sernheise.app.gl.SernShaders
import com.sernheise.app.gl.ShaderProgram
import com.sernheise.app.gl.StructurePipeline
import com.sernheise.app.utils.MediaUtils
import java.io.File

/**
 * Re-encodes a source video, applying the current [Adjustments] to every frame via the
 * same shader used for live preview. Audio (if present) is copied through untouched.
 *
 * Runs entirely on a background thread; call [export] off the main thread.
 */
class VideoExporter(private val context: Context) {

    interface Callback {
        /** [percent] is 0..100, based on how far through the source timeline export has reached. */
        fun onProgress(percent: Int)
        fun onSuccess(outputFile: File)
        fun onError(error: Exception)
    }

    private val TIMEOUT_US = 10_000L

    fun export(sourceUri: Uri, adjustments: Adjustments, outputFile: File, callback: Callback) {
        try {
            exportInternal(sourceUri, adjustments, outputFile, callback)
            callback.onSuccess(outputFile)
        } catch (e: Exception) {
            callback.onError(e)
        }
    }

    private fun exportInternal(sourceUri: Uri, adjustments: Adjustments, outputFile: File, callback: Callback) {
        val videoExtractor = MediaExtractor()
        videoExtractor.setDataSource(context, sourceUri, null)
        val videoTrackIndex = selectTrack(videoExtractor, "video/")
        require(videoTrackIndex >= 0) { "No video track found" }
        videoExtractor.selectTrack(videoTrackIndex)
        val inputFormat = videoExtractor.getTrackFormat(videoTrackIndex)

        // Single source of truth for raw decode size + rotation, shared with the live
        // preview -- this is what guarantees preview and export always agree.
        val geometry = MediaUtils.readVideoGeometry(context, sourceUri)
        val width = geometry.naturalWidth
        val height = geometry.naturalHeight
        val totalRotation = ((geometry.rotationDegrees + adjustments.rotation) % 360 + 360) % 360
        val (outputWidth, outputHeight) = RotationUtils.effectiveSize(width, height, totalRotation)

        val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION))
            inputFormat.getLong(MediaFormat.KEY_DURATION) else 0L

        // ---- Encoder (sized for the *post-rotation* orientation) ----
        val outputFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outputWidth, outputHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, estimateBitrate(outputWidth, outputHeight))
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encoderInputSurface = encoder.createInputSurface()
        encoder.start()

        // ---- EGL bound to the encoder's input surface ----
        val eglCore = CodecEglCore()
        eglCore.setup()
        val eglSurface = eglCore.createWindowSurface(encoderInputSurface)
        eglCore.makeCurrent(eglSurface)

        val shader = ShaderProgram(SernShaders.VERTEX, SernShaders.FRAGMENT_2D)
        val quad = Quad()
        val structurePipeline = StructurePipeline()
        // sourceIsVideo = true: the pipeline's output is always its own render-to-texture
        // result, which needs the plain/standard convention, same as SernRenderer.
        quad.setOrientation(totalRotation, adjustments.flipHorizontal, adjustments.flipVertical, sourceIsVideo = true)

        val oesTextureId = createOesTexture()
        val surfaceTexture = SurfaceTexture(oesTextureId)
        surfaceTexture.setDefaultBufferSize(width, height)

        val frameLatch = java.util.concurrent.atomic.AtomicBoolean(false)
        surfaceTexture.setOnFrameAvailableListener { frameLatch.set(true) }
        val decoderInputSurface = Surface(surfaceTexture)

        // ---- Decoder ----
        val decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(inputFormat, decoderInputSurface, null, 0)
        decoder.start()

        // ---- Muxer ----
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerVideoTrack = -1
        var muxerAudioTrack = -1
        var muxerStarted = false

        // Detect the audio track (if any) up front so both tracks can be registered
        // with the muxer together, before start() is called.
        val audioExtractorProbe = MediaExtractor()
        audioExtractorProbe.setDataSource(context, sourceUri, null)
        val probedAudioTrackIndex = selectTrack(audioExtractorProbe, "audio/")
        val audioFormat = if (probedAudioTrackIndex >= 0) audioExtractorProbe.getTrackFormat(probedAudioTrackIndex) else null
        audioExtractorProbe.release()

        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var decoderDone = false
        var encoderDone = false
        val texMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        // Identity: the output frame is already sized for the rotated content, and rotation
        // itself is handled by the quad's texcoord permutation, so no vertex transform is needed.
        val mvpMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        val identityMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

        var lastReportedPercent = -1

        while (!encoderDone) {
            // 1) Feed the decoder from the extractor.
            if (!inputDone) {
                val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val buffer = decoder.getInputBuffer(inIndex)!!
                    val sampleSize = videoExtractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, sampleSize, videoExtractor.sampleTime, 0)

                        if (durationUs > 0) {
                            val percent = ((videoExtractor.sampleTime * 100) / durationUs).toInt().coerceIn(0, 99)
                            if (percent != lastReportedPercent) {
                                lastReportedPercent = percent
                                callback.onProgress(percent)
                            }
                        }
                        videoExtractor.advance()
                    }
                }
            }

            // 2) Pull decoded frames, draw them through the shader into the encoder surface.
            if (!decoderDone) {
                val outIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outIndex >= 0) {
                    val doRender = bufferInfo.size > 0
                    decoder.releaseOutputBuffer(outIndex, doRender)
                    if (doRender) {
                        var waited = 0
                        while (!frameLatch.get() && waited < 50) {
                            Thread.sleep(10); waited++
                        }
                        frameLatch.set(false)
                        surfaceTexture.updateTexImage()
                        surfaceTexture.getTransformMatrix(texMatrix)

                        val structureAmount = (adjustments.structure / 100f).coerceIn(0f, 1f)
                        val finalTex = structurePipeline.process(
                            sourceTextureId = oesTextureId,
                            sourceIsOES = true,
                            sourceTexMatrix = texMatrix,
                            contentWidth = width,
                            contentHeight = height,
                            structureAmount = structureAmount
                        )

                        // The pipeline may have bound its own FBOs internally -- rebind the
                        // encoder's surface (framebuffer 0 in this EGL context) before drawing.
                        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                        GLES20.glViewport(0, 0, outputWidth, outputHeight)
                        GLES20.glClearColor(0f, 0f, 0f, 1f)
                        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                        shader.use()
                        GLES20.glUniformMatrix4fv(shader.uMvpMatrix, 1, false, mvpMatrix, 0)
                        // The real SurfaceTexture transform was already consumed inside the
                        // pipeline's resolve pass -- finalTex is plain and already corrected.
                        GLES20.glUniformMatrix4fv(shader.uTexMatrix, 1, false, identityMatrix, 0)
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, finalTex)
                        GLES20.glUniform1i(shader.uTexture, 0)
                        shader.applyAdjustments(adjustments, 1f / width, 1f / height)
                        quad.draw(shader)

                        eglCore.setPresentationTime(eglSurface, bufferInfo.presentationTimeUs * 1000)
                        eglCore.swapBuffers(eglSurface)
                    }
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        decoderDone = true
                        encoder.signalEndOfInputStream()
                    }
                }
            }

            // 3) Drain the encoder and write to the muxer.
            var encoderOutIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            while (encoderOutIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
                when {
                    encoderOutIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        muxerVideoTrack = muxer.addTrack(encoder.outputFormat)
                        if (audioFormat != null) {
                            muxerAudioTrack = muxer.addTrack(audioFormat)
                        }
                        muxer.start()
                        muxerStarted = true
                    }
                    encoderOutIndex >= 0 -> {
                        val encodedBuffer = encoder.getOutputBuffer(encoderOutIndex)!!
                        if (bufferInfo.size > 0 && muxerStarted) {
                            muxer.writeSampleData(muxerVideoTrack, encodedBuffer, bufferInfo)
                        }
                        val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        encoder.releaseOutputBuffer(encoderOutIndex, false)
                        if (isEos) { encoderDone = true }
                    }
                }
                if (encoderDone) break
                encoderOutIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            }
        }

        callback.onProgress(99) // audio copy + muxer finalize still to come

        // ---- Copy the audio track through untouched (no re-encode) ----
        if (audioFormat != null && muxerAudioTrack >= 0) {
            val audioExtractor = MediaExtractor()
            audioExtractor.setDataSource(context, sourceUri, null)
            audioExtractor.selectTrack(probeAudioTrackIndexOrThrow(audioExtractor))

            val audioBuffer = java.nio.ByteBuffer.allocate(1 shl 20)
            val audioInfo = MediaCodec.BufferInfo()
            while (true) {
                val size = audioExtractor.readSampleData(audioBuffer, 0)
                if (size < 0) break
                audioInfo.presentationTimeUs = audioExtractor.sampleTime
                audioInfo.flags = audioExtractor.sampleFlags
                audioInfo.offset = 0
                audioInfo.size = size
                muxer.writeSampleData(muxerAudioTrack, audioBuffer, audioInfo)
                audioExtractor.advance()
            }
            audioExtractor.release()
        }

        if (!muxerStarted) { muxer.start(); muxerStarted = true }
        muxer.stop()
        muxer.release()

        decoder.stop(); decoder.release()
        encoder.stop(); encoder.release()
        eglCore.releaseSurface(eglSurface)
        eglCore.release()
        decoderInputSurface.release()
        surfaceTexture.release()
        videoExtractor.release()

        callback.onProgress(100)
    }

    private fun probeAudioTrackIndexOrThrow(extractor: MediaExtractor): Int {
        val index = selectTrack(extractor, "audio/")
        require(index >= 0) { "Audio track disappeared unexpectedly" }
        return index
    }

    private fun selectTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return i
        }
        return -1
    }

    private fun estimateBitrate(width: Int, height: Int): Int {
        val pixels = width * height
        return (pixels * 4).coerceIn(2_000_000, 16_000_000)
    }

    private fun createOesTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }
}
