package com.search.browser

import android.net.Uri

/**
 * What the browser did, for the problems that only happen on a real phone on a
 * real site - a sign-in that stalls, a page that hangs. The menu's "Share
 * debug log" row (DebugTools) sends it.
 *
 * Debug builds only. This file lives in src/debug; release builds compile
 * src/release/DebugLog.kt instead, whose add() is an empty inline function,
 * so no message is even built there and nothing of this ships.
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

    const val enabled = true

    /** Records one event. Safe from any thread. */
    inline fun add(event: () -> String) {
        record(event())
    }

    @PublishedApi
    @Synchronized
    internal fun record(event: String) {
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
