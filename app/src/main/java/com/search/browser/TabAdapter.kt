package com.search.browser

import android.graphics.Matrix
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TabAdapter(
    private val tabs: List<Tab>,
    private val onSelect: (Tab) -> Unit,
    private val onClose: (Tab) -> Unit,
    /**
     * Both are read on every bind rather than captured once. The deck calls
     * notifyDataSetChanged as it opens, so a tab switch or an accent change
     * needs no new adapter and no invalidation of its own.
     */
    private val isActive: (Tab) -> Boolean = { false },
    private val accent: () -> Int = { 0xFF8B6BD8.toInt() }
) : RecyclerView.Adapter<TabAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.tabThumb)
        val title: TextView = v.findViewById(R.id.tabTitle)
        val close: ImageButton = v.findViewById(R.id.closeTab)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tab, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val tab = tabs[position]
        holder.title.text = tab.title.ifBlank { "New Tab" }

        // The live tab wears a ring in the accent picked for the owl, and sits
        // slightly higher. Built here rather than as a second drawable because
        // a stroke cannot be tinted apart from its fill - backgroundTintList
        // would recolour the whole card.
        val ctx = holder.itemView.context
        val res = holder.itemView.resources
        val density = res.displayMetrics.density
        val active = isActive(tab)
        val bg = android.graphics.drawable.GradientDrawable()
        bg.cornerRadius = res.getDimension(R.dimen.radius_lg)
        bg.setColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.settingsCard))
        if (active) bg.setStroke((2f * density).toInt(), accent())
        // Same radius as tab_card_bg, so clipToOutline keeps rounding the
        // thumbnail exactly as it did.
        holder.itemView.background = bg
        holder.itemView.elevation = (if (active) 6f else 2f) * density
        holder.itemView.setOnClickListener { onSelect(tab) }
        holder.close.setOnClickListener { onClose(tab) }

        val bmp = tab.thumbnail
        if (bmp != null) {
            holder.thumb.setImageBitmap(bmp)
            // Scale the (wide) capture to fit the card width, pinned to the top.
            holder.thumb.post {
                val vw = holder.thumb.width.toFloat()
                if (vw > 0 && bmp.width > 0) {
                    val scale = vw / bmp.width.toFloat()
                    val m = Matrix()
                    m.setScale(scale, scale)
                    holder.thumb.imageMatrix = m
                }
            }
        } else {
            holder.thumb.setImageDrawable(null)
        }
    }

    override fun getItemCount(): Int = tabs.size
}
