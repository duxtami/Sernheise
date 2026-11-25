package com.sernheise.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.sernheise.app.R
import com.sernheise.app.databinding.ItemToolBinding
import com.sernheise.app.gl.Adjustments
import com.sernheise.app.gl.Tool
import com.sernheise.app.gl.getToolValue

class ToolAdapter(
    private val items: List<ToolItem>,
    private val adjustments: () -> Adjustments,
    private val onToolSelected: (Tool) -> Unit
) : RecyclerView.Adapter<ToolAdapter.ToolViewHolder>() {

    private var selectedTool: Tool? = null

    inner class ToolViewHolder(val binding: ItemToolBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ToolViewHolder {
        val binding = ItemToolBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ToolViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ToolViewHolder, position: Int) {
        val item = items[position]
        val context = holder.binding.root.context
        holder.binding.toolIcon.setImageResource(item.iconRes)
        holder.binding.toolLabel.setText(item.labelRes)

        val isActive = adjustments().getToolValue(item.tool) != 0f
        val isSelected = selectedTool == item.tool
        val color = if (isSelected || isActive) R.color.sern_accent else R.color.sern_text_secondary
        holder.binding.toolIcon.setColorFilter(context.getColor(color))
        holder.binding.toolLabel.setTextColor(context.getColor(color))

        // The Rotate icon itself shows the current angle, so the tool tile always
        // reflects state at a glance -- no slider needed to see where you're at.
        holder.binding.toolIcon.rotation = if (item.tool == Tool.ROTATE) adjustments().rotation.toFloat() else 0f

        holder.binding.root.setOnClickListener {
            val previousSelected = selectedTool
            selectedTool = item.tool
            if (item.tool == Tool.ROTATE) {
                holder.binding.toolIcon.animate().rotationBy(90f).setDuration(220).start()
            }
            onToolSelected(item.tool)
            notifyItemChanged(items.indexOfFirst { it.tool == previousSelected }.coerceAtLeast(0))
            notifyItemChanged(position)
        }
    }

    override fun getItemCount(): Int = items.size

    fun refreshActiveStates() = notifyDataSetChanged()
}
