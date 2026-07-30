package com.sernheise.app.gl

/**
 * Shared rotation math used identically by the live preview ([SernRenderer]) and the
 * video exporter ([com.sernheise.app.video.VideoExporter]) -- having one function for
 * this is what guarantees preview and export can never disagree on orientation.
 */
object RotationUtils {

    /** True if swapping width/height is needed to describe content rotated by [totalRotationDegrees]. */
    fun swapsDimensions(totalRotationDegrees: Int): Boolean {
        val normalized = ((totalRotationDegrees % 360) + 360) % 360
        return normalized == 90 || normalized == 270
    }

    /**
     * The on-screen/output size for content whose *decoded* size is [rawWidth]x[rawHeight]
     * once [totalRotationDegrees] (clockwise) is applied.
     */
    fun effectiveSize(rawWidth: Int, rawHeight: Int, totalRotationDegrees: Int): Pair<Int, Int> =
        if (swapsDimensions(totalRotationDegrees)) rawHeight to rawWidth else rawWidth to rawHeight
}
