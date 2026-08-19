package com.search.browser

import android.app.DownloadManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DownloadsAdapter(
    private val items: List<Downloads.Item>,
    private val onOpen: (Downloads.Item) -> Unit,
    private val onDelete: (Downloads.Item) -> Unit
) : RecyclerView.Adapter<DownloadsAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.hTitle)
        val sub: TextView = v.findViewById(R.id.hUrl)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val d = items[position]
        holder.title.text = d.title
        holder.sub.text = when {
            d.isComplete -> Downloads.formatSize(d.bytesTotal).ifEmpty { "Completed" }
            d.isFailed -> "Failed"
            d.isRunning -> {
                val pct = if (d.bytesTotal > 0)
                    (d.bytesSoFar * 100 / d.bytesTotal).toInt() else 0
                "Downloading… $pct%"
            }
            else -> "Pending"
        }
        holder.itemView.setOnClickListener { onOpen(d) }
        holder.itemView.setOnLongClickListener { anchor ->
            val menu = PopupMenu(anchor.context, anchor)
            menu.menu.add("Remove")
            menu.setOnMenuItemClickListener { onDelete(d); true }
            menu.show()
            true
        }
    }

    override fun getItemCount() = items.size
}
