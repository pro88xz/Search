package com.search.browser

import android.content.Context

/**
 * A file written into the public Downloads folder a piece at a time.
 *
 * The blob: and data: downloads a page hands over (an export from a web app,
 * a file a site assembled in the page) used to arrive whole: a blob was read
 * into one data: URL, carried across the bridge in one string and decoded
 * into one byte array before a single byte reached the disk. That needed
 * several times the file's size in memory at once, so it was capped at 16MB,
 * and anything bigger was refused. Written in pieces instead, the memory
 * needed stays at one piece whatever the file's size, so files of several GB
 * are fine.
 *
 * Made and used off the main thread: every call here touches the disk.
 * Android 10 and up write through MediaStore, marked pending until finished
 * so nothing lists a half-written file; older phones write the file directly
 * (with the storage permission the download path already asks for).
 */
class DownloadSink private constructor(
    private val context: Context,
    val name: String,
    val mime: String,
    private val out: java.io.OutputStream,
    private val uri: android.net.Uri?,
    private val file: java.io.File?
) {
    /** Bytes written so far. */
    var written = 0L
        private set
    private var closed = false

    fun write(bytes: ByteArray, len: Int = bytes.size) {
        out.write(bytes, 0, len)
        written += len
    }

    /** Closes the file and makes it visible; its address, or null if that failed. */
    fun finish(): String? {
        if (closed) return null
        closed = true
        return try {
            out.flush()
            out.close()
            if (uri != null) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, values, null, null)
                uri.toString()
            } else {
                val f = file ?: return null
                // Without the scan the file is on disk but nothing lists it,
                // which reads to the user as another failed save.
                android.media.MediaScannerConnection.scanFile(
                    context, arrayOf(f.absolutePath), arrayOf(mime), null)
                android.net.Uri.fromFile(f).toString()
            }
        } catch (e: Exception) {
            abort()
            null
        }
    }

    /** Throws away whatever was written: a half file is worse than none. */
    fun abort() {
        closed = true
        try { out.close() } catch (e: Exception) {}
        try {
            if (uri != null) context.contentResolver.delete(uri, null, null)
            else file?.delete()
        } catch (e: Exception) {}
    }

    companion object {
        /** A new file named [name] (renamed if one is already there), or null if none can be made. */
        fun open(context: Context, name: String, mime: String): DownloadSink? = try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                val out = uri?.let { context.contentResolver.openOutputStream(it) }
                if (uri == null || out == null) {
                    uri?.let { try { context.contentResolver.delete(it, null, null) } catch (e: Exception) {} }
                    null
                } else DownloadSink(context, name, mime, java.io.BufferedOutputStream(out, 256 * 1024), uri, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                // Never overwrite a file already sitting there.
                val stem = name.substringBeforeLast('.', name)
                val ext = name.substringAfterLast('.', "")
                var f = java.io.File(dir, name)
                var n = 1
                while (f.exists()) {
                    f = java.io.File(dir, stem + "(" + n + ")" + if (ext.isBlank()) "" else ".$ext")
                    n++
                }
                DownloadSink(context, f.name, mime,
                    java.io.BufferedOutputStream(java.io.FileOutputStream(f), 256 * 1024), null, f)
            }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * The notification for a file the app is writing itself: a progress bar
 * while it is written, then "Saved" (a tap opens Downloads) or "Couldn't
 * save". Updated at most twice a second, so a fast save does not flood the
 * shade. Silently absent where notifications are off.
 */
class SaveProgress(private val context: Context, private val name: String) {

    private val id = NEXT_ID.getAndIncrement()
    private var lastAt = 0L

    init { ensureChannel(context) }

    fun update(done: Long, total: Long) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastAt < 500L) return
        lastAt = now
        val b = base().setOngoing(true).setOnlyAlertOnce(true)
            .setSmallIcon(android.R.drawable.stat_sys_download)
        if (total > 0) {
            val pct = (done * 100 / total).toInt().coerceIn(0, 100)
            b.setProgress(100, pct, false)
            b.setContentText(Downloads.formatSize(done) + " of " + Downloads.formatSize(total))
        } else {
            b.setProgress(0, 0, true)
            b.setContentText(Downloads.formatSize(done))
        }
        post(b)
    }

    fun done(ok: Boolean) {
        val b = base().setOngoing(false).setAutoCancel(true)
            .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentText(if (ok) "Saved to Downloads" else "Couldn't save the file")
            .setContentIntent(openDownloads())
        post(b)
    }

    private fun base() = androidx.core.app.NotificationCompat.Builder(context, CHANNEL)
        .setContentTitle(name)
        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
        .setCategory(androidx.core.app.NotificationCompat.CATEGORY_PROGRESS)

    private fun openDownloads(): android.app.PendingIntent {
        val i = android.content.Intent(context, MainActivity::class.java)
            .putExtra(BrowsingData.EXTRA_OPEN, BrowsingData.OPEN_DOWNLOADS)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return android.app.PendingIntent.getActivity(context, id, i,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
    }

    private fun post(b: androidx.core.app.NotificationCompat.Builder) {
        try {
            androidx.core.app.NotificationManagerCompat.from(context).notify(TAG, id, b.build())
        } catch (e: SecurityException) {
            // Notifications not allowed (Android 13+ without the grant): the
            // save goes on regardless; the toast and the Downloads list say so.
        } catch (e: Exception) {}
    }

    companion object {
        private const val CHANNEL = "search_saves"
        private const val TAG = "save"
        private val NEXT_ID = java.util.concurrent.atomic.AtomicInteger(5000)

        private fun ensureChannel(c: Context) {
            if (android.os.Build.VERSION.SDK_INT < 26) return
            val nm = c.getSystemService(android.app.NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) != null) return
            nm.createNotificationChannel(android.app.NotificationChannel(
                CHANNEL, "File saves", android.app.NotificationManager.IMPORTANCE_LOW).apply {
                description = "Files a page hands to Search to save, while they are written"
                setShowBadge(false)
            })
        }
    }
}

/**
 * A download fetched by the app itself, for when Android's DownloadManager
 * will not take it - switched off on the phone (it can be, in Settings >
 * Apps), or refusing the address. Streamed straight to the Downloads folder
 * a buffer at a time (DownloadSink), so a file of any size needs no more
 * memory than the buffer, with the progress notification while it runs and
 * the space checked as it goes. Runs on a thread of its own.
 *
 * Unlike DownloadManager it does not survive the app being closed, so it is
 * the fallback, never the first choice.
 */
object HttpSave {

    fun start(
        context: Context, url: String, userAgent: String?, referer: String?, cookie: String?,
        name: String, mime: String, onDone: (Boolean) -> Unit
    ) {
        val app = context.applicationContext
        Thread {
            var ok = false
            var sink: DownloadSink? = null
            val progress = SaveProgress(app, name)
            var conn: java.net.HttpURLConnection? = null
            try {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn = c
                c.connectTimeout = 20_000
                c.readTimeout = 60_000
                c.instanceFollowRedirects = true
                userAgent?.let { c.setRequestProperty("User-Agent", it) }
                referer?.let { if (it.startsWith("http")) c.setRequestProperty("Referer", it) }
                cookie?.let { if (it.isNotBlank()) c.setRequestProperty("Cookie", it) }
                c.connect()
                if (c.responseCode in 200..299) {
                    val total = c.contentLengthLong
                    val s = DownloadSink.open(app, name, mime)
                    sink = s
                    if (s != null) {
                        val buf = ByteArray(256 * 1024)
                        var checkedAt = 0L
                        c.inputStream.use { input ->
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                s.write(buf, n)
                                if (s.written - checkedAt >= 64L * 1024 * 1024) {
                                    checkedAt = s.written
                                    val free = try {
                                        android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path).availableBytes
                                    } catch (e: Exception) { -1L }
                                    if (free in 0 until 200L * 1024 * 1024) throw java.io.IOException("storage full")
                                }
                                progress.update(s.written, total)
                            }
                        }
                        val uri = if (s.written > 0) s.finish() else { s.abort(); null }
                        if (uri != null) {
                            SavedFiles.add(app, s.name, uri, s.mime, s.written)
                            ok = true
                        }
                    }
                }
            } catch (t: Throwable) {
                sink?.abort()
            } finally {
                try { conn?.disconnect() } catch (e: Exception) {}
            }
            progress.done(ok)
            onDone(ok)
        }.start()
    }
}
