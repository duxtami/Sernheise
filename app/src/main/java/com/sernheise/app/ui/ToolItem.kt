package com.sernheise.app.ui

import com.sernheise.app.R
import com.sernheise.app.gl.Tool

data class ToolItem(val tool: Tool, val iconRes: Int, val labelRes: Int)

object ToolCatalog {
    val adjustTools = listOf(
        ToolItem(Tool.EXPOSURE, R.drawable.ic_exposure, R.string.tool_exposure),
        ToolItem(Tool.BRIGHTNESS, R.drawable.ic_brightness, R.string.tool_brightness),
        ToolItem(Tool.CONTRAST, R.drawable.ic_contrast, R.string.tool_contrast),
        ToolItem(Tool.SATURATION, R.drawable.ic_saturation, R.string.tool_saturation),
        ToolItem(Tool.VIBRANCE, R.drawable.ic_vibrance, R.string.tool_vibrance),
        ToolItem(Tool.HIGHLIGHTS, R.drawable.ic_highlights, R.string.tool_highlights),
        ToolItem(Tool.SHADOWS, R.drawable.ic_shadows, R.string.tool_shadows),
        ToolItem(Tool.TEMPERATURE, R.drawable.ic_temperature, R.string.tool_temperature),
        ToolItem(Tool.TINT, R.drawable.ic_tint, R.string.tool_tint),
        ToolItem(Tool.ROTATE, R.drawable.ic_rotate, R.string.tool_rotate),
        ToolItem(Tool.FLIP_HORIZONTAL, R.drawable.ic_flip_horizontal, R.string.tool_flip_horizontal),
        ToolItem(Tool.FLIP_VERTICAL, R.drawable.ic_flip_vertical, R.string.tool_flip_vertical)
    )

    val detailTools = listOf(
        ToolItem(Tool.STRUCTURE, R.drawable.ic_structure, R.string.tool_structure),
        ToolItem(Tool.CLARITY, R.drawable.ic_clarity, R.string.tool_clarity),
        ToolItem(Tool.SHARPEN, R.drawable.ic_sharpen, R.string.tool_sharpen)
    )
}
