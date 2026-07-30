package com.sernheise.app.gl

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * A single fullscreen quad (two triangles) used to draw the current texture.
 *
 * Rotation and flip are implemented as a *texture-coordinate permutation*, not a vertex
 * matrix rotation. The on-screen quad shape always stays the plain -1..1 square (aspect
 * fit is handled separately, in the MVP matrix, using already rotation-aware dimensions);
 * only which texel of the source ends up in which screen corner changes.
 *
 * There are two different BASE conventions, and using the wrong one for a given source is
 * exactly what causes an unwanted vertical flip:
 *  - IMAGE: [GLUtils.texImage2D] uploads a [android.graphics.Bitmap] row-for-row (row 0 =
 *    visual top), so the base mapping here has V=0 at the bottom / V=1 at the top to match.
 *  - VIDEO: a [android.graphics.SurfaceTexture]'s own `getTransformMatrix()` already
 *    compensates for however the decoder's buffer is actually laid out (this varies by
 *    device/encoder), and it assumes its *input* uses the plain, non-pre-flipped mapping
 *    (V=0 at bottom, same as the position -- this is the standard convention used by every
 *    Grafika/ExoPlayer-style SurfaceTexture renderer). Feeding it the IMAGE convention on
 *    top double-compensates, producing an inconsistent vertical flip that varies by source.
 *
 * Rotation's corner permutation is applied identically on top of whichever base is active;
 * a pure index permutation can't introduce a mirror regardless of which UV values populate
 * the four corners, so this doesn't need two separate rotation tables.
 */
class Quad {

    private val positions = floatArrayOf(
        -1f, -1f,
        1f, -1f,
        -1f, 1f,
        1f, 1f
    )

    // index 0 = bottom-left, 1 = bottom-right, 2 = top-left, 3 = top-right.
    private val baseU = floatArrayOf(0f, 1f, 0f, 1f)

    // Image base: V=0 bottom / V=1 top (matches Bitmap row order via GLUtils.texImage2D).
    private val baseVImage = floatArrayOf(1f, 1f, 0f, 0f)

    // Video base: plain/standard mapping -- SurfaceTexture's own transform matrix handles
    // whatever row-order compensation the decoder buffer actually needs.
    private val baseVVideo = floatArrayOf(0f, 0f, 1f, 1f)

    // For each rotation state, rotationOrder[k] says which BASE corner supplies output slot k.
    // Derived by hand (and cross-checked for cyclic consistency) from the corner-permutation
    // a pure clockwise rotation produces; see the class doc above.
    private val rotationOrder = mapOf(
        0 to intArrayOf(0, 1, 2, 3),
        90 to intArrayOf(1, 3, 0, 2),
        180 to intArrayOf(3, 2, 1, 0),
        270 to intArrayOf(2, 0, 3, 1)
    )

    private val texCoords = FloatArray(8)
    private val positionBuffer: FloatBuffer = makeBuffer(positions)
    private val texCoordBuffer: FloatBuffer = makeBuffer(FloatArray(8))

    init {
        setOrientation(0, flipHorizontal = false, flipVertical = false, sourceIsVideo = false)
    }

    private fun makeBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(data); position(0) }

    /**
     * Recomputes and re-uploads the texture-coordinate buffer for the given clockwise
     * [rotationDegrees] (0/90/180/270) and flip flags, using the correct base convention
     * for [sourceIsVideo]. Flip is applied to each source corner's own (u, v) first, then
     * the rotation permutation reorders the (already flipped) corners to their screen slots
     * -- so "flip, then rotate" always matches what the user sees, regardless of which was
     * toggled most recently.
     */
    fun setOrientation(rotationDegrees: Int, flipHorizontal: Boolean, flipVertical: Boolean, sourceIsVideo: Boolean) {
        val baseV = if (sourceIsVideo) baseVVideo else baseVImage
        val order = rotationOrder[((rotationDegrees % 360) + 360) % 360] ?: rotationOrder.getValue(0)
        for (slot in 0 until 4) {
            val sourceCorner = order[slot]
            var u = baseU[sourceCorner]
            var v = baseV[sourceCorner]
            if (flipHorizontal) u = 1f - u
            if (flipVertical) v = 1f - v
            texCoords[slot * 2] = u
            texCoords[slot * 2 + 1] = v
        }
        texCoordBuffer.position(0)
        texCoordBuffer.put(texCoords)
        texCoordBuffer.position(0)
    }

    fun draw(shader: ShaderProgram) = draw(shader.aPosition, shader.aTexCoord)

    /** Lower-level entry point for callers (like [StructurePipeline]) that use [GlProgram] instead of [ShaderProgram]. */
    fun draw(positionAttrib: Int, texCoordAttrib: Int) {
        positionBuffer.position(0)
        GLES20.glEnableVertexAttribArray(positionAttrib)
        GLES20.glVertexAttribPointer(positionAttrib, 2, GLES20.GL_FLOAT, false, 0, positionBuffer)

        texCoordBuffer.position(0)
        GLES20.glEnableVertexAttribArray(texCoordAttrib)
        GLES20.glVertexAttribPointer(texCoordAttrib, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionAttrib)
        GLES20.glDisableVertexAttribArray(texCoordAttrib)
    }
}
