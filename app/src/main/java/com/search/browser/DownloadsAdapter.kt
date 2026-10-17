package com.search.browser

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DownloadsAdapter(
    private var items: List<Downloads.Item>,
    private val onOpen: (Downloads.Item) -> Unit,
    /** The overflow button. Deleting and sharing both live behind it. */
    private val onMore: (Downloads.Item) -> Unit
) : RecyclerView.Adapter<DownloadsAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.hTitle)
        val sub: TextView = v.findViewById(R.id.hUrl)
        val more: View = v.findViewById(R.id.hMore)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        // Its own layout now rather than the history row's: a download has an
        // action attached to it and history does not, and a button that only
        // some rows use has no business in a shared layout.
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_download, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val d = items[position]
        holder.title.text = d.title
        holder.sub.text = when {
            d.isComplete -> Downloads.formatSize(d.bytesTotal).ifEmpty { "Completed" }
            d.isFailed -> if (d.canRetry) "Failed \u00B7 tap to retry" else "Failed"
            // Paused is DownloadManager waiting on something, and resuming by
            // itself once it has it; saying what reads better than a stuck %.
            d.isPaused -> {
                val pct = if (d.bytesTotal > 0)
                    (d.bytesSoFar * 100 / d.bytesTotal).toInt() else 0
                when (d.reason) {
                    android.app.DownloadManager.PAUSED_WAITING_FOR_NETWORK ->
                        "Waiting for network\u2026 $pct%"
                    android.app.DownloadManager.PAUSED_QUEUED_FOR_WIFI ->
                        "Waiting for Wi-Fi\u2026 $pct%"
                    android.app.DownloadManager.PAUSED_WAITING_TO_RETRY ->
                        "Retrying soon\u2026 $pct%"
                    else -> "Paused $pct%"
                }
            }
            d.isRunning -> {
                // How much, of how much, and how fast: a 4GB file at a steady
                // 1% reads as stuck without them.
                val pct = if (d.bytesTotal > 0)
                    (d.bytesSoFar * 100 / d.bytesTotal).toInt() else 0
                val so = Downloads.formatSize(d.bytesSoFar)
                val of = Downloads.formatSize(d.bytesTotal)
                val amount = when {
                    so.isEmpty() -> ""
                    of.isEmpty() -> " \u00B7 $so"
                    else -> " \u00B7 $so of $of"
                }
                val rate = rates[d.id]?.takeIf { it > 0.0 }
                    ?.let { " \u00B7 " + Downloads.formatSize(it.toLong()) + "/s" } ?: ""
                (if (d.bytesTotal > 0) "Downloading\u2026 $pct%" else "Downloading\u2026") + amount + rate
            }
            else -> "Pending"
        }
        holder.itemView.setOnClickListener { onOpen(d) }
        holder.more.setOnClickListener { onMore(d) }
        // The long-press menu is gone. It was undiscoverable - nothing on the
        // row suggested it existed - and its single entry deleted the file on
        // one tap with nothing in between.
        holder.itemView.setOnLongClickListener { holder.more.performClick() }
    }

    /**
     * Swaps the rows in place. Building a fresh adapter on every refresh would
     * drop the user's scroll position, which a list that refreshes while a
     * download is running cannot afford.
     */
    fun submit(newItems: List<Downloads.Item>) {
        val now = android.os.SystemClock.uptimeMillis()
        newItems.forEach { d ->
            if (!d.isRunning || !d.managed) { seen.remove(d.id); rates.remove(d.id); return@forEach }
            val before = seen[d.id]
            if (before != null && now > before.second) {
                val bps = (d.bytesSoFar - before.first) * 1000.0 / (now - before.second)
                // Eased, so one slow poll does not make the speed jump about.
                val old = rates[d.id]
                rates[d.id] = if (old == null || old <= 0.0) bps else old * 0.6 + bps * 0.4
            }
            seen[d.id] = d.bytesSoFar to now
        }
        items = newItems
        notifyDataSetChanged()
    }

    /** Bytes and time at the last poll, per running download; and its speed in bytes a second. */
    private val seen = HashMap<Long, Pair<Long, Long>>()
    private val rates = HashMap<Long, Double>()

    override fun getItemCount() = items.size
}
