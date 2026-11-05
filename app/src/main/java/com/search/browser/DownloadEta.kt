package com.search.browser

/**
 * "2 min left" for a running download, from what is left and its eased speed
 * (DownloadsAdapter). Rounded so it does not jitter between polls, and left
 * out where it would mean nothing: no size to go on, no speed yet, or more
 * than a day.
 */
object DownloadEta {

    /** The time left for [bytesLeft] at [bytesPerSecond], or "" when there is nothing honest to say. */
    fun text(bytesLeft: Long, bytesPerSecond: Double): String {
        if (bytesLeft <= 0L || bytesPerSecond <= 0.0) return ""
        val seconds = Math.ceil(bytesLeft / bytesPerSecond).toLong()
        return format(seconds)
    }

    internal fun format(seconds: Long): String = when {
        seconds <= 0L -> ""
        seconds < 10L -> "a few seconds left"
        // Up to 55 s in fives; from there it reads as the minute it rounds to.
        seconds < 55L -> "${(seconds + 4) / 5 * 5} s left"
        seconds < 3600L -> "${(seconds + 59) / 60} min left"
        seconds < 86_400L -> {
            val h = seconds / 3600
            val m = (seconds % 3600 + 59) / 60
            if (m == 0L || m == 60L) "${if (m == 60L) h + 1 else h} h left" else "$h h $m min left"
        }
        else -> ""
    }
}
