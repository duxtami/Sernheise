package com.sernheise.app.gl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView
import android.util.AttributeSet

/**
 * Live preview surface for the editor. Hosts a [SernRenderer] and exposes a small,
 * activity-friendly API for pushing bitmaps, preparing video playback and live
 * adjustment updates.
 */
class SernPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val renderer: SernRenderer
    var videoSurfaceReadyListener: ((SurfaceTexture) -> Unit)? = null

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true

        renderer = SernRenderer(object : SernRenderer.Listener {
            override fun onVideoSurfaceReady(surfaceTexture: SurfaceTexture) {
                post { videoSurfaceReadyListener?.invoke(surfaceTexture) }
            }

            override fun onFrameDrawn() {
                // Hook point for future frame-level callbacks (e.g. export progress).
            }
        })
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun showBitmap(bitmap: Bitmap) {
        renderer.setBitmap(bitmap)
        requestRender()
    }

    fun prepareForVideo(width: Int, height: Int) {
        renderer.prepareForVideo(width, height)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    /** Sets the rotation baked into the source itself (video container metadata; always 0 for images). */
    fun setSourceRotation(degrees: Int) {
        renderer.sourceRotationDegrees = degrees
        requestRender()
    }

    fun setContinuousRendering(continuous: Boolean) {
        renderMode = if (continuous) RENDERMODE_CONTINUOUSLY else RENDERMODE_WHEN_DIRTY
    }

    fun updateAdjustments(adjustments: Adjustments) {
        renderer.updateAdjustments(adjustments)
        requestRender()
    }

    /** Renders the full-resolution edited image on the GL thread and delivers it on [onResult]. */
    fun exportImage(width: Int, height: Int, adjustments: Adjustments, onResult: (Bitmap) -> Unit) {
        queueEvent {
            val bitmap = renderer.renderImageToBitmap(width, height, adjustments)
            post { onResult(bitmap) }
        }
    }
}
