package com.search.browser

import android.app.DownloadManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DownloadsAdapter(
    private var items: List<Downloads.Item>,
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
            // DownloadManager.remove() deletes the file from disk, not just
            // the row, so the label has to say what actually happens.
            menu.menu.add("Delete file")
            menu.setOnMenuItemClickListener { onDelete(d); true }
            menu.show()
            true
        }
    }

    /**
     * Swaps the rows in place. Building a fresh adapter on every refresh would
     * drop the user's scroll position, which a list that refreshes while a
     * download is running cannot afford.
     */
    fun submit(newItems: List<Downloads.Item>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size
}
