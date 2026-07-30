package com.sernheise.app.gl

/**
 * All user-controllable edit values.
 *
 * Bipolar sliders (everything except [structure] and [sharpen]) are stored in the
 * UI range [-100, 100] and converted to shader-friendly units inside [SernShaders].
 * [structure] and [sharpen] are stored in [0, 100] since they are "amount" style controls.
 *
 * [rotation], [flipHorizontal], and [flipVertical] are non-destructive geometry transforms
 * applied on top of the source's own orientation (EXIF for photos, rotation metadata for
 * video). None of them are ever baked into decoded pixels -- they're applied purely as a
 * texture-coordinate transform at render time, identically for the live preview and export.
 */
data class Adjustments(
    var exposure: Float = 0f,
    var brightness: Float = 0f,
    var contrast: Float = 0f,
    var saturation: Float = 0f,
    var highlights: Float = 0f,
    var shadows: Float = 0f,
    var temperature: Float = 0f,
    var tint: Float = 0f,
    var structure: Float = 0f,
    var sharpen: Float = 0f,
    var rotation: Int = 0,
    var flipHorizontal: Boolean = false,
    var flipVertical: Boolean = false
) {
    /** Advances [rotation] by one quarter-turn clockwise, wrapping 270 degrees back to 0. */
    fun rotatedClockwise(): Adjustments = copy(rotation = (rotation + 90) % 360)

    companion object {
        const val MIN = -100f
        const val MAX = 100f
        const val DETAIL_MIN = 0f
        const val DETAIL_MAX = 100f
    }
}

/**
 * [Tool.ROTATE], [Tool.FLIP_HORIZONTAL], and [Tool.FLIP_VERTICAL] are action/toggle tools,
 * not sliders: tapping Rotate steps [Adjustments.rotation] by 90 degrees, tapping either
 * Flip tool toggles the matching boolean. [Tool.isBipolar] is unused for all three --
 * callers should check tool identity before consulting it.
 */
enum class Tool(val isBipolar: Boolean) {
    EXPOSURE(true),
    BRIGHTNESS(true),
    CONTRAST(true),
    SATURATION(true),
    HIGHLIGHTS(true),
    SHADOWS(true),
    TEMPERATURE(true),
    TINT(true),
    STRUCTURE(false),
    SHARPEN(false),
    ROTATE(false),
    FLIP_HORIZONTAL(false),
    FLIP_VERTICAL(false)
}

fun Adjustments.get(tool: Tool): Float = when (tool) {
    Tool.EXPOSURE -> exposure
    Tool.BRIGHTNESS -> brightness
    Tool.CONTRAST -> contrast
    Tool.SATURATION -> saturation
    Tool.HIGHLIGHTS -> highlights
    Tool.SHADOWS -> shadows
    Tool.TEMPERATURE -> temperature
    Tool.TINT -> tint
    Tool.STRUCTURE -> structure
    Tool.SHARPEN -> sharpen
    Tool.ROTATE -> rotation.toFloat()
    Tool.FLIP_HORIZONTAL -> if (flipHorizontal) 1f else 0f
    Tool.FLIP_VERTICAL -> if (flipVertical) 1f else 0f
}

fun Adjustments.set(tool: Tool, value: Float) {
    when (tool) {
        Tool.EXPOSURE -> exposure = value
        Tool.BRIGHTNESS -> brightness = value
        Tool.CONTRAST -> contrast = value
        Tool.SATURATION -> saturation = value
        Tool.HIGHLIGHTS -> highlights = value
        Tool.SHADOWS -> shadows = value
        Tool.TEMPERATURE -> temperature = value
        Tool.TINT -> tint = value
        Tool.STRUCTURE -> structure = value
        Tool.SHARPEN -> sharpen = value
        Tool.ROTATE -> rotation = value.toInt()
        Tool.FLIP_HORIZONTAL -> flipHorizontal = value != 0f
        Tool.FLIP_VERTICAL -> flipVertical = value != 0f
    }
}
