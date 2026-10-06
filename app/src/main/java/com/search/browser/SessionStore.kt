package com.search.browser

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * The open tabs, on disk, so a crash or a swipe from Recents does not cost
 * the session.
 *
 * TabStore (MainActivity) carries tabs through a rotation or a theme change,
 * and instance state carries their addresses through Android reclaiming the
 * app in the background. Neither survives a crash, or the user swiping the
 * app away: every tab was gone and the browser opened on a single home page.
 *
 * Two parts. session.json is the list - address, title, which tab opened
 * which - and is small enough to read at launch without slowing it. Each
 * tab's back/forward history (WebView.saveState) is a file of its own in
 * session_state/, written only when it changed, and read only when that tab
 * is opened: the active tab at launch, the rest when the user goes to them.
 *
 * Private (Night Owl) tabs are never written; MainActivity leaves them out
 * before anything reaches here.
 *
 * Saved history is only reused on the same Android build and the same
 * WebView. A Parcel is not a storage format across versions, and WebView
 * refuses state from another version anyway; on a mismatch the tabs come back
 * at their addresses, without their back/forward lists.
 *
 * A restore that crashes the app must not do it on every launch after. Each
 * restore is counted before it starts and cleared once the app is running
 * (onPause, or a few seconds in). One unfinished restore and the next launch
 * brings back addresses only; two, and it starts fresh.
 */
object SessionStore {

    class Entry(
        /** Names this tab's history file; kept for the tab's whole life. */
        val key: String,
        val url: String,
        val title: String,
        /** The opener's position in this list, or -1. */
        val openerIndex: Int,
        val signIn: Boolean,
        /** Whether the tab has saved history on disk after this save. */
        val hasState: Boolean,
        /** New history to write, or null when the file on disk is current. */
        val state: ByteArray?
    )

    class Saved(
        val key: String,
        val url: String,
        val title: String,
        val openerIndex: Int,
        val signIn: Boolean,
        val hasState: Boolean
    )

    class Session(
        val tabs: List<Saved>,
        val active: Int,
        /** False when saved history must not be used (see the class notes). */
        val stateUsable: Boolean
    )

    private const val FILE = "session.json"
    private const val STATE_DIR = "session_state"
    private const val PREFS = "session"
    private const val KEY_ATTEMPTS = "restore_attempts"
    private const val VERSION = 1

    /** One tab's history larger than this is not kept; the tab keeps its address. */
    const val MAX_STATE_BYTES = 512 * 1024

    // One writer, so saves land in the order they were made.
    private val io = Executors.newSingleThreadExecutor()

    fun stateFile(c: Context, key: String): File =
        File(File(c.filesDir, STATE_DIR), "$key.bin")

    /** The build and WebView a saved history belongs to. */
    private fun engineStamp(): String {
        val webView = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.webkit.WebView.getCurrentWebViewPackage()?.let { p ->
                    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        p.longVersionCode
                    } else {
                        @Suppress("DEPRECATION") p.versionCode.toLong()
                    }
                    p.packageName + "/" + code
                }
            } else null
        } catch (e: Throwable) { null }
        return Build.FINGERPRINT + "|" + (webView ?: "")
    }

    fun marshall(b: Bundle): ByteArray? {
        val p = Parcel.obtain()
        return try {
            b.writeToParcel(p, 0)
            p.marshall()
        } catch (e: Throwable) {
            null
        } finally {
            p.recycle()
        }
    }

    private fun unmarshall(bytes: ByteArray): Bundle? {
        val p = Parcel.obtain()
        return try {
            p.unmarshall(bytes, 0, bytes.size)
            p.setDataPosition(0)
            Bundle.CREATOR.createFromParcel(p)
        } catch (e: Throwable) {
            null
        } finally {
            p.recycle()
        }
    }

    /** A tab's saved history, read from its file. Null if missing or unreadable. */
    fun readState(file: File): Bundle? = try {
        if (!file.isFile || file.length() > MAX_STATE_BYTES) null
        else unmarshall(AtomicFile(file).readFully())
    } catch (e: Throwable) {
        null
    }

    /**
     * Writes the session, off the main thread. History files go first and
     * the list last, so the list on disk never names a file not yet written;
     * files no tab names any more are deleted after.
     */
    fun save(c: Context, entries: List<Entry>, active: Int) {
        val app = c.applicationContext
        val stamp = engineStamp()
        val savedAt = System.currentTimeMillis()
        io.execute {
            try {
                val dir = File(app.filesDir, STATE_DIR)
                if (!dir.isDirectory) dir.mkdirs()
                entries.forEach { e ->
                    val bytes = e.state ?: return@forEach
                    writeAtomically(stateFile(app, e.key), bytes)
                }
                val list = JSONArray()
                entries.forEach { e ->
                    list.put(JSONObject()
                        .put("key", e.key)
                        .put("url", e.url)
                        .put("title", e.title)
                        .put("opener", e.openerIndex)
                        .put("signIn", e.signIn)
                        .put("state", e.hasState))
                }
                val root = JSONObject()
                    .put("v", VERSION)
                    .put("engine", stamp)
                    .put("savedAt", savedAt)
                    .put("active", active)
                    .put("tabs", list)
                writeAtomically(File(app.filesDir, FILE), root.toString().toByteArray())
                val keep = entries.filter { it.hasState }.map { it.key + ".bin" }.toSet()
                dir.listFiles()?.forEach { f -> if (f.name !in keep) f.delete() }
            } catch (e: Throwable) {
                // A failed save leaves the previous one in place.
            }
        }
    }

    private fun writeAtomically(file: File, bytes: ByteArray) {
        val af = AtomicFile(file)
        val out = af.startWrite()
        try {
            out.write(bytes)
            af.finishWrite(out)
        } catch (e: Throwable) {
            af.failWrite(out)
            throw e
        }
    }

    /** The last saved session, or null when there is none worth restoring. */
    fun load(c: Context): Session? = try {
        val file = File(c.filesDir, FILE)
        if (!file.isFile) null else {
            val root = JSONObject(String(AtomicFile(file).readFully()))
            if (root.optInt("v") != VERSION) null else {
                val arr = root.optJSONArray("tabs") ?: JSONArray()
                val tabs = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val url = o.optString("url")
                    val key = o.optString("key")
                    if (url.isBlank() || key.isBlank()) null
                    else Saved(key, url, o.optString("title", "New Tab"),
                        o.optInt("opener", -1), o.optBoolean("signIn"),
                        o.optBoolean("state"))
                }
                if (tabs.isEmpty()) null
                else Session(tabs, root.optInt("active", 0).coerceIn(0, tabs.size - 1),
                    root.optString("engine") == engineStamp())
            }
        }
    } catch (e: Throwable) {
        null
    }

    /**
     * Waits, up to [timeoutMs], for every save already handed over to reach
     * the disk - for when the process is about to end on purpose.
     */
    fun flush(timeoutMs: Long) {
        try {
            io.submit {}.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: Throwable) { /* the writer is stuck or gone; carry on */ }
    }

    /** Removes the saved session entirely. */
    fun clear(c: Context) {
        val app = c.applicationContext
        io.execute {
            try {
                File(app.filesDir, FILE).delete()
                File(app.filesDir, STATE_DIR).listFiles()?.forEach { it.delete() }
            } catch (e: Throwable) {}
        }
    }

    /**
     * Counts a restore before it starts and returns how many before it never
     * finished. Written with commit(), not apply(): if the restore crashes the
     * app, the count has to be on disk already.
     */
    fun beginRestore(c: Context): Int {
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val before = prefs.getInt(KEY_ATTEMPTS, 0)
        prefs.edit().putInt(KEY_ATTEMPTS, before + 1).commit()
        return before
    }

    /** The restore is behind us: the app is up and running. */
    fun restoreSettled(c: Context) {
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_ATTEMPTS, 0) != 0) prefs.edit().putInt(KEY_ATTEMPTS, 0).apply()
    }
}
