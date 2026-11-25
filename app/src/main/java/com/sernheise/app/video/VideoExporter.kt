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
        /** Called instead of [onSuccess]/[onError] when [cancel] was requested mid-export. */
        fun onCancelled() {}
    }

    private val TIMEOUT_US = 10_000L

    // Checked at the top of every codec loop below (probe, audio pre-transcode, main video
    // loop) so a cancel request from the UI takes effect within a frame or two, not just
    // between whole export runs. No encoder/muxer settings are touched by cancellation --
    // it only stops feeding more frames in and tears resources down early.
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
                java.io.File(context.cacheDir, "export_error.txt").writeText(android.util.Log.getStackTraceString(e))
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
        val videoExtractor = MediaExtractor()
        videoExtractor.setDataSource(context, sourceUri, null)
        val videoTrackIndex = selectTrack(videoExtractor, "video/")
        require(videoTrackIndex >= 0) { "No video track found" }
        videoExtractor.selectTrack(videoTrackIndex)
        val inputFormat = videoExtractor.getTrackFormat(videoTrackIndex)

        // Single source of truth for raw decode size + rotation, shared with the live
        // preview -- this is what guarantees preview and export always agree.
        val geometry = MediaUtils.readVideoGeometry(context, sourceUri)
        var width = geometry.naturalWidth
        var height = geometry.naturalHeight
        val totalRotation = ((geometry.rotationDegrees + adjustments.rotation) % 360 + 360) % 360
        var outputSize = RotationUtils.effectiveSize(width, height, totalRotation)
        var outputWidth = outputSize.first
        var outputHeight = outputSize.second

        val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION))
            inputFormat.getLong(MediaFormat.KEY_DURATION) else 0L

        val inputFrameRate = if (inputFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            inputFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
        } else {
            30
        }
        fun computeBitrate(): Int = if (inputFormat.containsKey(MediaFormat.KEY_BIT_RATE)) {
            // Sharpening and clarity create high-frequency details.
            // Multiply original bitrate by 4 to prevent the encoder from blurring these details out.
            (inputFormat.getInteger(MediaFormat.KEY_BIT_RATE) * 4).coerceAtLeast(20_000_000)
        } else {
            estimateBitrate(outputWidth, outputHeight)
        }
        var inputBitrate = computeBitrate()

        val oesTextureId = createOesTexture()
        val surfaceTexture = SurfaceTexture(oesTextureId)
        surfaceTexture.setDefaultBufferSize(width, height)

        val frameLatch = java.util.concurrent.atomic.AtomicBoolean(false)
        surfaceTexture.setOnFrameAvailableListener { frameLatch.set(true) }
        val decoderInputSurface = Surface(surfaceTexture)

        // ---- Decoder ----
        // Some vendor hardware decoders (notably a few Samsung/Exynos and MediaTek builds)
        // will auto-apply the container's own KEY_ROTATION when decoding straight into a
        // Surface, physically rotating the output buffer. Since totalRotation above was
        // already computed independently (via MediaUtils.readVideoGeometry, which is also
        // what the live preview uses), we don't need -- and don't want -- the decoder doing
        // this a second time: that double rotation is what produces a stretched / wrongly
        // oriented export on affected devices even though the preview (which goes through
        // MediaPlayer, a different code path) looks fine. Zeroing it out here keeps the
        // decoder's raw output buffer always in its natural, un-rotated layout, which is
        // the one thing every other calculation in this function assumes.
        if (inputFormat.containsKey(MediaFormat.KEY_ROTATION)) {
            inputFormat.setInteger(MediaFormat.KEY_ROTATION, 0)
        }
        val decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(inputFormat, decoderInputSurface, null, 0)
        decoder.start()

        // ---- Probe the decoder's REAL output size before locking in the encoder/canvas ----
        // The container's declared width/height (used above) is usually correct, but for
        // some files -- observed specifically with very high-bitrate exports from
        // third-party editors like Alight Motion -- the actual decoder ends up producing
        // frames at a size that doesn't match what the container claimed (device/decoder
        // profile dependent). The decoder is required by the MediaCodec API contract to
        // report its real output format via INFO_OUTPUT_FORMAT_CHANGED before it hands back
        // any actual frame, so catch that here and re-sync everything downstream (encoder
        // canvas size, bitrate estimate, SurfaceTexture buffer size, structure pipeline FBO
        // size) to the real numbers -- this is exactly what fixes the exported video (and,
        // via the equivalent MediaPlayer.OnVideoSizeChangedListener hook, the live preview)
        // matching what every other player already shows correctly.
        var quadRotation = totalRotation
        run {
            val probeBufferInfo = MediaCodec.BufferInfo()
            var formatSeen = false
            var attempts = 0
            while (!formatSeen && attempts < 30 && !cancelled) {
                attempts++
                val inIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inIdx >= 0) {
                    val buf = decoder.getInputBuffer(inIdx)!!
                    val sampleSize = videoExtractor.readSampleData(buf, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    } else {
                        decoder.queueInputBuffer(inIdx, 0, sampleSize, videoExtractor.sampleTime, 0)
                        videoExtractor.advance()
                    }
                }
                val outIdx = decoder.dequeueOutputBuffer(probeBufferInfo, TIMEOUT_US)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    formatSeen = true
                    val realFormat = decoder.outputFormat
                    val realWidth = if (realFormat.containsKey(MediaFormat.KEY_WIDTH)) realFormat.getInteger(MediaFormat.KEY_WIDTH) else width
                    val realHeight = if (realFormat.containsKey(MediaFormat.KEY_HEIGHT)) realFormat.getInteger(MediaFormat.KEY_HEIGHT) else height
                    val matchesRaw = realWidth == width && realHeight == height
                    val matchesRotated = realWidth == height && realHeight == width
                    if (realWidth > 0 && realHeight > 0 && !matchesRaw) {
                        if (matchesRotated) {
                            // The decoder baked the container's rotation directly into the
                            // buffer it delivers, instead of leaving it for the quad's
                            // texcoord permutation to apply -- applying our own rotation on
                            // top of that would rotate the frame a second time. Zeroing
                            // KEY_ROTATION on the input format above is meant to prevent
                            // this, but not every vendor decoder honors it, so still guard
                            // for it here: treat the buffer as already-oriented and skip our
                            // own rotation.
                            android.util.Log.w(
                                "VideoExporter",
                                "Decoder delivered an already-rotated ${realWidth}x$realHeight buffer -- disabling quad rotation to avoid double-rotating"
                            )
                            width = realWidth
                            height = realHeight
                            quadRotation = 0
                            outputSize = width to height
                        } else {
                            android.util.Log.w(
                                "VideoExporter",
                                "Decoder real output size ${realWidth}x$realHeight differs from " +
                                    "container-declared ${width}x$height -- re-syncing export canvas"
                            )
                            width = realWidth
                            height = realHeight
                            outputSize = RotationUtils.effectiveSize(width, height, totalRotation)
                        }
                        outputWidth = outputSize.first
                        outputHeight = outputSize.second
                        inputBitrate = computeBitrate()
                        surfaceTexture.setDefaultBufferSize(width, height)
                    }
                } else if (outIdx >= 0) {
                    // A real frame arrived without an explicit format-changed event first
                    // (some decoders do this) -- nothing to correct, stop probing so this
                    // frame flows into the normal loop below instead of being consumed here.
                    decoder.releaseOutputBuffer(outIdx, false)
                    formatSeen = true
                }
            }
        }

        if (cancelled) {
            // Nothing but the decoder side has been created yet at this point -- tear that
            // down and bail before the encoder/muxer (and their much larger buffers) ever
            // get allocated.
            try { decoder.stop() } catch (ignored: Exception) {}
            try { decoder.release() } catch (ignored: Exception) {}
            try { decoderInputSurface.release() } catch (ignored: Exception) {}
            try { surfaceTexture.release() } catch (ignored: Exception) {}
            try { videoExtractor.release() } catch (ignored: Exception) {}
            return
        }

        // ---- Encoder (sized for the *post-rotation*, now decoder-verified, orientation) ----
        val outputFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outputWidth, outputHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, inputBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, inputFrameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 second for maximum quality retention
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
        quad.setOrientation(quadRotation, adjustments.flipHorizontal, adjustments.flipVertical, sourceIsVideo = true)

        // ---- Muxer ----
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerVideoTrack = -1
        var muxerAudioTrack = -1
        var muxerStarted = false

        // Detect the audio track (if any) up front so both tracks can be registered
        // with the muxer together, before start() is called. Keep the extractor alive
        // because audioFormat contains ByteBuffer references (csd-0) tied to it.
        //
        // Android's MP4 muxer only reliably accepts AAC audio. Passing through anything
        // else (AMR, Opus, Vorbis, PCM, ...) makes MediaMuxer.addTrack() throw, which used
        // to silently drop audio from the export (or, on some devices, fail the whole
        // export with "Failed to add audio track"). To guarantee the exported video always
        // has working audio, non-AAC sources are transcoded to high-bitrate (256kbps) AAC
        // up front -- lossless passthrough is still used whenever the source is already
        // AAC, which is the common case, so no quality is lost for the vast majority of
        // videos.
        var audioExtractor: MediaExtractor? = null
        var audioFormat: MediaFormat? = null
        var transcodedAudio: List<TranscodedAudioSample>? = null
        var transcodedAudioIndex = 0

        var lastReportedPercent = -1
        // When the source audio isn't already AAC it has to be fully decoded + re-encoded
        // up front (see transcodeAudioToAac's doc comment) before the video loop below can
        // even start -- on a long clip that can take real time on its own. Reserve the
        // first slice of the bar for that phase and report real progress through it instead
        // of leaving the dialog sitting at a silent, unmoving 0% until it suddenly finishes.
        val audioPrepMaxPercent = 12
        var audioPrepRan = false

        val probe = MediaExtractor()
        probe.setDataSource(context, sourceUri, null)
        val probedAudioTrackIndex = selectTrack(probe, "audio/")
        if (probedAudioTrackIndex >= 0) {
            val probedFormat = probe.getTrackFormat(probedAudioTrackIndex)
            // Only plain AAC-LC is safe to copy through byte-for-byte. HE-AAC / HE-AACv2 (SBR,
            // optionally + PS) is also reported with KEY_MIME == MIMETYPE_AUDIO_AAC, but its
            // csd-0 encodes an SBR extension and its "real" playback sample rate (e.g. 44100)
            // differs from the core AAC stream's coded rate (e.g. 22050) -- MediaExtractor's
            // and MediaMuxer's handling of that split doesn't reliably round-trip on every
            // device. Confirmed concretely: an Instagram-downloaded clip (HE-AAC, ~65kbps --
            // typical of social-media re-encodes optimizing for size) muxed straight through
            // produced a track with a completely blank codec entry (stsd fourcc all zeros,
            // 0 Hz/0ch) on a Nothing Phone (2a) Plus, which is why standard players couldn't
            // even identify the audio track. Routing HE-AAC through the same decode+re-encode
            // path already used for non-AAC codecs sidesteps this entirely: MediaCodec decodes
            // the SBR extension correctly to full-rate PCM internally regardless of device,
            // and the re-encoded AAC-LC output has no SBR ambiguity left to go wrong.
            val aacProfile = if (probedFormat.containsKey(MediaFormat.KEY_AAC_PROFILE))
                probedFormat.getInteger(MediaFormat.KEY_AAC_PROFILE)
            else
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            val isPlainAacLc = probedFormat.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC &&
                aacProfile == MediaCodecInfo.CodecProfileLevel.AACObjectLC
            if (isPlainAacLc) {
                audioExtractor = probe
                audioExtractor.selectTrack(probedAudioTrackIndex)
                // Rebuild a clean MediaFormat instead of handing muxer.addTrack() the raw
                // probed one. addTrack() only actually runs much later -- after the *video*
                // encoder produces its first output buffer, which on this codepath means
                // rendering through the structure/clarity GL pipeline first. On some devices
                // (confirmed on a Nothing Phone (2a) Plus) the probed format's csd-0 ByteBuffer
                // doesn't survive that gap intact: the muxer silently accepts it but writes an
                // empty/zeroed codec entry (stsd fourcc "\x00\x00\x00\x00") into the container
                // instead of throwing, which is why this passed export while producing a track
                // no standard player can identify -- lenient players that don't validate the
                // codec box at open time (e.g. Next Player) still open the file but can't
                // decode the audio either, so it plays silently. Copying csd-0's actual bytes
                // into a fresh buffer *now*, while the probe extractor is still freshly opened,
                // removes that dependency on the buffer surviving the wait.
                audioFormat = buildCleanAacFormat(probedFormat)
            } else {
                probe.release()
                audioPrepRan = true
                val transcoded = transcodeAudioToAac(sourceUri, probedAudioTrackIndex) { audioPercent ->
                    val scaled = (audioPercent * audioPrepMaxPercent / 100).coerceIn(0, audioPrepMaxPercent)
                    if (scaled != lastReportedPercent) {
                        lastReportedPercent = scaled
                        callback.onProgress(scaled)
                    }
                }
                if (transcoded != null) {
                    audioFormat = transcoded.first
                    transcodedAudio = transcoded.second
                }
            }
        } else {
            probe.release()
        }

        if (cancelled) {
            audioExtractor?.release()
            try { encoder.stop() } catch (ignored: Exception) {}
            try { encoder.release() } catch (ignored: Exception) {}
            try { muxer.release() } catch (ignored: Exception) {}
            try { eglCore.releaseSurface(eglSurface) } catch (ignored: Exception) {}
            try { eglCore.release() } catch (ignored: Exception) {}
            try { decoder.stop() } catch (ignored: Exception) {}
            try { decoder.release() } catch (ignored: Exception) {}
            try { decoderInputSurface.release() } catch (ignored: Exception) {}
            try { surfaceTexture.release() } catch (ignored: Exception) {}
            try { videoExtractor.release() } catch (ignored: Exception) {}
            return
        }

        val videoRangeStart = if (audioPrepRan) audioPrepMaxPercent else 0
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var decoderDone = false
        var encoderDone = false
        val texMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        // Identity: the output frame is already sized for the rotated content, and rotation
        // itself is handled by the quad's texcoord permutation, so no vertex transform is needed.
        val mvpMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        val identityMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

        // ---- Audio Muxing State ----
        var audioFramesWritten = 0
        var audioDebugMsg = ""
        val audioBuffer = java.nio.ByteBuffer.allocateDirect(1 shl 20)
        val audioInfo = MediaCodec.BufferInfo()
        var audioDone = true

        while (!encoderDone && !cancelled) {
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
                            // Scaled into the slice of the bar left over after audio prep
                            // (0..99 as before when there was no audio prep phase at all).
                            val videoFraction = (videoExtractor.sampleTime * 100) / durationUs
                            val percent = (videoRangeStart + (videoFraction * (99 - videoRangeStart) / 100))
                                .toInt().coerceIn(videoRangeStart, 99)
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
                    if (doRender) {
                        frameLatch.set(false) // Clear BEFORE releasing buffer to avoid race conditions
                    }
                    decoder.releaseOutputBuffer(outIndex, doRender)
                    if (doRender) {
                        var waited = 0
                        while (!frameLatch.get() && waited < 500) {
                            Thread.sleep(5); waited++
                        }
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
                        GLES20.glFinish()
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
                        if (!muxerStarted) {
                            muxerVideoTrack = muxer.addTrack(encoder.outputFormat)
                            if (audioFormat != null) {
                                try {
                                    muxerAudioTrack = muxer.addTrack(audioFormat!!)
                                    audioDone = false
                                    audioDebugMsg = "Added audio track successfully: $audioFormat"
                                } catch (e: Exception) {
                                    // Even a plain AAC format can occasionally be rejected on a
                                    // specific device (malformed csd, odd channel config, etc).
                                    // Last resort: transcode it fresh rather than shipping a
                                    // silent video.
                                    android.util.Log.e("VideoExporter", "Failed to add audio track directly, retrying via transcode", e)
                                    audioExtractor?.release(); audioExtractor = null
                                    val fallbackTrackIndex = run {
                                        val p = MediaExtractor()
                                        p.setDataSource(context, sourceUri, null)
                                        val idx = selectTrack(p, "audio/")
                                        p.release()
                                        idx
                                    }
                                    val fallback = if (fallbackTrackIndex >= 0) transcodeAudioToAac(sourceUri, fallbackTrackIndex) else null
                                    if (fallback != null) {
                                        try {
                                            audioFormat = fallback.first
                                            transcodedAudio = fallback.second
                                            transcodedAudioIndex = 0
                                            muxerAudioTrack = muxer.addTrack(audioFormat!!)
                                            audioDone = false
                                            audioDebugMsg = "Added transcoded fallback audio track: $audioFormat"
                                        } catch (e2: Exception) {
                                            android.util.Log.e("VideoExporter", "Fallback audio transcode also failed, exporting without audio", e2)
                                            muxerAudioTrack = -1
                                            audioDebugMsg = "Failed to add audio track even after transcode fallback: ${e2.message}"
                                        }
                                    } else {
                                        muxerAudioTrack = -1
                                        audioDebugMsg = "Failed to add audio track and transcode fallback produced nothing: ${e.message}"
                                    }
                                }
                            } else {
                                audioDebugMsg = "Source has no usable audio track"
                            }
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    encoderOutIndex >= 0 -> {
                        val encodedBuffer = encoder.getOutputBuffer(encoderOutIndex)!!
                        if (bufferInfo.size > 0 && muxerStarted) {
                            muxer.writeSampleData(muxerVideoTrack, encodedBuffer, bufferInfo)

                            // Interleave audio: catch up to the current video timestamp (0.5s buffer).
                            if (muxerAudioTrack >= 0 && !audioDone) {
                                val thresholdUs = bufferInfo.presentationTimeUs + 500_000
                                val transcoded = transcodedAudio
                                if (transcoded != null) {
                                    while (transcodedAudioIndex < transcoded.size) {
                                        val sample = transcoded[transcodedAudioIndex]
                                        if (sample.presentationTimeUs > thresholdUs) break
                                        audioBuffer.clear()
                                        audioBuffer.put(sample.data)
                                        audioBuffer.position(0)
                                        audioBuffer.limit(sample.data.size)
                                        audioInfo.presentationTimeUs = sample.presentationTimeUs
                                        audioInfo.flags = sample.flags
                                        audioInfo.offset = 0
                                        audioInfo.size = sample.data.size
                                        muxer.writeSampleData(muxerAudioTrack, audioBuffer, audioInfo)
                                        audioFramesWritten++
                                        transcodedAudioIndex++
                                    }
                                    if (transcodedAudioIndex >= transcoded.size) audioDone = true
                                } else if (audioExtractor != null) {
                                    while (true) {
                                        val audioPts = audioExtractor!!.sampleTime
                                        if (audioPts < 0) {
                                            audioDone = true
                                            break
                                        }
                                        if (audioPts > thresholdUs) break

                                        audioBuffer.clear()
                                        val size = audioExtractor!!.readSampleData(audioBuffer, 0)
                                        if (size >= 0) {
                                            audioInfo.presentationTimeUs = audioPts
                                            audioInfo.flags = audioExtractor!!.sampleFlags
                                            audioInfo.offset = 0
                                            audioInfo.size = size

                                            audioBuffer.position(audioInfo.offset)
                                            audioBuffer.limit(audioInfo.offset + audioInfo.size)

                                            muxer.writeSampleData(muxerAudioTrack, audioBuffer, audioInfo)
                                            audioFramesWritten++
                                            audioExtractor!!.advance()
                                        }
                                    }
                                }
                            }
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

        if (!cancelled) callback.onProgress(99) // audio copy + muxer finalize still to come

        // ---- Drain remaining audio ---- (skipped on cancel: output is being discarded anyway)
        if (!cancelled && muxerAudioTrack >= 0 && !audioDone) {
            val transcoded = transcodedAudio
            if (transcoded != null) {
                while (transcodedAudioIndex < transcoded.size) {
                    val sample = transcoded[transcodedAudioIndex]
                    audioBuffer.clear()
                    audioBuffer.put(sample.data)
                    audioBuffer.position(0)
                    audioBuffer.limit(sample.data.size)
                    audioInfo.presentationTimeUs = sample.presentationTimeUs
                    audioInfo.flags = sample.flags
                    audioInfo.offset = 0
                    audioInfo.size = sample.data.size
                    muxer.writeSampleData(muxerAudioTrack, audioBuffer, audioInfo)
                    audioFramesWritten++
                    transcodedAudioIndex++
                }
            } else if (audioExtractor != null) {
                while (true) {
                    audioBuffer.clear()
                    val size = audioExtractor!!.readSampleData(audioBuffer, 0)
                    if (size < 0) break
                    audioInfo.presentationTimeUs = audioExtractor!!.sampleTime
                    audioInfo.flags = audioExtractor!!.sampleFlags
                    audioInfo.offset = 0
                    audioInfo.size = size

                    audioBuffer.position(audioInfo.offset)
                    audioBuffer.limit(audioInfo.offset + audioInfo.size)

                    muxer.writeSampleData(muxerAudioTrack, audioBuffer, audioInfo)
                    audioFramesWritten++
                    audioExtractor!!.advance()
                }
            }
        }
        audioExtractor?.release()
        
        try {
            java.io.File(context.cacheDir, "audio_debug.txt").writeText("Audio Debug:\n$audioDebugMsg\nFrames Written: $audioFramesWritten")
        } catch (ignored: Exception) {}

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

        if (!cancelled) callback.onProgress(100)
    }

    private fun probeAudioTrackIndexOrThrow(extractor: MediaExtractor): Int {
        val index = selectTrack(extractor, "audio/")
        require(index >= 0) { "Audio track disappeared unexpectedly" }
        return index
    }

    /** One encoded AAC access unit produced by [transcodeAudioToAac]. */
    private class TranscodedAudioSample(val data: ByteArray, val presentationTimeUs: Long, val flags: Int)

    /**
     * Fully decodes the source's audio track and re-encodes it to AAC (256kbps, source
     * sample rate/channel count) so it can always be added to the MP4 muxer, regardless of
     * what codec the source video originally used (AMR, Opus, Vorbis, PCM, etc. all fail
     * MediaMuxer.addTrack() directly). Runs as a self-contained pass entirely before the
     * video loop starts, so it never has to interleave two live codecs at once -- the
     * resulting samples are just handed to the normal PTS-ordered muxer-write loop exactly
     * like a passthrough track would be.
     *
     * Returns null (never throws) if the source has no decodable/re-encodable audio --
     * callers treat that the same as "no audio track" rather than failing the whole export.
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
            val audioDurationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION))
                inFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            var lastReportedAudioPercent = -1

            val sampleRate = if (inFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            val channelCount = if (inFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inFormat, null, null, 0)
            decoder.start()

            var extractorDone = false

            // ---- Probe the decoder's REAL output format before configuring the encoder ----
            // MediaCodec always emits INFO_OUTPUT_FORMAT_CHANGED before any real output buffer
            // for an audio decoder. This matters specifically for HE-AAC / HE-AACv2 sources:
            // `inFormat`'s declared KEY_SAMPLE_RATE (used for `sampleRate` above) is the AAC
            // core-coded rate (e.g. 22050), but the decoder applies SBR internally and hands
            // back real PCM at the doubled playback rate (e.g. 44100). Configuring the encoder
            // at the container's rate while it's actually fed 44100Hz PCM silently doubles the
            // encoded track's duration and slows/detunes the audio -- confirmed concretely on
            // an HE-AAC Instagram clip. Reading the rate/channel count off the decoder's own
            // output format, once actually known, removes that ambiguity entirely. Plain AAC-LC
            // and other codecs just report the same numbers here as `inFormat` already had.
            var realSampleRate = sampleRate
            var realChannelCount = channelCount
            run {
                val probeInfo = MediaCodec.BufferInfo()
                var formatKnown = false
                var attempts = 0
                while (!formatKnown && attempts < 30 && !cancelled) {
                    attempts++
                    if (!extractorDone) {
                        val inIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                        if (inIdx >= 0) {
                            val buf = decoder.getInputBuffer(inIdx)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                extractorDone = true
                            } else {
                                decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIdx = decoder.dequeueOutputBuffer(probeInfo, TIMEOUT_US)
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        formatKnown = true
                        val realFormat = decoder.outputFormat
                        if (realFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            realSampleRate = realFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (realFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            realChannelCount = realFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    } else if (outIdx >= 0) {
                        // Real decoded data arrived without an explicit format-changed event
                        // first -- stop probing; the main loop below will just dequeue the
                        // next buffer normally rather than this one being force-fit in here.
                        decoder.releaseOutputBuffer(outIdx, false)
                        formatKnown = true
                    }
                }
            }

            val encOutputFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, realSampleRate, realChannelCount).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 256_000)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(encOutputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            var decoderDone = false
            var encoderDone = false
            var finalFormat: MediaFormat? = null
            val samples = mutableListOf<TranscodedAudioSample>()

            while (!encoderDone && !cancelled) {
                if (!extractorDone) {
                    val inIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            extractorDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            if (onProgress != null && audioDurationUs > 0) {
                                val pct = ((extractor.sampleTime * 100) / audioDurationUs).toInt().coerceIn(0, 99)
                                if (pct != lastReportedAudioPercent) {
                                    lastReportedAudioPercent = pct
                                    onProgress(pct)
                                }
                            }
                            extractor.advance()
                        }
                    }
                }

                if (!decoderDone) {
                    val outIdx = decoder.dequeueOutputBuffer(decInfo, TIMEOUT_US)
                    if (outIdx >= 0) {
                        val isEos = (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        if (decInfo.size > 0) {
                            val decoded = decoder.getOutputBuffer(outIdx)!!
                            decoded.position(decInfo.offset)
                            decoded.limit(decInfo.offset + decInfo.size)
                            var remaining = decInfo.size
                            // Encoder input buffer might be smaller than the decoded chunk on
                            // some devices -- loop until every decoded byte has been queued.
                            while (remaining > 0) {
                                val encInIdx = encoder.dequeueInputBuffer(TIMEOUT_US)
                                if (encInIdx < 0) break
                                val encBuf = encoder.getInputBuffer(encInIdx)!!
                                val chunk = minOf(remaining, encBuf.capacity())
                                val oldLimit = decoded.limit()
                                decoded.limit(decoded.position() + chunk)
                                encBuf.clear()
                                encBuf.put(decoded)
                                decoded.limit(oldLimit)
                                encoder.queueInputBuffer(encInIdx, 0, chunk, decInfo.presentationTimeUs, 0)
                                remaining -= chunk
                            }
                        }
                        decoder.releaseOutputBuffer(outIdx, false)
                        if (isEos) {
                            decoderDone = true
                            val encInIdx = encoder.dequeueInputBuffer(TIMEOUT_US)
                            if (encInIdx >= 0) {
                                encoder.queueInputBuffer(encInIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            }
                        }
                    }
                }

                var encOutIdx = encoder.dequeueOutputBuffer(encInfo, TIMEOUT_US)
                while (encOutIdx != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    when {
                        encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            finalFormat = encoder.outputFormat
                        }
                        encOutIdx >= 0 -> {
                            if (encInfo.size > 0 && (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                val outBuf = encoder.getOutputBuffer(encOutIdx)!!
                                outBuf.position(encInfo.offset)
                                outBuf.limit(encInfo.offset + encInfo.size)
                                val arr = ByteArray(encInfo.size)
                                outBuf.get(arr)
                                samples.add(TranscodedAudioSample(arr, encInfo.presentationTimeUs, encInfo.flags))
                            }
                            val eos = (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            encoder.releaseOutputBuffer(encOutIdx, false)
                            if (eos) encoderDone = true
                        }
                    }
                    if (encoderDone) break
                    encOutIdx = encoder.dequeueOutputBuffer(encInfo, TIMEOUT_US)
                }
            }

            val fmt = finalFormat ?: return null
            return fmt to samples
        } catch (e: Exception) {
            android.util.Log.e("VideoExporter", "Audio transcode to AAC failed, exporting without audio", e)
            return null
        } finally {
            try { decoder?.stop() } catch (ignored: Exception) {}
            try { decoder?.release() } catch (ignored: Exception) {}
            try { encoder?.stop() } catch (ignored: Exception) {}
            try { encoder?.release() } catch (ignored: Exception) {}
            extractor.release()
        }
    }

    private fun selectTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return i
        }
        return -1
    }

    /**
     * Rebuilds [source] into a fresh [MediaFormat] with its own copy of csd-0/csd-1, instead
     * of reusing the extractor-provided one as-is. See the call site's comment for why: on at
     * least one real device the original format's csd ByteBuffer didn't reliably survive the
     * gap between probing it and [MediaMuxer.addTrack] actually consuming it.
     */
    private fun buildCleanAacFormat(source: MediaFormat): MediaFormat {
        val sampleRate = if (source.containsKey(MediaFormat.KEY_SAMPLE_RATE))
            source.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
        val channelCount = if (source.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            source.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
        val clean = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount)
        if (source.containsKey(MediaFormat.KEY_AAC_PROFILE)) {
            clean.setInteger(MediaFormat.KEY_AAC_PROFILE, source.getInteger(MediaFormat.KEY_AAC_PROFILE))
        }
        if (source.containsKey(MediaFormat.KEY_BIT_RATE)) {
            clean.setInteger(MediaFormat.KEY_BIT_RATE, source.getInteger(MediaFormat.KEY_BIT_RATE))
        }
        // csd-0 (and csd-1, present for some AAC variants) is the AudioSpecificConfig the
        // muxer needs to write a valid stsd/esds entry -- copy its actual bytes into a new
        // buffer now rather than carrying a reference to the source format's buffer forward.
        for (key in listOf("csd-0", "csd-1")) {
            val original = try { source.getByteBuffer(key) } catch (e: Exception) { null } ?: continue
            val dup = original.duplicate()
            val fresh = java.nio.ByteBuffer.allocateDirect(dup.remaining())
            fresh.put(dup)
            fresh.flip()
            clean.setByteBuffer(key, fresh)
        }
        return clean
    }

    private fun estimateBitrate(width: Int, height: Int): Int {
        val pixels = width * height
        // Professional maximum quality: 30x pixels (e.g. ~62 Mbps for 1080p)
        return (pixels * 30).coerceAtLeast(20_000_000)
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
