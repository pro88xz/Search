package com.search.browser

import android.content.Context
import android.os.Build

/**
 * The app's technical health, counted on the phone, per app version - so a
 * slower or less stable release shows up as numbers next to the one before
 * it, not as a feeling.
 *
 * Only counts and timings: how many sessions ended cleanly, crashes and
 * freezes, renderer crashes, failed page loads and downloads, start-up time,
 * memory warnings, and averages of PageMetrics. No address, page, search or
 * anything else about what was browsed is kept, and nothing is sent anywhere.
 * Settings > Performance & health shows it, and its Share button is the only
 * way it leaves the phone - by the user's hand.
 *
 * Where it comes from:
 * - A session is the app in the foreground, onResume to onPause. A flag set
 *   at the start and cleared at the end that is still set at the next launch
 *   means the session ended without onPause: a crash, a freeze the user or
 *   Android ended, or a kill while on screen. That is the crash-free figure.
 * - Java crashes are counted by a handler that then hands the crash on, so
 *   Android and anything else watching still see it.
 * - From Android 11, the system's own record of how the app's process ended
 *   (ActivityManager.getHistoricalProcessExitReasons) adds what the app
 *   cannot see itself: native crashes, ANRs, and kills for low memory.
 */
object HealthStats {

    private const val PREFS = "health"
    private const val KEY_VERSIONS = "versions"
    private const val KEY_RUNNING = "running_version"
    private const val KEY_EXITS_SEEN = "exits_seen_at"

    // Counters.
    const val SESSIONS = "sessions"
    const val UNCLEAN_EXITS = "unclean_exits"
    const val JAVA_CRASHES = "java_crashes"
    const val EXIT_CRASH = "exit_crash"
    const val EXIT_NATIVE_CRASH = "exit_native_crash"
    const val EXIT_ANR = "exit_anr"
    const val EXIT_LOW_MEMORY = "exit_low_memory"
    const val RENDERER_CRASH = "renderer_crash"
    const val RENDERER_KILLED = "renderer_killed"
    const val PAGE_UNRESPONSIVE = "page_unresponsive"
    const val PAGE_LOADS = "page_loads"
    const val PAGE_ERRORS = "page_errors"
    const val HTTP_ERRORS = "http_errors"
    const val DOWNLOADS = "downloads"
    const val DOWNLOAD_FAILURES = "download_failures"
    const val MEMORY_MODERATE = "memory_moderate"
    const val MEMORY_LOW = "memory_low"
    const val PAGES_LIGHT = "pages_light"
    const val PAGES_MEDIUM = "pages_medium"
    const val PAGES_HEAVY = "pages_heavy"
    // The feed ad (MainActivity.loadFeedAd): asked for, answered with an ad,
    // answered with an error, and the card actually on screen. Counts only.
    const val AD_REQUESTS = "ad_requests"
    const val AD_FILLED = "ad_filled"
    const val AD_FAILED = "ad_failed"
    const val AD_SEEN = "ad_seen"

    // Timings (milliseconds), kept as a sum and a count for an average.
    const val COLD_START = "cold_start"
    const val WARM_START = "warm_start"
    const val TTFB = "ttfb"
    const val FIRST_PAINT = "fcp"
    const val LARGEST_PAINT = "lcp"
    const val PAGE_LOAD = "load"

    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val version: Int get() = BuildConfig.VERSION_CODE
    private val versionName: String get() = BuildConfig.VERSION_NAME

    private fun key(v: Int, name: String) = "$v.$name"

    /** Notes this version as seen, so the report can compare it with the last one. */
    private fun noteVersion(c: Context) {
        val p = prefs(c)
        val seen = p.getString(KEY_VERSIONS, "").orEmpty()
        val list = seen.split(',').filter { it.isNotBlank() }
        val tag = "$version:$versionName"
        if (list.lastOrNull() != tag && list.none { it.startsWith("$version:") }) {
            // The last few only; older ones are dropped with their numbers.
            val kept = (list + tag).takeLast(4)
            val e = p.edit().putString(KEY_VERSIONS, kept.joinToString(","))
            list.filter { it !in kept }.forEach { gone ->
                val v = gone.substringBefore(':')
                p.all.keys.filter { it.startsWith("$v.") }.forEach { e.remove(it) }
            }
            e.apply()
        }
    }

    fun count(c: Context, name: String, by: Int = 1) {
        val p = prefs(c)
        val k = key(version, name)
        p.edit().putLong(k, p.getLong(k, 0L) + by).apply()
    }

    fun time(c: Context, name: String, ms: Double) {
        if (ms < 0 || ms > 600_000) return
        val p = prefs(c)
        val s = key(version, "$name.sum")
        val n = key(version, "$name.n")
        p.edit()
            .putLong(s, p.getLong(s, 0L) + ms.toLong())
            .putLong(n, p.getLong(n, 0L) + 1)
            .apply()
    }

    /** One loaded page's numbers (PageMetrics). */
    fun page(c: Context, m: PageMetrics) {
        time(c, TTFB, m.ttfbMs)
        time(c, FIRST_PAINT, m.firstPaintMs)
        time(c, LARGEST_PAINT, m.largestPaintMs)
        time(c, PAGE_LOAD, m.loadMs)
        count(c, when (m.weight) {
            PageMetrics.Weight.LIGHT -> PAGES_LIGHT
            PageMetrics.Weight.MEDIUM -> PAGES_MEDIUM
            PageMetrics.Weight.HEAVY -> PAGES_HEAVY
        })
    }

    /**
     * At launch: notes the version, counts a session the last one left
     * unfinished against the version that was running, reads Android's exit
     * records, and puts the crash handler in place.
     */
    fun onLaunch(c: Context) {
        val app = c.applicationContext
        noteVersion(app)
        val p = prefs(app)
        val running = p.getInt(KEY_RUNNING, -1)
        if (running >= 0) {
            val k = key(running, UNCLEAN_EXITS)
            p.edit().putLong(k, p.getLong(k, 0L) + 1).remove(KEY_RUNNING).apply()
        }
        readExitReasons(app)
        installCrashHandler(app)
    }

    private var coldClaimed = false

    /** True the first time it is asked in a process: that launch is the cold start. */
    fun claimColdStart(): Boolean {
        if (coldClaimed) return false
        coldClaimed = true
        return true
    }

    fun sessionStarted(c: Context) {
        count(c, SESSIONS)
        prefs(c).edit().putInt(KEY_RUNNING, version).apply()
    }

    fun sessionEnded(c: Context) {
        prefs(c).edit().remove(KEY_RUNNING).apply()
    }

    private var handlerInstalled = false

    private fun installCrashHandler(app: Context) {
        if (handlerInstalled) return
        handlerInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val p = prefs(app)
                val k = key(version, JAVA_CRASHES)
                // commit, not apply: the process is about to end.
                p.edit().putLong(k, p.getLong(k, 0L) + 1).commit()
            } catch (e: Throwable) {}
            if (previous != null) previous.uncaughtException(thread, error)
            else {
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(10)
            }
        }
    }

    /** Android 11+: how the app's process ended since this was last read. */
    private fun readExitReasons(app: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val p = prefs(app)
            val since = p.getLong(KEY_EXITS_SEEN, 0L)
            val exits = am.getHistoricalProcessExitReasons(app.packageName, 0, 20)
            var newest = since
            val e = p.edit()
            exits.filter { it.timestamp > since }.forEach { info ->
                newest = maxOf(newest, info.timestamp)
                val name = when (info.reason) {
                    android.app.ApplicationExitInfo.REASON_CRASH -> EXIT_CRASH
                    android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> EXIT_NATIVE_CRASH
                    android.app.ApplicationExitInfo.REASON_ANR -> EXIT_ANR
                    android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> EXIT_LOW_MEMORY
                    else -> null
                } ?: return@forEach
                // Counted against the version running now: the record does not
                // carry one, and an exit is read at the very next launch.
                val k = key(version, name)
                e.putLong(k, p.getLong(k, 0L) + 1)
            }
            // The first run only marks where reading starts; what happened
            // before this existed is not counted.
            e.putLong(KEY_EXITS_SEEN, if (since == 0L) System.currentTimeMillis() else newest)
            if (since == 0L) {
                listOf(EXIT_CRASH, EXIT_NATIVE_CRASH, EXIT_ANR, EXIT_LOW_MEMORY)
                    .forEach { e.remove(key(version, it)) }
            }
            e.apply()
        } catch (e: Throwable) { /* not available on this phone */ }
    }

    // ---- The report ----

    class Version(val code: Int, val name: String, val values: Map<String, Long>) {
        fun n(k: String) = values[k] ?: 0L
        fun avg(k: String): Long? {
            val n = values["$k.n"] ?: 0L
            return if (n == 0L) null else (values["$k.sum"] ?: 0L) / n
        }
    }

    /** This version first, then the ones before it, newest first. */
    fun versions(c: Context): List<Version> {
        val p = prefs(c)
        val all = p.all
        return p.getString(KEY_VERSIONS, "").orEmpty().split(',')
            .filter { it.isNotBlank() }.reversed()
            .map { tag ->
                val code = tag.substringBefore(':').toIntOrNull() ?: -1
                val name = tag.substringAfter(':', "")
                val prefix = "$code."
                val values = all.filterKeys { it.startsWith(prefix) }
                    .mapKeys { it.key.removePrefix(prefix) }
                    .mapValues { (it.value as? Long) ?: 0L }
                Version(code, name, values)
            }
    }

    /** The numbers for one version, as lines of text. */
    fun lines(v: Version): List<String> {
        val out = mutableListOf<String>()
        val sessions = v.n(SESSIONS)
        val unclean = v.n(UNCLEAN_EXITS)
        out += if (sessions > 0) {
            val clean = ((sessions - unclean).coerceAtLeast(0) * 1000 / sessions) / 10.0
            "Crash-free sessions: $clean% of $sessions"
        } else "Crash-free sessions: no sessions yet"
        out += "Crashes: " + (v.n(JAVA_CRASHES) + v.n(EXIT_NATIVE_CRASH)) +
            "   Not responding (ANR): " + v.n(EXIT_ANR) +
            "   Closed for memory: " + v.n(EXIT_LOW_MEMORY)
        out += "Page engine crashes: " + v.n(RENDERER_CRASH) +
            "   reclaimed: " + v.n(RENDERER_KILLED) +
            "   pages stuck: " + v.n(PAGE_UNRESPONSIVE)
        val loads = v.n(PAGE_LOADS)
        out += "Pages loaded: $loads   failed: " + v.n(PAGE_ERRORS) +
            "   HTTP errors: " + v.n(HTTP_ERRORS)
        out += "Downloads: " + v.n(DOWNLOADS) + "   failed: " + v.n(DOWNLOAD_FAILURES)
        out += "Start-up: cold " + ms(v.avg(COLD_START)) + ", warm " + ms(v.avg(WARM_START))
        out += "Pages: first byte " + ms(v.avg(TTFB)) + ", first paint " +
            ms(v.avg(FIRST_PAINT)) + ", largest paint " + ms(v.avg(LARGEST_PAINT)) +
            ", loaded " + ms(v.avg(PAGE_LOAD))
        out += "Page weight: light " + v.n(PAGES_LIGHT) + ", medium " +
            v.n(PAGES_MEDIUM) + ", heavy " + v.n(PAGES_HEAVY)
        out += "Memory warnings: getting short " + v.n(MEMORY_MODERATE) +
            ", low " + v.n(MEMORY_LOW)
        out += "Feed ad: asked " + v.n(AD_REQUESTS) + ", filled " + v.n(AD_FILLED) +
            ", failed " + v.n(AD_FAILED) + ", on screen " + v.n(AD_SEEN)
        return out
    }

    // ---------- Why the feed ad did not show ----------
    //
    // The last answer AdMob gave instead of an ad, kept so a release build
    // can say why the card is missing (Settings > Performance & health): in a
    // release build there is no log to read, and "no ad" looks the same
    // whether the unit has no fill yet or the request is wrong.

    private const val KEY_AD_ERROR = "ad_last_error"
    private const val KEY_AD_ERROR_AT = "ad_last_error_at"
    private const val KEY_AD_GATE = "ad_last_gate"

    fun noteAdError(c: Context, code: Int, message: String?) {
        prefs(c).edit()
            .putString(KEY_AD_ERROR, code.toString() + "|" + (message ?: "").take(160))
            .putLong(KEY_AD_ERROR_AT, System.currentTimeMillis())
            .apply()
    }

    /** Why no request was made, when one was not (consent, supporter, Night Owl). */
    fun noteAdGate(c: Context, why: String) {
        prefs(c).edit().putString(KEY_AD_GATE, why).apply()
    }

    /** Plain lines about the feed ad's last failure and what it means, or none. */
    fun adLines(c: Context): List<String> {
        val p = prefs(c)
        val out = mutableListOf<String>()
        // A supporter purchase turns the ad off for good on this phone - a
        // test purchase made while setting the store up included - so it is
        // the first thing to rule out when the card never shows.
        if (Settings.getBool(c, Settings.IS_SUPPORTER, false)) {
            out += "Supporter: yes - the feed ad is off on this phone"
        }
        p.getString(KEY_AD_GATE, null)?.let { out += "Last time no ad was asked for: $it" }
        val raw = p.getString(KEY_AD_ERROR, null) ?: return out
        val code = raw.substringBefore('|').toIntOrNull() ?: -1
        val msg = raw.substringAfter('|', "")
        val at = p.getLong(KEY_AD_ERROR_AT, 0L)
        val ago = if (at > 0) " (" + ((System.currentTimeMillis() - at) / 60_000L).coerceAtLeast(0) + " min ago)" else ""
        out += "Last ad error: code $code - " + adErrorMeaning(code) + ago
        if (msg.isNotBlank()) out += "AdMob said: $msg"
        return out
    }

    /** What an AdMob LoadAdError code means, and what to do about it. */
    fun adErrorMeaning(code: Int): String = when (code) {
        0 -> "internal error on Google's side; it usually passes"
        1 -> "invalid request: check the ad unit ID and the app ID in AdMob match this app"
        2 -> "network error: no connection when the ad was asked for"
        3 -> "no fill: AdMob had no ad to give (normal for a new unit or an app still in review)"
        8 -> "the app ID is missing or wrong in the manifest"
        9 -> "no fill from mediation"
        10 -> "request ID mismatch"
        11 -> "invalid ad string"
        else -> "unknown"
    }

    private fun ms(v: Long?): String = if (v == null) "-" else "$v ms"

    /** The whole report, for sharing. */
    fun report(c: Context): String {
        val sb = StringBuilder("Search - performance & health\n")
        sb.append("Android ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append("), ")
            .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        versions(c).forEach { v ->
            sb.append("\nVersion ").append(v.name).append(" (").append(v.code).append(")\n")
            lines(v).forEach { sb.append("  ").append(it).append('\n') }
        }
        val ads = adLines(c)
        if (ads.isNotEmpty()) {
            sb.append("\nFeed ad\n")
            ads.forEach { sb.append("  ").append(it).append('\n') }
        }
        return sb.toString()
    }
}
