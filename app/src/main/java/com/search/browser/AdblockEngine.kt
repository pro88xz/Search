package com.search.browser

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Brave's ad and tracker blocking engine - adblock-rust, the one inside the
 * Brave browser - with EasyList (ads), EasyPrivacy (trackers) and Brave's
 * own fixes for the sites those lists break.
 *
 * The engine is native code (native/adblock-jni, libadblock_jni.so) and the
 * lists come compiled with the app (assets/adblock/engine.dat, see
 * native/adblock-jni/build.sh). While blocking is on, fresh lists are
 * fetched every few days and compiled on the phone, so it does not wait on
 * an app update to learn about a new ad server.
 *
 * Everything here is allowed to fail. If the library will not load on a
 * phone, or a list will not compile, nothing breaks: [check] answers
 * [NO_ENGINE] and AdBlocker carries on with its own short list, as it did
 * before the engine existed.
 */
object AdblockEngine {

    const val NO_ENGINE = -1
    const val ALLOW = 0
    const val BLOCK = 1

    private const val ASSET = "adblock/engine.dat"
    private const val PREFS = "adblock_engine"
    private const val KEY_UPDATED = "updated"
    private const val KEY_TRIED = "tried"
    private const val KEY_FOR_VERSION = "for_version"

    private const val DAY = 24L * 60 * 60 * 1000
    /** EasyList says to refresh every 4 days; a day's grace on top. */
    private const val UPDATE_EVERY = 5 * DAY
    /** On a metered connection, a few MB is spent less often. */
    private const val UPDATE_EVERY_METERED = 14 * DAY
    /** After a failed try - offline, a list host down - before the next. */
    private const val RETRY_AFTER = DAY
    private const val MAX_LIST_BYTES = 12 * 1024 * 1024

    /** Where the lists come from - the same addresses Brave and uBlock Origin fetch them from. */
    private val LISTS = arrayOf(
        "https://easylist.to/easylist/easylist.txt",
        "https://easylist.to/easylist/easyprivacy.txt",
        "https://raw.githubusercontent.com/brave/adblock-lists/master/brave-unbreak.txt"
    )

    private val libLoaded: Boolean by lazy {
        try {
            System.loadLibrary("adblock_jni")
            true
        } catch (t: Throwable) {
            false
        }
    }

    @Volatile private var ready = false
    @Volatile private var started = false

    /** Whether the engine is loaded and answering. */
    fun isReady(): Boolean = ready

    /**
     * Loads the engine in the background, the first time it is wanted.
     * Cheap to call on every request: after the first call it is one read of
     * a flag. Until the load is done (a fraction of a second), [check]
     * answers [NO_ENGINE].
     */
    fun warmUp(c: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        val app = c.applicationContext
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            ready = load(app)
            maybeUpdate(app)
        }, "adblock-engine").start()
    }

    private fun cacheFile(c: Context) = File(File(c.filesDir, "adblock"), "engine.dat")

    /**
     * The lists last fetched on this phone if this version of the app wrote
     * them, else the ones it came with. A compiled engine is only readable by
     * the engine version that wrote it, so an app update starts again from
     * its own copy - which is newer than the cache's anyway, more often than
     * not.
     */
    private fun load(c: Context): Boolean {
        if (!libLoaded) return false
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cache = cacheFile(c)
        if (prefs.getInt(KEY_FOR_VERSION, -1) == BuildConfig.VERSION_CODE && cache.isFile) {
            val ok = try { nativeLoad(cache.readBytes()) } catch (t: Throwable) { false }
            if (ok) return true
        }
        cache.delete()
        prefs.edit().remove(KEY_UPDATED).remove(KEY_FOR_VERSION).apply()
        return try {
            nativeLoad(c.assets.open(ASSET).use { it.readBytes() })
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Fetches and compiles fresh lists if the ones in use are due, then
     * swaps them in. Pages keep loading throughout: the old engine answers
     * until the new one has been built and checked.
     *
     * Left to app updates on a low-memory phone (Android Go and the like),
     * where compiling the lists, a few tens of MB for a second or two, is
     * not worth the squeeze.
     */
    private fun maybeUpdate(c: Context) {
        if (!ready || !AdBlocker.isEnabled(c)) return
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        if (am == null || am.isLowRamDevice) return
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        // The app's own lists are as old as the app's install, near enough.
        val installed = File(c.applicationInfo.sourceDir).lastModified()
        val updated = maxOf(prefs.getLong(KEY_UPDATED, 0L), installed)
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val metered = try { cm?.isActiveNetworkMetered ?: true } catch (e: Exception) { true }
        val every = if (metered) UPDATE_EVERY_METERED else UPDATE_EVERY
        if (now - updated < every) return
        if (now - prefs.getLong(KEY_TRIED, 0L) < RETRY_AFTER) return
        prefs.edit().putLong(KEY_TRIED, now).apply()

        val lists = LISTS.map { fetch(it) ?: return }
        val data = try { nativeBuild(lists.toTypedArray()) } catch (t: Throwable) { null } ?: return
        val cache = cacheFile(c)
        try {
            cache.parentFile?.mkdirs()
            val part = File(cache.parentFile, "engine.dat.part")
            part.writeBytes(data)
            if (!part.renameTo(cache)) return
        } catch (e: Exception) {
            return
        }
        if (try { nativeLoad(data) } catch (t: Throwable) { false }) {
            prefs.edit().putLong(KEY_UPDATED, now).putInt(KEY_FOR_VERSION, BuildConfig.VERSION_CODE).apply()
        }
    }

    /** A list's text, or null for anything that is not one: an error page, a captive portal, a cut-off download. */
    private fun fetch(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (conn.responseCode != 200) return null
            val bytes = conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream(1 shl 20)
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_LIST_BYTES) return null
                }
                out.toByteArray()
            }
            val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
            if (text.startsWith("[Adblock Plus") && text.length > 10_000) text else null
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Whether a page on [pageUrl] may load [url]: [BLOCK], [ALLOW], or
     * [NO_ENGINE] before the engine is ready or where it cannot run.
     * [types] is what the request is for, or the things it might be (see
     * AdBlocker.requestTypes).
     */
    fun check(url: String, pageUrl: String, types: String): Int {
        if (!ready) return NO_ENGINE
        return try { nativeCheck(url, pageUrl, types) } catch (t: Throwable) { NO_ENGINE }
    }

    /** The element hiding for a page, as JSON (see AdBlocker.cosmetic), or null with no engine. */
    fun cosmetic(url: String): String? {
        if (!ready) return null
        return try { nativeCosmetic(url) } catch (t: Throwable) { null }
    }

    /** CSS hiding whatever the lists say about these classes and ids, or "". */
    fun hidden(classes: Array<String>, ids: Array<String>, exceptions: Array<String>): String {
        if (!ready) return ""
        return try { nativeHidden(classes, ids, exceptions) ?: "" } catch (t: Throwable) { "" }
    }

    @JvmStatic private external fun nativeLoad(data: ByteArray): Boolean
    @JvmStatic private external fun nativeBuild(lists: Array<String>): ByteArray?
    @JvmStatic private external fun nativeCheck(url: String, sourceUrl: String, types: String): Int
    @JvmStatic private external fun nativeCosmetic(url: String): String?
    @JvmStatic private external fun nativeHidden(classes: Array<String>, ids: Array<String>, exceptions: Array<String>): String?
}
