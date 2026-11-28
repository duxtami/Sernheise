package com.sernheise.app.video

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.sernheise.app.gl.Adjustments
import com.sernheise.app.gl.Quad
import com.sernheise.app.gl.RotationUtils
import com.sernheise.app.gl.SernShaders
import com.sernheise.app.gl.ShaderProgram
import com.sernheise.app.gl.StructurePipeline
import com.sernheise.app.utils.MediaUtils
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

private const val TAG = "VideoExporter"

/**
 * Re-encodes a source video, applying the current [Adjustments] to every frame via the
 * same shader used for live preview. Preserves audio reliably by passing through verified
 * plain AAC-LC or transcoding any other/non-standard audio track to high-bitrate AAC-LC.
 *
 * Runs entirely on a background thread; call [export] off the main thread.
 */
class VideoExporter(private val context: Context) {

    interface Callback {
        /** [percent] is 0..100, based on how far through the source timeline export has reached. */
        fun onProgress(percent: Int)
        fun onSuccess(outputFile: File)
        fun onError(error: Exception)
        /** Called instead of [onSuccess]/[onError] when [cancel] was requested mid-export. */
        fun onCancelled() {}
    }

    private val TIMEOUT_US = 10_000L

    @Volatile
    private var cancelled = false

    /** Request the in-flight [export] to stop as soon as possible. Safe to call from any thread. */
    fun cancel() {
        cancelled = true
    }

    fun export(sourceUri: Uri, adjustments: Adjustments, outputFile: File, callback: Callback) {
        try {
            exportInternal(sourceUri, adjustments, outputFile, callback)
            if (cancelled) {
                try { outputFile.delete() } catch (ignored: Exception) {}
                callback.onCancelled()
            } else {
                callback.onSuccess(outputFile)
            }
        } catch (e: Exception) {
            try {
                File(context.cacheDir, "export_error.txt").writeText(Log.getStackTraceString(e))
            } catch (ignored: Exception) {}
            if (cancelled) {
                try { outputFile.delete() } catch (ignored: Exception) {}
                callback.onCancelled()
            } else {
                callback.onError(e)
            }
        }
    }

    private fun exportInternal(sourceUri: Uri, adjustments: Adjustments, outputFile: File, callback: Callback) {
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var muxerReleased = false
        var eglCore: CodecEglCore? = null
        var eglSurface: android.opengl.EGLSurface? = null
        var decoderInputSurface: Surface? = null
        var surfaceTexture: SurfaceTexture? = null
        var frameThread: HandlerThread? = null

        var audioDebugMsg = ""
        var audioFramesWritten = 0
        var videoFramesWritten = 0
        var probedAudioTrackIndex = -1
        var transcodedAudioFormat: MediaFormat? = null
        var transcodedAudio: List<TranscodedAudioSample>? = null

        try {
            val vExtractor = MediaExtractor()
            videoExtractor = vExtractor
            vExtractor.setDataSource(context, sourceUri, null)
            val videoTrackIndex = selectTrack(vExtractor, "video/")
            require(videoTrackIndex >= 0) { "No video track found" }
            vExtractor.selectTrack(videoTrackIndex)
            val inputFormat = vExtractor.getTrackFormat(videoTrackIndex)

            // Single source of truth for raw decode size + rotation, shared with live preview.
            val geometry = MediaUtils.readVideoGeometry(context, sourceUri)
            var width = geometry.naturalWidth
            var height = geometry.naturalHeight
            val totalRotation = ((geometry.rotationDegrees + adjustments.rotation) % 360 + 360) % 360
            var outputSize = RotationUtils.effectiveSize(width, height, totalRotation)
            var outputWidth = outputSize.first
            var outputHeight = outputSize.second

            val durationUs = getLongOrNull(inputFormat, MediaFormat.KEY_DURATION) ?: 0L
            val inputFrameRate = (getIntOrNull(inputFormat, MediaFormat.KEY_FRAME_RATE) ?: 30).coerceIn(1, 120)
            val sourceBitrate = getIntOrNull(inputFormat, MediaFormat.KEY_BIT_RATE)

            fun computeDesiredBitrate(outW: Int, outH: Int): Long {
                val pixelTarget = (outW.toLong() * outH.toLong() * 30L).coerceAtLeast(20_000_000L)
                return if (sourceBitrate != null && sourceBitrate > 0) {
                    maxOf(sourceBitrate.toLong() * 4L, pixelTarget)
                } else {
                    pixelTarget
                }
            }

            // Dedicated thread for SurfaceTexture callbacks so frame sync never depends on the UI thread.
            val syncThread = HandlerThread("SernheiseFrameSync").apply { start() }
            frameThread = syncThread
            val syncHandler = Handler(syncThread.looper)

            val oesTextureId = createOesTexture()
            val st = SurfaceTexture(oesTextureId)
            surfaceTexture = st
            st.setDefaultBufferSize(width, height)

            val frameLock = ReentrantLock()
            val frameCondition = frameLock.newCondition()
            var frameAvailable = false
            st.setOnFrameAvailableListener({
                frameLock.lock()
                try {
                    frameAvailable = true
                    frameCondition.signalAll()
                } finally {
                    frameLock.unlock()
                }
            }, syncHandler)
            val decSurface = Surface(st)
            decoderInputSurface = decSurface

            // Keep raw decoded frames un-rotated; Quad applies totalRotation consistently.
            if (inputFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                inputFormat.setInteger(MediaFormat.KEY_ROTATION, 0)
            }
            val dec = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
            decoder = dec
            dec.configure(inputFormat, decSurface, null, 0)
            dec.start()

            // Probe decoder's real output format before locking encoder dimensions.
            // Preserve the first decoded frame if it arrives before/without INFO_OUTPUT_FORMAT_CHANGED.
            var quadRotation = totalRotation
            var inputDone = false
            var decoderDone = false
            var pendingFirstOutIndex = -1
            val pendingFirstOutInfo = MediaCodec.BufferInfo()
            run {
                val probeBufferInfo = MediaCodec.BufferInfo()
                var formatSeen = false
                var attempts = 0
                while (!formatSeen && attempts < 100 && !cancelled) {
                    attempts++
                    if (!inputDone) {
                        val inIdx = dec.dequeueInputBuffer(TIMEOUT_US)
                        if (inIdx >= 0) {
                            val buf = dec.getInputBuffer(inIdx)!!
                            val sampleSize = vExtractor.readSampleData(buf, 0)
                            if (sampleSize < 0) {
                                dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                dec.queueInputBuffer(inIdx, 0, sampleSize, vExtractor.sampleTime, 0)
                                vExtractor.advance()
                            }
                        }
                    }
                    val outIdx = dec.dequeueOutputBuffer(probeBufferInfo, TIMEOUT_US)
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        formatSeen = true
                        val realFormat = dec.outputFormat
                        val realWidth = getIntOrNull(realFormat, MediaFormat.KEY_WIDTH) ?: width
                        val realHeight = getIntOrNull(realFormat, MediaFormat.KEY_HEIGHT) ?: height
                        val matchesRaw = realWidth == width && realHeight == height
                        val matchesRotated = realWidth == height && realHeight == width
                        if (realWidth > 0 && realHeight > 0 && !matchesRaw) {
                            if (matchesRotated) {
                                Log.w(
                                    TAG,
                                    "Decoder delivered already-rotated ${realWidth}x$realHeight -- disabling quad rotation"
                                )
                                width = realWidth
                                height = realHeight
                                quadRotation = 0
                                outputSize = width to height
                            } else {
                                Log.w(
                                    TAG,
                                    "Decoder output size ${realWidth}x$realHeight differs from ${width}x$height -- re-syncing"
                                )
                                width = realWidth
                                height = realHeight
                                outputSize = RotationUtils.effectiveSize(width, height, totalRotation)
                            }
                            outputWidth = outputSize.first
                            outputHeight = outputSize.second
                            st.setDefaultBufferSize(width, height)
                        }
                    } else if (outIdx >= 0) {
                        val realFormat = dec.outputFormat
                        val realWidth = getIntOrNull(realFormat, MediaFormat.KEY_WIDTH) ?: width
                        val realHeight = getIntOrNull(realFormat, MediaFormat.KEY_HEIGHT) ?: height
                        if (realWidth > 0 && realHeight > 0 && (realWidth != width || realHeight != height)) {
                            if (realWidth == height && realHeight == width) {
                                width = realWidth
                                height = realHeight
                                quadRotation = 0
                                outputSize = width to height
                            } else {
                                width = realWidth
                                height = realHeight
                                outputSize = RotationUtils.effectiveSize(width, height, totalRotation)
                            }
                            outputWidth = outputSize.first
                            outputHeight = outputSize.second
                            st.setDefaultBufferSize(width, height)
                        }
                        pendingFirstOutIndex = outIdx
                        pendingFirstOutInfo.set(
                            probeBufferInfo.offset,
                            probeBufferInfo.size,
                            probeBufferInfo.presentationTimeUs,
                            probeBufferInfo.flags
                        )
                        formatSeen = true
                    }
                }
            }

            if (cancelled) return

            // ---- Encoder (high quality, even dimensions, clamped within device capabilities) ----
            val configuredEncoder = createAndConfigureVideoEncoder(
                requestedWidth = outputWidth,
                requestedHeight = outputHeight,
                requestedFps = inputFrameRate,
                desiredBitrate = computeDesiredBitrate(outputWidth, outputHeight)
            )
            encoder = configuredEncoder.encoder
            outputWidth = configuredEncoder.width
            outputHeight = configuredEncoder.height
            val enc = encoder
            val encoderInputSurface = enc.createInputSurface()
            enc.start()

            // ---- EGL bound to the encoder's input surface ----
            val egl = CodecEglCore()
            eglCore = egl
            egl.setup()
            val eglSurf = egl.createWindowSurface(encoderInputSurface)
            eglSurface = eglSurf
            egl.makeCurrent(eglSurf)

            val shader = ShaderProgram(SernShaders.VERTEX, SernShaders.FRAGMENT_2D)
            val quad = Quad()
            val structurePipeline = StructurePipeline()
            quad.setOrientation(quadRotation, adjustments.flipHorizontal, adjustments.flipVertical, sourceIsVideo = true)

            // ---- Prepare audio before starting the video mux loop ----
            var audioFormat: MediaFormat? = null
            var transcodedAudioIndex = 0
            var audioBufferSize = 1 shl 20

            var lastReportedPercent = -1
            val audioPrepMaxPercent = 12
            var audioPrepRan = false

            val probe = MediaExtractor()
            var keepProbeAsAudioExtractor = false
            try {
                probe.setDataSource(context, sourceUri, null)
                probedAudioTrackIndex = selectBestAudioTrack(probe)
                if (probedAudioTrackIndex >= 0) {
                    val probedFormat = probe.getTrackFormat(probedAudioTrackIndex)
                    val maxIn = getIntOrNull(probedFormat, MediaFormat.KEY_MAX_INPUT_SIZE) ?: 0
                    if (maxIn > audioBufferSize) audioBufferSize = maxIn

                    if (isPlainAacLcForPassthrough(probedFormat)) {
                        probe.selectTrack(probedAudioTrackIndex)
                        if (probe.sampleTrackIndex >= 0) {
                            audioExtractor = probe
                            keepProbeAsAudioExtractor = true
                            audioFormat = buildCleanAacFormat(probedFormat)
                        }
                    }
                }
            } finally {
                if (!keepProbeAsAudioExtractor) {
                    probe.release()
                }
            }

            if (probedAudioTrackIndex >= 0 && audioFormat == null && !cancelled) {
                audioPrepRan = true
                val transcoded = transcodeAudioToAac(sourceUri, probedAudioTrackIndex) { audioPercent ->
                    val scaled = (audioPercent * audioPrepMaxPercent / 100).coerceIn(0, audioPrepMaxPercent)
                    if (scaled != lastReportedPercent) {
                        lastReportedPercent = scaled
                        callback.onProgress(scaled)
                    }
                }
                if (transcoded != null && transcoded.second.isNotEmpty()) {
                    audioFormat = transcoded.first
                    transcodedAudioFormat = transcoded.first
                    transcodedAudio = transcoded.second
                }
            }

            if (cancelled) return

            // ---- Muxer ----
            val mx = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mx
            var muxerVideoTrack = -1
            var muxerAudioTrack = -1

            val videoRangeStart = if (audioPrepRan) audioPrepMaxPercent else 0
            val bufferInfo = MediaCodec.BufferInfo()
            var encoderDone = false
            val texMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
            val mvpMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
            val identityMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

            val audioBuffer = ByteBuffer.allocateDirect(audioBufferSize)
            val audioInfo = MediaCodec.BufferInfo()
            var audioDone = true
            var lastVideoPtsUs = -1L
            var lastAudioPtsUs = -1L

            fun renderDecodedFrame(outIdx: Int, frameInfo: MediaCodec.BufferInfo) {
                val doRender = frameInfo.size > 0
                if (doRender) {
                    frameLock.lock()
                    try {
                        frameAvailable = false
                    } finally {
                        frameLock.unlock()
                    }
                }
                dec.releaseOutputBuffer(outIdx, doRender)
                if (doRender) {
                    var timedOut = false
                    frameLock.lock()
                    try {
                        var remainingNanos = TimeUnit.MILLISECONDS.toNanos(2_500L)
                        while (!frameAvailable && !cancelled) {
                            if (remainingNanos <= 0L) {
                                timedOut = true
                                break
                            }
                            remainingNanos = frameCondition.awaitNanos(remainingNanos)
                        }
                    } finally {
                        frameLock.unlock()
                    }
                    if (!cancelled && (!timedOut || frameAvailable)) {
                        st.updateTexImage()
                        st.getTransformMatrix(texMatrix)

                        val structureAmount = (adjustments.structure / 100f).coerceIn(0f, 1f)
                        val finalTex = structurePipeline.process(
                            sourceTextureId = oesTextureId,
                            sourceIsOES = true,
                            sourceTexMatrix = texMatrix,
                            contentWidth = width,
                            contentHeight = height,
                            structureAmount = structureAmount
                        )

                        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                        GLES20.glViewport(0, 0, outputWidth, outputHeight)
                        GLES20.glClearColor(0f, 0f, 0f, 1f)
                        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                        shader.use()
                        GLES20.glUniformMatrix4fv(shader.uMvpMatrix, 1, false, mvpMatrix, 0)
                        GLES20.glUniformMatrix4fv(shader.uTexMatrix, 1, false, identityMatrix, 0)
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, finalTex)
                        GLES20.glUniform1i(shader.uTexture, 0)
                        shader.applyAdjustments(adjustments, 1f / width, 1f / height)
                        quad.draw(shader)

                        val rawPts = frameInfo.presentationTimeUs.coerceAtLeast(0L)
                        val safePts = if (lastVideoPtsUs >= 0L && rawPts <= lastVideoPtsUs) {
                            lastVideoPtsUs + 1L
                        } else {
                            rawPts
                        }
                        lastVideoPtsUs = safePts

                        GLES20.glFlush()
                        egl.setPresentationTime(eglSurf, safePts * 1000L)
                        egl.swapBuffers(eglSurf)
                    }
                }
                if ((frameInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    decoderDone = true
                    enc.signalEndOfInputStream()
                }
            }

            fun writeTranscodedAudioSample(sample: TranscodedAudioSample) {
                audioBuffer.clear()
                audioBuffer.put(sample.data)
                audioBuffer.flip()
                val safePts = when {
                    lastAudioPtsUs < 0L -> sample.presentationTimeUs.coerceAtLeast(0L)
                    sample.presentationTimeUs <= lastAudioPtsUs -> lastAudioPtsUs + 1L
                    else -> sample.presentationTimeUs
                }
                lastAudioPtsUs = safePts
                audioInfo.set(0, sample.data.size, safePts, sample.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                mx.writeSampleData(muxerAudioTrack, audioBuffer, audioInfo)
                audioFramesWritten++
            }

            fun writeAudioUpTo(thresholdUs: Long?) {
                if (muxerAudioTrack < 0 || audioDone) return
                val transcoded = transcodedAudio
                if (transcoded != null) {
                    while (transcodedAudioIndex < transcoded.size) {
                        val sample = transcoded[transcodedAudioIndex]
                        if (thresholdUs != null && sample.presentationTimeUs > thresholdUs) break
                        writeTranscodedAudioSample(sample)
                        transcodedAudioIndex++
                    }
                    if (transcodedAudioIndex >= transcoded.size) audioDone = true
                } else {
                    val aExt = audioExtractor ?: return
                    while (true) {
                        if (aExt.sampleTrackIndex < 0) {
                            audioDone = true
                            break
                        }
                        val rawPts = aExt.sampleTime
                        if (thresholdUs != null && rawPts > thresholdUs) break

                        audioBuffer.clear()
                        val size = aExt.readSampleData(audioBuffer, 0)
                        if (size < 0) {
                            audioDone = true
                            break
                        }
                        val safePts = when {
                            lastAudioPtsUs < 0L -> rawPts.coerceAtLeast(0L)
                            rawPts <= lastAudioPtsUs -> lastAudioPtsUs + 1L
                            else -> rawPts
                        }
                        lastAudioPtsUs = safePts
                        audioInfo.set(0, size, safePts, mapExtractorFlagsToMuxer(aExt.sampleFlags))
                        audioBuffer.position(0)
                        audioBuffer.limit(size)
                        mx.writeSampleData(muxerAudioTrack, audioBuffer, audioInfo)
                        audioFramesWritten++
                        aExt.advance()
                    }
                }
            }

            while (!encoderDone && !cancelled) {
                // 1) Feed the decoder from the extractor.
                if (!inputDone) {
                    val inIndex = dec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = dec.getInputBuffer(inIndex)!!
                        val sampleSize = vExtractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            dec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(inIndex, 0, sampleSize, vExtractor.sampleTime, 0)
                            if (durationUs > 0) {
                                val videoFraction = (vExtractor.sampleTime * 100) / durationUs
                                val percent = (videoRangeStart + (videoFraction * (99 - videoRangeStart) / 100))
                                    .toInt().coerceIn(videoRangeStart, 99)
                                if (percent != lastReportedPercent) {
                                    lastReportedPercent = percent
                                    callback.onProgress(percent)
                                }
                            }
                            vExtractor.advance()
                        }
                    }
                }

                // 2) Pull decoded frames, render through GL into encoder surface.
                if (!decoderDone) {
                    if (pendingFirstOutIndex >= 0) {
                        val idx = pendingFirstOutIndex
                        pendingFirstOutIndex = -1
                        renderDecodedFrame(idx, pendingFirstOutInfo)
                    } else {
                        val outIndex = dec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                        if (outIndex >= 0) {
                            renderDecodedFrame(outIndex, bufferInfo)
                        }
                    }
                }

                // 3) Drain the encoder and write video + interleaved audio to the muxer.
                var encoderOutIndex = enc.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                while (encoderOutIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    when {
                        encoderOutIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                muxerVideoTrack = mx.addTrack(enc.outputFormat)
                                if (audioFormat != null) {
                                    try {
                                        muxerAudioTrack = mx.addTrack(audioFormat!!)
                                        audioDone = false
                                        audioDebugMsg = "Added audio track successfully: $audioFormat"
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed to add audio track directly, retrying via transcode", e)
                                        audioExtractor?.release()
                                        audioExtractor = null
                                        val fallback = if (probedAudioTrackIndex >= 0) {
                                            transcodeAudioToAac(sourceUri, probedAudioTrackIndex)
                                        } else {
                                            null
                                        }
                                        if (fallback != null && fallback.second.isNotEmpty()) {
                                            try {
                                                audioFormat = fallback.first
                                                transcodedAudioFormat = fallback.first
                                                transcodedAudio = fallback.second
                                                transcodedAudioIndex = 0
                                                muxerAudioTrack = mx.addTrack(audioFormat!!)
                                                audioDone = false
                                                audioDebugMsg = "Added transcoded fallback audio track: $audioFormat"
                                            } catch (e2: Exception) {
                                                Log.e(TAG, "Fallback audio transcode also failed", e2)
                                                muxerAudioTrack = -1
                                                audioDebugMsg = "Failed after fallback: ${e2.message}"
                                            }
                                        } else {
                                            muxerAudioTrack = -1
                                            audioDebugMsg = "Failed to add audio track and fallback empty: ${e.message}"
                                        }
                                    }
                                } else {
                                    audioDebugMsg = "Source has no usable audio track"
                                }
                                mx.start()
                                muxerStarted = true
                            }
                        }
                        encoderOutIndex >= 0 -> {
                            val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            if (!isConfig && bufferInfo.size > 0 && muxerStarted) {
                                val encodedBuffer = enc.getOutputBuffer(encoderOutIndex)!!
                                encodedBuffer.position(bufferInfo.offset)
                                encodedBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                val savedFlags = bufferInfo.flags
                                bufferInfo.flags = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()
                                mx.writeSampleData(muxerVideoTrack, encodedBuffer, bufferInfo)
                                bufferInfo.flags = savedFlags
                                videoFramesWritten++

                                writeAudioUpTo(bufferInfo.presentationTimeUs + 500_000L)
                            }
                            enc.releaseOutputBuffer(encoderOutIndex, false)
                            if (isEos) {
                                encoderDone = true
                            }
                        }
                    }
                    if (encoderDone) break
                    encoderOutIndex = enc.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                }
            }

            if (cancelled) return

            require(muxerStarted && videoFramesWritten > 0) {
                "No video frames were encoded for export"
            }

            callback.onProgress(99)

            // Drain any trailing audio samples beyond the last video timestamp.
            writeAudioUpTo(null)

            try {
                File(context.cacheDir, "audio_debug.txt").writeText(
                    "Audio Debug:\n$audioDebugMsg\nFrames Written: $audioFramesWritten\nVideo Frames: $videoFramesWritten"
                )
            } catch (ignored: Exception) {}

            mx.stop()
            mx.release()
            muxerReleased = true
        } finally {
            try { audioExtractor?.release() } catch (ignored: Exception) {}
            if (muxer != null && !muxerReleased) {
                if (muxerStarted) {
                    try { muxer.stop() } catch (ignored: Exception) {}
                }
                try { muxer.release() } catch (ignored: Exception) {}
            }
            try { decoder?.stop() } catch (ignored: Exception) {}
            try { decoder?.release() } catch (ignored: Exception) {}
            try { encoder?.stop() } catch (ignored: Exception) {}
            try { encoder?.release() } catch (ignored: Exception) {}
            if (eglSurface != null && eglCore != null) {
                try { eglCore.releaseSurface(eglSurface) } catch (ignored: Exception) {}
            }
            try { eglCore?.release() } catch (ignored: Exception) {}
            try { decoderInputSurface?.release() } catch (ignored: Exception) {}
            try { surfaceTexture?.release() } catch (ignored: Exception) {}
            try { frameThread?.quitSafely() } catch (ignored: Exception) {}
            try { videoExtractor?.release() } catch (ignored: Exception) {}
        }

        if (cancelled) return

        // Post-mux verification: if source had audio, ensure the exported MP4 actually contains a
        // valid AAC track with real samples. If passthrough suffered an OEM MediaMuxer quirk,
        // rescue by remuxing the already-encoded video with freshly transcoded AAC-LC audio.
        if (probedAudioTrackIndex >= 0 && !verifyOutputHasValidAudio(outputFile)) {
            Log.w(TAG, "Output MP4 missing valid audio track after mux; rescuing via transcoded remux")
            val rescueAudio = if (transcodedAudioFormat != null && transcodedAudio != null && transcodedAudio!!.isNotEmpty()) {
                transcodedAudioFormat!! to transcodedAudio!!
            } else {
                transcodeAudioToAac(sourceUri, probedAudioTrackIndex)
            }
            if (rescueAudio != null && rescueAudio.second.isNotEmpty() && !cancelled) {
                remuxVideoWithAudio(outputFile, rescueAudio.first, rescueAudio.second)
            }
        }

        if (!cancelled) callback.onProgress(100)
    }

    private data class ConfiguredVideoEncoder(
        val encoder: MediaCodec,
        val width: Int,
        val height: Int
    )

    private fun createAndConfigureVideoEncoder(
        requestedWidth: Int,
        requestedHeight: Int,
        requestedFps: Int,
        desiredBitrate: Long
    ): ConfiguredVideoEncoder {
        val evenWidth = (requestedWidth and 1.inv()).coerceAtLeast(2)
        val evenHeight = (requestedHeight and 1.inv()).coerceAtLeast(2)

        val probeCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val caps = try {
            probeCodec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        } catch (_: Exception) {
            null
        }
        probeCodec.release()

        val videoCaps = caps?.videoCapabilities
        val (targetW, targetH) = fitDimensionsToCapabilities(evenWidth, evenHeight, videoCaps)
        val safeFps = clampFrameRateToCapabilities(requestedFps, targetW, targetH, videoCaps)
        val safeBitrate = clampBitrateToCapabilities(desiredBitrate, targetW, targetH, videoCaps)

        // Attempt 1: High Profile + VBR for maximum visual clarity (especially Structure/Sharpen detail).
        var attempt1: MediaCodec? = null
        try {
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            attempt1 = enc
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, targetW, targetH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, safeBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, safeFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

                val highLevel = highestSupportedLevelForProfile(caps, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                if (highLevel != null) {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                    setInteger(MediaFormat.KEY_LEVEL, highLevel)
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                }
                val encCaps = caps?.encoderCapabilities
                if (encCaps?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) == true) {
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                }
            }
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            return ConfiguredVideoEncoder(enc, targetW, targetH)
        } catch (e: Exception) {
            try { attempt1?.release() } catch (_: Exception) {}
            Log.w(TAG, "High-profile/VBR encoder configure failed (${e.message}), falling back to standard AVC")
        }

        // Attempt 2: Clean universal baseline AVC configuration.
        var attempt2: MediaCodec? = null
        try {
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            attempt2 = enc
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, targetW, targetH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, safeBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, safeFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            return ConfiguredVideoEncoder(enc, targetW, targetH)
        } catch (e: Exception) {
            try { attempt2?.release() } catch (_: Exception) {}
            Log.w(TAG, "Standard AVC configure failed at ${targetW}x$targetH (${e.message}), scaling to 1080p cap")
        }

        // Attempt 3: Scale down to 1080p max long side if device encoder rejects large portrait/4K frames.
        val (fallbackW, fallbackH) = scaleDownToLongSide(targetW, targetH, 1920)
        val fallbackBitrate = clampBitrateToCapabilities(desiredBitrate, fallbackW, fallbackH, videoCaps)
        val fallbackEnc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val fallbackFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, fallbackW, fallbackH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, fallbackBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, safeFps.coerceAtMost(60))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        fallbackEnc.configure(fallbackFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        return ConfiguredVideoEncoder(fallbackEnc, fallbackW, fallbackH)
    }

    private fun fitDimensionsToCapabilities(
        width: Int,
        height: Int,
        videoCaps: MediaCodecInfo.VideoCapabilities?
    ): Pair<Int, Int> {
        if (videoCaps == null) return width to height
        if (videoCaps.isSizeSupported(width, height)) return width to height

        val maxW = videoCaps.supportedWidths.upper
        val maxH = videoCaps.supportedHeights.upper
        // Many hardware encoders validate landscape orientation bounds internally even when
        // they still accept portrait via swapped width/height; if neither fits, scale down.
        var w = width
        var h = height
        if (w > maxW || h > maxH) {
            val scale = minOf(maxW.toDouble() / w, maxH.toDouble() / h)
            w = ((w * scale).toInt() and 1.inv()).coerceAtLeast(2)
            h = ((h * scale).toInt() and 1.inv()).coerceAtLeast(2)
        }
        while (!videoCaps.isSizeSupported(w, h) && maxOf(w, h) > 640) {
            w = ((w * 3 / 4) and 1.inv()).coerceAtLeast(2)
            h = ((h * 3 / 4) and 1.inv()).coerceAtLeast(2)
        }
        return w to h
    }

    private fun scaleDownToLongSide(width: Int, height: Int, maxLongSide: Int): Pair<Int, Int> {
        val longSide = maxOf(width, height)
        if (longSide <= maxLongSide) return width to height
        val scale = maxLongSide.toDouble() / longSide
        val w = ((width * scale).toInt() and 1.inv()).coerceAtLeast(2)
        val h = ((height * scale).toInt() and 1.inv()).coerceAtLeast(2)
        return w to h
    }

    private fun clampFrameRateToCapabilities(
        fps: Int,
        width: Int,
        height: Int,
        videoCaps: MediaCodecInfo.VideoCapabilities?
    ): Int {
        var clamped = fps.coerceIn(1, 120)
        if (videoCaps != null) {
            val range = try {
                if (videoCaps.isSizeSupported(width, height)) {
                    videoCaps.getSupportedFrameRatesFor(width, height)
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
            if (range != null) {
                clamped = clamped.coerceIn(range.lower.toInt().coerceAtLeast(1), range.upper.toInt().coerceAtLeast(1))
            }
        }
        return clamped
    }

    private fun clampBitrateToCapabilities(
        desiredBitrate: Long,
        width: Int,
        height: Int,
        videoCaps: MediaCodecInfo.VideoCapabilities?
    ): Int {
        val pixels = width.toLong() * height.toLong()
        val saneMaxForDevice = if (pixels > 2_500_000L) 80_000_000L else 60_000_000L
        var target = desiredBitrate.coerceIn(12_000_000L, saneMaxForDevice)
        if (videoCaps != null) {
            val range = videoCaps.bitrateRange
            target = target.coerceIn(range.lower.toLong(), range.upper.toLong())
        }
        return target.toInt()
    }

    private fun highestSupportedLevelForProfile(
        caps: MediaCodecInfo.CodecCapabilities?,
        targetProfile: Int
    ): Int? {
        val levels = caps?.profileLevels ?: return null
        var best: Int? = null
        for (pl in levels) {
            if (pl.profile == targetProfile && (best == null || pl.level > best)) {
                best = pl.level
            }
        }
        return best
    }

    private fun verifyOutputHasValidAudio(outputFile: File): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(outputFile.absolutePath)
            val audioIdx = selectTrack(extractor, "audio/")
            if (audioIdx < 0) return false
            val fmt = extractor.getTrackFormat(audioIdx)
            val mime = fmt.getString(MediaFormat.KEY_MIME)
            val sampleRate = getIntOrNull(fmt, MediaFormat.KEY_SAMPLE_RATE) ?: 0
            val channels = getIntOrNull(fmt, MediaFormat.KEY_CHANNEL_COUNT) ?: 0
            val csd0 = try { fmt.getByteBuffer("csd-0") } catch (_: Exception) { null }
            if (mime != MediaFormat.MIMETYPE_AUDIO_AAC || sampleRate <= 0 || channels <= 0) return false
            if (csd0 == null || csd0.remaining() <= 0) return false

            extractor.selectTrack(audioIdx)
            val buf = ByteBuffer.allocate(65_536)
            val firstSize = extractor.readSampleData(buf, 0)
            firstSize > 0
        } catch (e: Exception) {
            Log.w(TAG, "Audio verification failed: ${e.message}")
            false
        } finally {
            try { extractor.release() } catch (ignored: Exception) {}
        }
    }

    private fun remuxVideoWithAudio(
        videoFile: File,
        audioFormat: MediaFormat,
        audioSamples: List<TranscodedAudioSample>
    ) {
        val tempFile = File(videoFile.parentFile, "${videoFile.name}.remux.tmp.mp4")
        val videoExt = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            videoExt.setDataSource(videoFile.absolutePath)
            val vIdx = selectTrack(videoExt, "video/")
            if (vIdx < 0) return
            videoExt.selectTrack(vIdx)
            val vFmt = videoExt.getTrackFormat(vIdx)

            val mx = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mx
            val outVideoTrack = mx.addTrack(vFmt)
            val outAudioTrack = mx.addTrack(audioFormat)
            mx.start()
            muxerStarted = true

            val maxV = (getIntOrNull(vFmt, MediaFormat.KEY_MAX_INPUT_SIZE) ?: 0).coerceAtLeast(2 shl 20)
            val vBuf = ByteBuffer.allocateDirect(maxV)
            val vInfo = MediaCodec.BufferInfo()
            val aBuf = ByteBuffer.allocateDirect(1 shl 20)
            val aInfo = MediaCodec.BufferInfo()
            var aIdx = 0
            var lastVPts = -1L
            var lastAPts = -1L

            while (!cancelled) {
                if (videoExt.sampleTrackIndex < 0) break
                vBuf.clear()
                val size = videoExt.readSampleData(vBuf, 0)
                if (size < 0) break
                val rawPts = videoExt.sampleTime
                val safeVPts = if (lastVPts >= 0L && rawPts <= lastVPts) lastVPts + 1L else rawPts.coerceAtLeast(0L)
                lastVPts = safeVPts
                vInfo.set(0, size, safeVPts, mapExtractorFlagsToMuxer(videoExt.sampleFlags))
                vBuf.position(0)
                vBuf.limit(size)
                mx.writeSampleData(outVideoTrack, vBuf, vInfo)
                videoExt.advance()

                val threshold = safeVPts + 500_000L
                while (aIdx < audioSamples.size) {
                    val sample = audioSamples[aIdx]
                    if (sample.presentationTimeUs > threshold) break
                    aBuf.clear()
                    aBuf.put(sample.data)
                    aBuf.flip()
                    val safeAPts = if (lastAPts >= 0L && sample.presentationTimeUs <= lastAPts) {
                        lastAPts + 1L
                    } else {
                        sample.presentationTimeUs.coerceAtLeast(0L)
                    }
                    lastAPts = safeAPts
                    aInfo.set(0, sample.data.size, safeAPts, sample.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                    mx.writeSampleData(outAudioTrack, aBuf, aInfo)
                    aIdx++
                }
            }

            while (aIdx < audioSamples.size && !cancelled) {
                val sample = audioSamples[aIdx]
                aBuf.clear()
                aBuf.put(sample.data)
                aBuf.flip()
                val safeAPts = if (lastAPts >= 0L && sample.presentationTimeUs <= lastAPts) {
                    lastAPts + 1L
                } else {
                    sample.presentationTimeUs.coerceAtLeast(0L)
                }
                lastAPts = safeAPts
                aInfo.set(0, sample.data.size, safeAPts, sample.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                mx.writeSampleData(outAudioTrack, aBuf, aInfo)
                aIdx++
            }

            mx.stop()
            mx.release()
            muxer = null

            if (!cancelled && tempFile.exists() && tempFile.length() > 0L) {
                if (videoFile.delete()) {
                    if (!tempFile.renameTo(videoFile)) {
                        tempFile.copyTo(videoFile, overwrite = true)
                    }
                } else {
                    tempFile.copyTo(videoFile, overwrite = true)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Remux with transcoded audio failed: ${e.message}", e)
        } finally {
            try { videoExt.release() } catch (ignored: Exception) {}
            if (muxer != null) {
                if (muxerStarted) {
                    try { muxer.stop() } catch (ignored: Exception) {}
                }
                try { muxer.release() } catch (ignored: Exception) {}
            }
            if (tempFile.exists()) {
                try { tempFile.delete() } catch (ignored: Exception) {}
            }
        }
    }

    /** One encoded AAC access unit produced by [transcodeAudioToAac]. */
    private class TranscodedAudioSample(val data: ByteArray, val presentationTimeUs: Long, val flags: Int)

    /**
     * Fully decodes the source's audio track and re-encodes it to standard AAC-LC so it can
     * always be muxed reliably regardless of the input audio codec (HE-AAC, Opus, Vorbis,
     * AMR, MP3, PCM, multi-channel, etc.).
     */
    private fun transcodeAudioToAac(
        sourceUri: Uri,
        audioTrackIndex: Int,
        onProgress: ((Int) -> Unit)? = null
    ): Pair<MediaFormat, List<TranscodedAudioSample>>? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        try {
            extractor.setDataSource(context, sourceUri, null)
            extractor.selectTrack(audioTrackIndex)
            val inFormat = extractor.getTrackFormat(audioTrackIndex)
            val mime = inFormat.getString(MediaFormat.KEY_MIME) ?: return null
            val audioDurationUs = getLongOrNull(inFormat, MediaFormat.KEY_DURATION) ?: 0L
            var lastReportedAudioPercent = -1

            val initialSampleRate = (getIntOrNull(inFormat, MediaFormat.KEY_SAMPLE_RATE) ?: 44100).coerceAtLeast(8000)
            val initialChannelCount = (getIntOrNull(inFormat, MediaFormat.KEY_CHANNEL_COUNT) ?: 2).coerceAtLeast(1)

            val dec = MediaCodec.createDecoderByType(mime)
            decoder = dec
            dec.configure(inFormat, null, null, 0)
            dec.start()

            var extractorDone = false
            var decoderDone = false
            var decoderFormatReady = false
            var realSampleRate = initialSampleRate
            var realChannelCount = initialChannelCount
            var pcmEncoding = getIntOrNull(inFormat, MediaFormat.KEY_PCM_ENCODING) ?: AudioFormat.ENCODING_PCM_16BIT

            var pendingRawPcm: ByteArray? = null
            var pendingRawPtsUs = 0L
            var pendingDecoderEos = false
            val decInfo = MediaCodec.BufferInfo()

            fun readDecodedFormat(fmt: MediaFormat) {
                getIntOrNull(fmt, MediaFormat.KEY_SAMPLE_RATE)?.takeIf { it > 0 }?.let { realSampleRate = it }
                getIntOrNull(fmt, MediaFormat.KEY_CHANNEL_COUNT)?.takeIf { it > 0 }?.let { realChannelCount = it }
                getIntOrNull(fmt, MediaFormat.KEY_PCM_ENCODING)?.let { pcmEncoding = it }
            }

            fun feedDecoderFromExtractor() {
                if (extractorDone) return
                val inIdx = dec.dequeueInputBuffer(TIMEOUT_US)
                if (inIdx < 0) return
                val buf = dec.getInputBuffer(inIdx)!!
                val size = extractor.readSampleData(buf, 0)
                if (size < 0) {
                    dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    extractorDone = true
                } else {
                    val samplePts = extractor.sampleTime
                    dec.queueInputBuffer(inIdx, 0, size, samplePts, 0)
                    if (onProgress != null && audioDurationUs > 0 && samplePts >= 0) {
                        val pct = ((samplePts * 100) / audioDurationUs).toInt().coerceIn(0, 99)
                        if (pct != lastReportedAudioPercent) {
                            lastReportedAudioPercent = pct
                            onProgress(pct)
                        }
                    }
                    extractor.advance()
                }
            }

            // Wait for decoder to report its true PCM output format before configuring encoder.
            var probeAttempts = 0
            while (!decoderFormatReady && !decoderDone && probeAttempts < 300 && !cancelled) {
                probeAttempts++
                feedDecoderFromExtractor()
                val outIdx = dec.dequeueOutputBuffer(decInfo, TIMEOUT_US)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    readDecodedFormat(dec.outputFormat)
                    decoderFormatReady = true
                } else if (outIdx >= 0) {
                    readDecodedFormat(dec.outputFormat)
                    if (decInfo.size > 0) {
                        val decoded = dec.getOutputBuffer(outIdx)!!
                        decoded.position(decInfo.offset)
                        decoded.limit(decInfo.offset + decInfo.size)
                        val arr = ByteArray(decInfo.size)
                        decoded.get(arr)
                        pendingRawPcm = arr
                        pendingRawPtsUs = decInfo.presentationTimeUs.coerceAtLeast(0L)
                    }
                    val isEos = (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    dec.releaseOutputBuffer(outIdx, false)
                    if (isEos) {
                        decoderDone = true
                        pendingDecoderEos = true
                    }
                    decoderFormatReady = true
                }
            }

            if (cancelled || (pendingDecoderEos && pendingRawPcm == null)) return null

            val targetChannelCount = realChannelCount.coerceIn(1, 2)
            val encSampleRate = selectEncoderSampleRate(realSampleRate)
            val enc = configureAacEncoder(encSampleRate, targetChannelCount)
            encoder = enc

            var pendingPcm: ByteArray? = pendingRawPcm?.let {
                normalizePcmForAacEncoder(it, realChannelCount, targetChannelCount, pcmEncoding, realSampleRate, encSampleRate)
            }
            var pendingOffset = 0
            var pendingPtsUs = pendingRawPtsUs
            var encoderEosQueued = false
            val encInfo = MediaCodec.BufferInfo()
            var encoderDone = false
            var finalFormat: MediaFormat? = null
            var codecConfigCsd0: ByteArray? = null
            val samples = mutableListOf<TranscodedAudioSample>()
            var lastEncodedPtsUs = -1L

            while (!encoderDone && !cancelled) {
                if (!extractorDone) {
                    feedDecoderFromExtractor()
                }

                if (!decoderDone && pendingPcm == null) {
                    val outIdx = dec.dequeueOutputBuffer(decInfo, TIMEOUT_US)
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        readDecodedFormat(dec.outputFormat)
                    } else if (outIdx >= 0) {
                        val isEos = (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        if (decInfo.size > 0) {
                            val decoded = dec.getOutputBuffer(outIdx)!!
                            decoded.position(decInfo.offset)
                            decoded.limit(decInfo.offset + decInfo.size)
                            val raw = ByteArray(decInfo.size)
                            decoded.get(raw)
                            val normalized = normalizePcmForAacEncoder(
                                raw,
                                realChannelCount,
                                targetChannelCount,
                                pcmEncoding,
                                realSampleRate,
                                encSampleRate
                            )
                            if (normalized.isNotEmpty()) {
                                pendingPcm = normalized
                                pendingOffset = 0
                                pendingPtsUs = decInfo.presentationTimeUs.coerceAtLeast(0L)
                            }
                        }
                        dec.releaseOutputBuffer(outIdx, false)
                        if (isEos) {
                            decoderDone = true
                            pendingDecoderEos = true
                        }
                    }
                }

                // Feed encoder from pendingPcm without discarding unconsumed bytes.
                val pcm = pendingPcm
                if (pcm != null) {
                    while (pendingOffset < pcm.size) {
                        val encInIdx = enc.dequeueInputBuffer(0)
                        if (encInIdx < 0) break
                        val encBuf = enc.getInputBuffer(encInIdx)!!
                        val bytesPerFrame = 2 * targetChannelCount
                        val rawCap = encBuf.capacity()
                        val alignedCap = (rawCap / bytesPerFrame) * bytesPerFrame
                        val chunk = minOf(pcm.size - pendingOffset, alignedCap.coerceAtLeast(bytesPerFrame))
                        encBuf.clear()
                        encBuf.put(pcm, pendingOffset, chunk)
                        enc.queueInputBuffer(encInIdx, 0, chunk, pendingPtsUs, 0)
                        val chunkFrames = chunk.toLong() / bytesPerFrame
                        pendingPtsUs += (chunkFrames * 1_000_000L) / encSampleRate
                        pendingOffset += chunk
                    }
                    if (pendingOffset >= pcm.size) {
                        pendingPcm = null
                        pendingOffset = 0
                    }
                }

                if (pendingPcm == null && pendingDecoderEos && !encoderEosQueued) {
                    val encInIdx = enc.dequeueInputBuffer(0)
                    if (encInIdx >= 0) {
                        enc.queueInputBuffer(encInIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        encoderEosQueued = true
                    }
                }

                var encOutIdx = enc.dequeueOutputBuffer(encInfo, TIMEOUT_US)
                while (encOutIdx != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    when {
                        encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            finalFormat = buildCleanAacFormat(enc.outputFormat)
                        }
                        encOutIdx >= 0 -> {
                            val isConfig = (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            val isEos = (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            if (encInfo.size > 0) {
                                val outBuf = enc.getOutputBuffer(encOutIdx)!!
                                outBuf.position(encInfo.offset)
                                outBuf.limit(encInfo.offset + encInfo.size)
                                val arr = ByteArray(encInfo.size)
                                outBuf.get(arr)
                                if (isConfig) {
                                    codecConfigCsd0 = arr
                                } else {
                                    val rawPts = encInfo.presentationTimeUs.coerceAtLeast(0L)
                                    val safePts = if (lastEncodedPtsUs >= 0L && rawPts <= lastEncodedPtsUs) {
                                        lastEncodedPtsUs + 1L
                                    } else {
                                        rawPts
                                    }
                                    lastEncodedPtsUs = safePts
                                    val cleanFlags = encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()
                                    samples.add(TranscodedAudioSample(arr, safePts, cleanFlags))
                                }
                            }
                            enc.releaseOutputBuffer(encOutIdx, false)
                            if (isEos) encoderDone = true
                        }
                    }
                    if (encoderDone) break
                    encOutIdx = enc.dequeueOutputBuffer(encInfo, TIMEOUT_US)
                }
            }

            if (cancelled || samples.isEmpty()) return null

            val fmt = finalFormat ?: MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, encSampleRate, targetChannelCount).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            if (!fmt.containsKey("csd-0") && codecConfigCsd0 != null) {
                val csdBuf = ByteBuffer.allocateDirect(codecConfigCsd0!!.size)
                csdBuf.put(codecConfigCsd0)
                csdBuf.flip()
                fmt.setByteBuffer("csd-0", csdBuf)
            }
            if (!fmt.containsKey("csd-0")) return null

            return fmt to samples
        } catch (e: Exception) {
            Log.e(TAG, "Audio transcode to AAC failed, exporting without audio", e)
            return null
        } finally {
            try { decoder?.stop() } catch (ignored: Exception) {}
            try { decoder?.release() } catch (ignored: Exception) {}
            try { encoder?.stop() } catch (ignored: Exception) {}
            try { encoder?.release() } catch (ignored: Exception) {}
            try { extractor.release() } catch (ignored: Exception) {}
        }
    }

    private fun selectEncoderSampleRate(sourceRate: Int): Int {
        val standardRates = intArrayOf(48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000)
        if (sourceRate in standardRates) return sourceRate
        return if (sourceRate > 48000) 48000 else 44100
    }

    private fun configureAacEncoder(sampleRate: Int, channelCount: Int): MediaCodec {
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val audioCaps = try {
            encoder.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_AAC).audioCapabilities
        } catch (_: Exception) {
            null
        }
        val maxSpecBitrate = (sampleRate * channelCount * 5).coerceAtLeast(32_000)
        var targetBitrate = minOf(256_000, maxSpecBitrate)
        if (audioCaps != null) {
            targetBitrate = targetBitrate.coerceIn(audioCaps.bitrateRange.lower, audioCaps.bitrateRange.upper)
        }
        val encOutputFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65_536)
        }
        encoder.configure(encOutputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        return encoder
    }

    private fun normalizePcmForAacEncoder(
        raw: ByteArray,
        srcChannels: Int,
        dstChannels: Int,
        pcmEncoding: Int,
        srcSampleRate: Int,
        dstSampleRate: Int
    ): ByteArray {
        val shortSamples: ShortArray = when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floatCount = raw.size / 4
                val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                ShortArray(floatCount) {
                    val f = bb.float.coerceIn(-1f, 1f)
                    (f * 32767f).toInt().toShort()
                }
            }
            else -> {
                val sampleCount = raw.size / 2
                val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                ShortArray(sampleCount) { bb.short }
            }
        }

        val chMixed: ShortArray = if (srcChannels == dstChannels) {
            shortSamples
        } else if (srcChannels > 2 && dstChannels == 2) {
            val frames = shortSamples.size / srcChannels
            val out = ShortArray(frames * 2)
            for (f in 0 until frames) {
                val base = f * srcChannels
                var leftSum = 0
                var rightSum = 0
                var leftCount = 0
                var rightCount = 0
                for (c in 0 until srcChannels) {
                    val v = shortSamples[base + c].toInt()
                    if (c % 2 == 0) {
                        leftSum += v
                        leftCount++
                    } else {
                        rightSum += v
                        rightCount++
                    }
                }
                out[f * 2] = (leftSum / leftCount.coerceAtLeast(1)).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                out[f * 2 + 1] = (rightSum / rightCount.coerceAtLeast(1)).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            out
        } else if (srcChannels == 1 && dstChannels == 2) {
            val out = ShortArray(shortSamples.size * 2)
            for (i in shortSamples.indices) {
                out[i * 2] = shortSamples[i]
                out[i * 2 + 1] = shortSamples[i]
            }
            out
        } else {
            val frames = shortSamples.size / srcChannels.coerceAtLeast(1)
            val out = ShortArray(frames * dstChannels)
            for (f in 0 until frames) {
                for (c in 0 until dstChannels) {
                    out[f * dstChannels + c] = shortSamples[f * srcChannels + c.coerceAtMost(srcChannels - 1)]
                }
            }
            out
        }

        val finalSamples = if (srcSampleRate != dstSampleRate && srcSampleRate > 0 && dstSampleRate > 0) {
            val srcFrames = chMixed.size / dstChannels
            if (srcFrames <= 1) {
                chMixed
            } else {
                val dstFrames = ((srcFrames.toLong() * dstSampleRate) / srcSampleRate).toInt().coerceAtLeast(1)
                val resampled = ShortArray(dstFrames * dstChannels)
                val ratio = srcFrames.toDouble() / dstFrames.toDouble()
                for (df in 0 until dstFrames) {
                    val srcPos = df * ratio
                    val i0 = srcPos.toInt().coerceIn(0, srcFrames - 1)
                    val i1 = (i0 + 1).coerceAtMost(srcFrames - 1)
                    val frac = srcPos - i0
                    for (c in 0 until dstChannels) {
                        val s0 = chMixed[i0 * dstChannels + c].toDouble()
                        val s1 = chMixed[i1 * dstChannels + c].toDouble()
                        resampled[df * dstChannels + c] = (s0 + (s1 - s0) * frac)
                            .toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    }
                }
                resampled
            }
        } else {
            chMixed
        }

        val outBytes = ByteArray(finalSamples.size * 2)
        ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(finalSamples)
        return outBytes
    }

    private fun selectTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return i
        }
        return -1
    }

    private fun selectBestAudioTrack(extractor: MediaExtractor): Int {
        var fallback = -1
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (!mime.startsWith("audio/")) continue
            if (fallback < 0) fallback = i
            val sr = getIntOrNull(fmt, MediaFormat.KEY_SAMPLE_RATE) ?: 0
            val ch = getIntOrNull(fmt, MediaFormat.KEY_CHANNEL_COUNT) ?: 0
            if (sr > 0 && ch > 0) return i
        }
        return fallback
    }

    /**
     * Strict ISO 14496-3 AudioSpecificConfig verification so HE-AAC (SBR/PS), ADTS, or
     * incomplete extractor formats are never blindly passed through to MediaMuxer.
     */
    private fun isPlainAacLcForPassthrough(format: MediaFormat): Boolean {
        if (format.getString(MediaFormat.KEY_MIME) != MediaFormat.MIMETYPE_AUDIO_AAC) return false
        if ((getIntOrNull(format, MediaFormat.KEY_IS_ADTS) ?: 0) != 0) return false
        val profile = getIntOrNull(format, MediaFormat.KEY_AAC_PROFILE)
        if (profile != null && profile != MediaCodecInfo.CodecProfileLevel.AACObjectLC) return false

        val sampleRate = getIntOrNull(format, MediaFormat.KEY_SAMPLE_RATE) ?: return false
        val channelCount = getIntOrNull(format, MediaFormat.KEY_CHANNEL_COUNT) ?: return false
        if (sampleRate <= 0 || channelCount !in 1..2) return false
        if (format.containsKey("csd-1")) return false

        val csd0 = try { format.getByteBuffer("csd-0") } catch (_: Exception) { null } ?: return false
        val dup = csd0.duplicate()
        if (dup.remaining() != 2) return false
        val b0 = dup.get().toInt() and 0xFF
        val b1 = dup.get().toInt() and 0xFF

        val audioObjectType = (b0 ushr 3) and 0x1F
        val freqIndex = ((b0 and 0x07) shl 1) or ((b1 ushr 7) and 0x01)
        val channelConfig = (b1 ushr 3) and 0x0F
        val frameLengthFlag = (b1 ushr 2) and 0x01
        val dependsOnCoreCoder = (b1 ushr 1) and 0x01
        val extensionFlag = b1 and 0x01

        return audioObjectType == 2 &&
            freqIndex in 0..12 &&
            channelConfig == channelCount &&
            frameLengthFlag == 0 &&
            dependsOnCoreCoder == 0 &&
            extensionFlag == 0
    }

    private fun buildCleanAacFormat(source: MediaFormat): MediaFormat {
        val sampleRate = (getIntOrNull(source, MediaFormat.KEY_SAMPLE_RATE) ?: 44100).coerceAtLeast(8000)
        val channelCount = (getIntOrNull(source, MediaFormat.KEY_CHANNEL_COUNT) ?: 2).coerceIn(1, 2)
        val clean = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount)
        val profile = getIntOrNull(source, MediaFormat.KEY_AAC_PROFILE) ?: MediaCodecInfo.CodecProfileLevel.AACObjectLC
        clean.setInteger(MediaFormat.KEY_AAC_PROFILE, profile)
        getIntOrNull(source, MediaFormat.KEY_BIT_RATE)?.takeIf { it > 0 }?.let {
            clean.setInteger(MediaFormat.KEY_BIT_RATE, it)
        }
        val original = try { source.getByteBuffer("csd-0") } catch (_: Exception) { null }
        if (original != null) {
            val dup = original.duplicate()
            val fresh = ByteBuffer.allocateDirect(dup.remaining())
            fresh.put(dup)
            fresh.flip()
            clean.setByteBuffer("csd-0", fresh)
        }
        return clean
    }

    private fun mapExtractorFlagsToMuxer(sampleFlags: Int): Int =
        if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0

    private fun getIntOrNull(format: MediaFormat, key: String): Int? {
        if (!format.containsKey(key)) return null
        return try {
            format.getInteger(key)
        } catch (_: Exception) {
            try { format.getFloat(key).toInt() } catch (_: Exception) { null }
        }
    }

    private fun getLongOrNull(format: MediaFormat, key: String): Long? {
        if (!format.containsKey(key)) return null
        return try {
            format.getLong(key)
        } catch (_: Exception) {
            try { format.getInteger(key).toLong() } catch (_: Exception) { null }
        }
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
