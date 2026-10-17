package com.search.browser

import android.content.Context

/**
 * How many ads and trackers the blocker has stopped, for home's "blocked this
 * week" line and Settings > Ad blocking: counted per day for the last seven
 * days, on the phone only.
 *
 * Counted where blocking happens (AdBlocker.check), on WebView's request
 * threads, so only an atomic counter is touched there; it is written to
 * SharedPreferences when the app is left (flush), never per request.
 */
object PrivacyStats {

    private const val PREFS = "privacy_stats"
    private const val DAYS = 7
    /** A rough average for a blocked ad or tracker request, for "about N MB saved". */
    private const val BYTES_PER_BLOCK = 48L * 1024

    private val pending = java.util.concurrent.atomic.AtomicLong(0L)

    fun blocked() { pending.incrementAndGet() }

    private fun today(): Long = System.currentTimeMillis() / 86_400_000L

    /** Writes what was counted since the last flush into today's bucket, and drops buckets older than a week. */
    fun flush(c: Context) {
        val n = pending.getAndSet(0L)
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val day = today()
        val e = p.edit()
        if (n > 0) {
            val k = "d$day"
            e.putLong(k, p.getLong(k, 0L) + n)
            e.putLong("total", p.getLong("total", 0L) + n)
        }
        p.all.keys.filter { it.startsWith("d") }.forEach { k ->
            val d = k.substring(1).toLongOrNull() ?: return@forEach
            if (day - d >= DAYS) e.remove(k)
        }
        e.apply()
    }

    /** Blocked in the last seven days, including what has not been written yet. */
    fun week(c: Context): Long {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val day = today()
        var sum = pending.get()
        for (i in 0 until DAYS) sum += p.getLong("d" + (day - i), 0L)
        return sum
    }

    fun total(c: Context): Long =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("total", 0L) + pending.get()

    /** About how much data that saved, in bytes. */
    fun bytesSaved(count: Long): Long = count * BYTES_PER_BLOCK

    fun clear(c: Context) {
        pending.set(0L)
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
