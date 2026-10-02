package com.search.browser

import android.net.Uri

/**
 * What the browser did, kept in debug builds only, for the problems that only
 * happen on a real phone on a real site - a sign-in that stalls, a page that
 * hangs. The menu's "Share debug log" row sends it.
 *
 * Addresses are written without their query values or fragment: a sign-in
 * callback carries one-time codes and tokens there, and none of that belongs
 * in a log that is about to be sent to someone. The names of the query
 * parameters are kept, because which ones are present is often the clue.
 */
object DebugLog {

    private const val MAX_LINES = 800

    private val lines = ArrayDeque<String>()
    private val clock = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)

    val enabled: Boolean get() = BuildConfig.DEBUG

    /** Records one event. Safe from any thread; a no-op in release builds. */
    @Synchronized
    fun add(event: String) {
        if (!enabled) return
        lines.addLast(clock.format(java.util.Date()) + "  " + event)
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")

    /** scheme://host/path?name=&name=#, with every value left out. */
    fun url(u: String?): String {
        if (u == null) return "(none)"
        if (u.startsWith("data:", ignoreCase = true)) return "data:..."
        if (u.startsWith("blob:", ignoreCase = true)) return "blob:..."
        return try {
            val uri = Uri.parse(u)
            if (uri.isOpaque) return (uri.scheme ?: "") + ":..."
            val names = uri.queryParameterNames
            (uri.scheme ?: "") + "://" + (uri.host ?: "") +
                (if (uri.port > 0) ":" + uri.port else "") + (uri.path ?: "") +
                (if (names.isEmpty()) "" else names.joinToString("&", "?") { it + "=" }) +
                (if (uri.fragment.isNullOrEmpty()) "" else "#")
        } catch (e: Exception) {
            u.substringBefore('?').substringBefore('#').take(160)
        }
    }
}
