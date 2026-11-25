package com.sernheise.app

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sernheise.app.databinding.ActivityEditorBinding
import com.sernheise.app.gl.Adjustments
import com.sernheise.app.gl.RotationUtils
import com.sernheise.app.gl.Tool
import com.sernheise.app.gl.getToolValue
import com.sernheise.app.gl.setToolValue
import com.sernheise.app.ui.ExportProgressDialog
import com.sernheise.app.ui.ToolAdapter
import com.sernheise.app.ui.ToolCatalog
import com.sernheise.app.ui.ToolItem
import com.sernheise.app.utils.MediaUtils
import com.sernheise.app.video.VideoExporter
import java.io.File

private const val TAG = "EditorActivity"

class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var sourceUri: Uri
    private var mediaType: MediaUtils.MediaType = MediaUtils.MediaType.UNKNOWN
    private var mediaPlayer: MediaPlayer? = null

    private var currentAdjustments = Adjustments()
    private var selectedTool: Tool? = null
    private var userPaused = false

    private lateinit var toolAdapter: ToolAdapter
    private var exportDialog: ExportProgressDialog? = null
    private var activeVideoExporter: VideoExporter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uri = intent.data
        if (uri == null) {
            finish()
            return
        }
        sourceUri = uri
        mediaType = MediaUtils.typeOf(this, uri)

        setupToolStrip()
        setupTopBar()
        setupSlider()
        setupPreviewTapToPause()
        loadMedia()
    }

    // ---------------------------------------------------------------------
    // Media loading
    // ---------------------------------------------------------------------

    private fun loadMedia() {
        when (mediaType) {
            MediaUtils.MediaType.IMAGE -> loadImage()
            MediaUtils.MediaType.VIDEO -> setupVideoSurfaceListener()
            MediaUtils.MediaType.UNKNOWN -> {
                Toast.makeText(this, R.string.unsupported_file, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun loadImage() {
        setLoading(true)
        Thread {
            try {
                val bitmap = MediaUtils.loadPreviewBitmap(this, sourceUri)
                mainHandler.post {
                    binding.preview.showBitmap(bitmap)
                    setLoading(false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode image", e)
                mainHandler.post {
                    setLoading(false)
                    Toast.makeText(this, R.string.load_failed, Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }.start()
    }

    private fun setupVideoSurfaceListener() {
        setLoading(true)
        binding.preview.videoSurfaceReadyListener = { surfaceTexture ->
            startVideoPlayback(surfaceTexture)
        }
    }

    private fun startVideoPlayback(surfaceTexture: SurfaceTexture) {
        try {
            // Single source of truth for raw size + rotation -- the exact same call
            // VideoExporter makes, which is what keeps preview and export in agreement.
            val geometry = MediaUtils.readVideoGeometry(this, sourceUri)
            binding.preview.prepareForVideo(geometry.naturalWidth, geometry.naturalHeight)
            binding.preview.setSourceRotation(geometry.rotationDegrees)
            surfaceTexture.setDefaultBufferSize(geometry.naturalWidth, geometry.naturalHeight)

            val player = MediaPlayer()
            mediaPlayer = player
            player.setDataSource(this, sourceUri)
            player.setSurface(Surface(surfaceTexture))
            player.isLooping = true
            // MediaPlayer's real decode size, once it's known, is authoritative -- but *how*
            // to react to it depends on WHICH size it reports:
            //  - Matches the container's raw (pre-rotation) size: our own GL-side rotation
            //    (via Quad) is still needed exactly as before, nothing to change.
            //  - Matches the ROTATED (post-rotation) size: on some devices, for some files,
            //    MediaPlayer's own decode pipeline bakes the container's rotation directly
            //    into the delivered buffer instead of leaving it for us to apply. If we kept
            //    applying our own 90/270 rotation on top of an already-rotated buffer, the
            //    result is rotated a *second* time -- which is exactly what shows up as a
            //    sideways, wrongly-sized preview. In that case, disable our own rotation and
            //    just treat the reported size as the natural content size directly.
            //  - Matches neither: a genuine size mismatch unrelated to rotation (e.g. a
            //    decoder-level resolution clamp) -- resync to it, keeping whatever rotation
            //    we already computed.
            player.setOnVideoSizeChangedListener { _, mpWidth, mpHeight ->
                if (mpWidth <= 0 || mpHeight <= 0) return@setOnVideoSizeChangedListener
                val rawAspect = geometry.naturalWidth.toFloat() / geometry.naturalHeight
                val rotatedAspect = geometry.naturalHeight.toFloat() / geometry.naturalWidth
                val mpAspect = mpWidth.toFloat() / mpHeight
                // Compare aspect ratios (with a small tolerance) rather than exact pixel
                // dimensions -- some decoders report a buffer a few pixels off from the
                // container's declared size (macroblock/alignment rounding), which would
                // make an exact-equality check miss the match entirely and fall through to
                // the wrong branch below.
                fun closeAspect(a: Float, b: Float) = kotlin.math.abs(a - b) < 0.02f
                when {
                    closeAspect(mpAspect, rawAspect) -> {
                        // Exactly what we assumed -- nothing to do.
                    }
                    closeAspect(mpAspect, rotatedAspect) -> {
                        Log.w(
                            TAG,
                            "MediaPlayer delivered an already-rotated ${mpWidth}x$mpHeight buffer " +
                                "(container rotation=${geometry.rotationDegrees}) -- disabling our own GL rotation to avoid double-rotating"
                        )
                        surfaceTexture.setDefaultBufferSize(mpWidth, mpHeight)
                        binding.preview.prepareForVideo(mpWidth, mpHeight)
                        binding.preview.setSourceRotation(0)
                    }
                    else -> {
                        Log.w(
                            TAG,
                            "MediaPlayer real decode size ${mpWidth}x$mpHeight differs from " +
                                "container-declared ${geometry.naturalWidth}x${geometry.naturalHeight} -- re-syncing"
                        )
                        surfaceTexture.setDefaultBufferSize(mpWidth, mpHeight)
                        binding.preview.prepareForVideo(mpWidth, mpHeight)
                    }
                }
            }
            player.setOnPreparedListener {
                it.start()
                mainHandler.post { setLoading(false) }
            }
            player.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
                mainHandler.post {
                    setLoading(false)
                    Toast.makeText(this, R.string.load_failed, Toast.LENGTH_LONG).show()
                }
                true
            }
            player.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start video playback", e)
            setLoading(false)
            Toast.makeText(this, R.string.load_failed, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    /** Tap the preview to pause; tap again to resume from the same frame. Images ignore this. */
    private fun setupPreviewTapToPause() {
        binding.preview.setOnClickListener {
            val player = mediaPlayer ?: return@setOnClickListener
            if (player.isPlaying) {
                player.pause()
                userPaused = true
                // No new frames are coming in while paused -- stop burning GPU/battery
                // redrawing the identical last frame at the display's full refresh rate.
                binding.preview.setContinuousRendering(false)
            } else {
                player.start()
                userPaused = false
                binding.preview.setContinuousRendering(true)
                // Switching WHEN_DIRTY -> CONTINUOUSLY wakes the GL thread on its own next
                // loop iteration, but that iteration can lag behind MediaPlayer.start() by
                // a frame or two on some devices/OEM builds, so playback silently resumes
                // (position/audio move) while the view still shows the frozen paused frame
                // until something else happens to trigger a draw. Force one immediately so
                // the resume is visible on the very next vsync instead of waiting on that.
                binding.preview.requestRender()
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.exportProgress.visibility = if (loading) android.view.View.VISIBLE else android.view.View.GONE
    }

    // ---------------------------------------------------------------------
    // Tool strip / slider
    // ---------------------------------------------------------------------

    private fun setupToolStrip() {
        binding.toolStrip.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        binding.toolStrip.setHasFixedSize(true)
        showAdjustTools()

        binding.tabAdjust.setOnClickListener { showAdjustTools() }
        binding.tabDetails.setOnClickListener { showDetailTools() }
    }

    private fun showAdjustTools() {
        binding.tabAdjust.setTextColor(getColor(R.color.sern_text_primary))
        binding.tabDetails.setTextColor(getColor(R.color.sern_text_secondary))
        bindToolAdapter(ToolCatalog.adjustTools)
    }

    private fun showDetailTools() {
        binding.tabDetails.setTextColor(getColor(R.color.sern_text_primary))
        binding.tabAdjust.setTextColor(getColor(R.color.sern_text_secondary))
        bindToolAdapter(ToolCatalog.detailTools)
    }

    private fun bindToolAdapter(items: List<ToolItem>) {
        toolAdapter = ToolAdapter(items, { currentAdjustments }) { tool ->
            when (tool) {
                Tool.ROTATE -> rotateOnce()
                Tool.FLIP_HORIZONTAL -> toggleFlip(horizontal = true)
                Tool.FLIP_VERTICAL -> toggleFlip(horizontal = false)
                else -> selectTool(tool)
            }
        }
        binding.toolStrip.adapter = toolAdapter
    }

    private fun selectTool(tool: Tool) {
        selectedTool = tool
        val label = getString(labelFor(tool))
        val value = currentAdjustments.getToolValue(tool)

        binding.activeToolLabel.visibility = android.view.View.VISIBLE
        binding.toolSlider.visibility = android.view.View.VISIBLE

        // Reset to a safe value before changing bounds to prevent IllegalArgumentException
        binding.toolSlider.value = 0f

        if (tool.isBipolar) {
            binding.toolSlider.valueFrom = Adjustments.MIN
            binding.toolSlider.valueTo = Adjustments.MAX
        } else {
            binding.toolSlider.valueFrom = Adjustments.DETAIL_MIN
            binding.toolSlider.valueTo = Adjustments.DETAIL_MAX
        }
        binding.toolSlider.value = value.coerceIn(binding.toolSlider.valueFrom, binding.toolSlider.valueTo)
        updateActiveToolLabel(label, value)
    }

    /** Rotate is a one-tap action (0 -> 90 -> 180 -> 270 -> 0), not a slider tool. */
    private fun rotateOnce() {
        currentAdjustments = currentAdjustments.rotatedClockwise()
        binding.preview.updateAdjustments(currentAdjustments)
        toolAdapter.refreshActiveStates()
    }

    /** Flip Horizontal/Vertical are simple on/off toggles, not slider tools. */
    private fun toggleFlip(horizontal: Boolean) {
        val updated = currentAdjustments.copy()
        if (horizontal) updated.flipHorizontal = !updated.flipHorizontal else updated.flipVertical = !updated.flipVertical
        currentAdjustments = updated
        binding.preview.updateAdjustments(currentAdjustments)
        toolAdapter.refreshActiveStates()
    }

    private fun labelFor(tool: Tool): Int = (ToolCatalog.adjustTools + ToolCatalog.detailTools)
        .first { it.tool == tool }.labelRes

    private fun updateActiveToolLabel(label: String, value: Float) {
        val rounded = Math.round(value)
        val sign = if (rounded > 0) "+" else ""
        binding.activeToolLabel.text = "$label   $sign$rounded"
    }

    private fun setupSlider() {
        binding.toolSlider.addOnChangeListener { _, value, fromUser ->
            val tool = selectedTool ?: return@addOnChangeListener
            if (!fromUser) return@addOnChangeListener

            val updated = currentAdjustments.copy()
            updated.setToolValue(tool, value)
            currentAdjustments = updated
            binding.preview.updateAdjustments(currentAdjustments)
            updateActiveToolLabel(getString(labelFor(tool)), value)
        }
    }

    // ---------------------------------------------------------------------
    // Top bar: back / reset / compare / export
    // ---------------------------------------------------------------------

    private fun setupTopBar() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnReset.setOnClickListener {
            currentAdjustments = Adjustments()
            binding.preview.updateAdjustments(currentAdjustments)
            selectedTool?.let { selectTool(it) }
            toolAdapter.refreshActiveStates()
        }

        binding.btnCompare.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    // Compare shows the "before" state, but geometry (rotate/flip) isn't a
                    // color edit -- keep it applied so the comparison stays right-side up.
                    binding.preview.updateAdjustments(
                        Adjustments(
                            rotation = currentAdjustments.rotation,
                            flipHorizontal = currentAdjustments.flipHorizontal,
                            flipVertical = currentAdjustments.flipVertical
                        )
                    )
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    binding.preview.updateAdjustments(currentAdjustments)
                }
            }
            true
        }

        binding.btnExport.setOnClickListener { export() }
    }

    // ---------------------------------------------------------------------
    // Export
    // ---------------------------------------------------------------------

    private fun export() {
        if (isFinishing || isDestroyed) return

        val dialog: ExportProgressDialog
        try {
            dialog = ExportProgressDialog(this)
            dialog.show()
        } catch (t: Throwable) {
            // The dialog itself failing to construct/show must never crash the editor --
            // fall back to the plainest possible AlertDialog, which can't fail the same way.
            Log.e(TAG, "Failed to show export progress dialog", t)
            showPlainErrorDialog()
            return
        }

        exportDialog = dialog
        setExportControlsEnabled(false)

        try {
            when (mediaType) {
                MediaUtils.MediaType.IMAGE -> exportImage(dialog)
                MediaUtils.MediaType.VIDEO -> exportVideo(dialog)
                else -> finishExport(dialog, success = false)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Export failed to start", t)
            finishExport(dialog, success = false)
        }
    }

    /** Last-resort error UI if even [ExportProgressDialog] itself can't be shown. */
    private fun showPlainErrorDialog() {
        setExportControlsEnabled(true)
        try {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.export_failed_title)
                .setMessage(R.string.export_failed_message)
                .setPositiveButton(R.string.dialog_ok) { d, _ -> d.dismiss() }
                .setCancelable(true)
                .show()
        } catch (t: Throwable) {
            // Truly last resort -- never let this take the app down.
            Log.e(TAG, "Even the fallback error dialog failed", t)
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun exportImage(dialog: ExportProgressDialog) {
        mainHandler.post { dialog.updateProgress(10, R.string.export_status_preparing) }
        Thread {
            try {
                val fullBitmap = MediaUtils.loadOrientedBitmap(this, sourceUri)
                val (exportWidth, exportHeight) = RotationUtils.effectiveSize(
                    fullBitmap.width, fullBitmap.height, currentAdjustments.rotation
                )
                mainHandler.post {
                    try {
                        dialog.updateProgress(45, R.string.export_status_processing)
                        binding.preview.exportImage(exportWidth, exportHeight, currentAdjustments) { edited ->
                            Thread {
                                try {
                                    mainHandler.post { dialog.updateProgress(85, R.string.export_status_finishing) }
                                    val name = MediaUtils.timestampedFileName("Sernheise", "jpg")
                                    val saved = MediaUtils.saveImageToGallery(this, edited, name)
                                    mainHandler.post {
                                        finishExport(dialog, success = saved != null)
                                    }
                                } catch (t: Throwable) {
                                    Log.e(TAG, "Image export failed while saving", t)
                                    mainHandler.post { finishExport(dialog, success = false) }
                                }
                            }.start()
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "Image export failed while dispatching GL render", t)
                        finishExport(dialog, success = false)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Image export failed while rendering", t)
                mainHandler.post { finishExport(dialog, success = false) }
            }
        }.start()
    }

    private fun exportVideo(dialog: ExportProgressDialog) {
        mainHandler.post { dialog.updateProgress(0, R.string.export_status_preparing) }
        val exporter = VideoExporter(this)
        activeVideoExporter = exporter
        dialog.onCancelRequested = {
            // Just flips a flag the exporter checks between codec steps -- the background
            // thread below unwinds itself and reports onCancelled(); we don't touch it here.
            exporter.cancel()
            dialog.showCancelling()
        }
        Thread {
            try {
                val outputFile = File(cacheDir, MediaUtils.timestampedFileName("sernheise_export", "mp4"))
                exporter.export(sourceUri, currentAdjustments, outputFile, object : VideoExporter.Callback {
                    override fun onProgress(percent: Int) {
                        mainHandler.post {
                            val statusRes = if (percent >= 100) R.string.export_status_finishing else R.string.export_status_processing
                            dialog.updateProgress(percent, statusRes)
                        }
                    }

                    override fun onSuccess(outputFile: File) {
                        try {
                            val name = MediaUtils.timestampedFileName("Sernheise", "mp4")
                            val saved = MediaUtils.publishVideoToGallery(this@EditorActivity, outputFile, name)
                            outputFile.delete()
                            mainHandler.post { finishExport(dialog, success = saved != null) }
                        } catch (t: Throwable) {
                            Log.e(TAG, "Video export succeeded but publishing to gallery failed", t)
                            mainHandler.post { finishExport(dialog, success = false) }
                        }
                    }

                    override fun onError(error: Exception) {
                        Log.e(TAG, "Video export failed", error)
                        try {
                            java.io.File(cacheDir, "export_error.txt").writeText(android.util.Log.getStackTraceString(error))
                        } catch (ignored: Exception) {}
                        mainHandler.post { 
                            Toast.makeText(this@EditorActivity, "Error saved to cacheDir/export_error.txt: ${error.message}", Toast.LENGTH_LONG).show()
                            finishExport(dialog, success = false) 
                        }
                    }

                    override fun onCancelled() {
                        mainHandler.post {
                            activeVideoExporter = null
                            setExportControlsEnabled(true)
                            exportDialog = null
                            dialog.dismissImmediately()
                        }
                    }
                })
            } catch (t: Throwable) {
                // Covers anything VideoExporter itself didn't catch (e.g. an Error, not
                // just an Exception) -- an uncaught throwable on this background thread
                // would otherwise take the whole app down.
                Log.e(TAG, "Video export failed unexpectedly", t)
                mainHandler.post { finishExport(dialog, success = false) }
            } finally {
                activeVideoExporter = null
            }
        }.start()
    }

    private fun finishExport(dialog: ExportProgressDialog, success: Boolean) {
        setExportControlsEnabled(true)
        exportDialog = null
        try {
            if (success) {
                dialog.updateProgress(100, R.string.export_status_finishing)
                dialog.showSuccess {
                    Toast.makeText(this, R.string.export_done, Toast.LENGTH_LONG).show()
                }
            } else {
                dialog.showError {
                    Toast.makeText(this, R.string.export_failed, Toast.LENGTH_LONG).show()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to present export result UI", t)
            Toast.makeText(this, if (success) R.string.export_done else R.string.export_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun setExportControlsEnabled(enabled: Boolean) {
        binding.btnExport.isEnabled = enabled
        binding.btnBack.isEnabled = enabled
        binding.btnReset.isEnabled = enabled
    }

    override fun onPause() {
        super.onPause()
        mediaPlayer?.let { if (it.isPlaying) it.pause() }
        // GLSurfaceView needs its own onPause() so the GL thread releases the EGL surface
        // cleanly while backgrounded -- without it, resuming the thread after onResume()
        // can leave rendering stuck on a stale frame even though the view is visible again.
        binding.preview.onPause()
    }

    override fun onResume() {
        super.onResume()
        binding.preview.onResume()
        if (!userPaused) {
            mediaPlayer?.start()
            binding.preview.setContinuousRendering(true)
            binding.preview.requestRender()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.setOnErrorListener(null)
        mediaPlayer?.release()
        mediaPlayer = null
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (exportDialog != null) return // ignore system back/gesture while exporting
        super.onBackPressed()
    }
}
