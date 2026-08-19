package com.search.browser

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor

/** Reads the device's download records (enqueued via DownloadManager) so they
 *  can be shown in an in-app Downloads list instead of punting to the system UI. */
object Downloads {

    data class Item(
        val id: Long,
        val title: String,
        val status: Int,        // DownloadManager.STATUS_*
        val bytesTotal: Long,
        val bytesSoFar: Long,
        val localUri: String?,
        val mimeType: String?
    ) {
        val isComplete get() = status == DownloadManager.STATUS_SUCCESSFUL
        val isFailed get() = status == DownloadManager.STATUS_FAILED
        val isRunning get() = status == DownloadManager.STATUS_RUNNING ||
                              status == DownloadManager.STATUS_PENDING ||
                              status == DownloadManager.STATUS_PAUSED
    }

    fun load(c: Context): List<Item> {
        val dm = c.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val out = ArrayList<Item>()
        val query = DownloadManager.Query()
        var cur: Cursor? = null
        try {
            cur = dm.query(query)
            if (cur != null) {
                val idI = cur.getColumnIndex(DownloadManager.COLUMN_ID)
                val titleI = cur.getColumnIndex(DownloadManager.COLUMN_TITLE)
                val statusI = cur.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val totalI = cur.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val soFarI = cur.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val uriI = cur.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                val mimeI = cur.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE)
                while (cur.moveToNext()) {
                    out.add(
                        Item(
                            id = if (idI >= 0) cur.getLong(idI) else 0,
                            title = (if (titleI >= 0) cur.getString(titleI) else null) ?: "File",
                            status = if (statusI >= 0) cur.getInt(statusI) else 0,
                            bytesTotal = if (totalI >= 0) cur.getLong(totalI) else 0,
                            bytesSoFar = if (soFarI >= 0) cur.getLong(soFarI) else 0,
                            localUri = if (uriI >= 0) cur.getString(uriI) else null,
                            mimeType = if (mimeI >= 0) cur.getString(mimeI) else null
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Return whatever we gathered; empty on failure.
        } finally {
            cur?.close()
        }
        // Newest first (DownloadManager returns oldest-first by default).
        return out.reversed()
    }

    /** Human-readable size, e.g. "2.4 MB". */
    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val units = arrayOf("B", "KB", "MB", "GB")
        var size = bytes.toDouble()
        var i = 0
        while (size >= 1024 && i < units.size - 1) { size /= 1024; i++ }
        return if (i == 0) "${size.toInt()} ${units[i]}"
               else String.format("%.1f %s", size, units[i])
    }
}
