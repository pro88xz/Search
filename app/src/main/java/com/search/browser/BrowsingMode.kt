package com.search.browser

import android.content.Context

/**
 * Three ways to have the home page - Explore, Focus and Zen - over one
 * browser. A mode decides only what the home page holds and fetches; tabs,
 * bookmarks, history, downloads, privacy and security are the same in all
 * three, and changing mode clears nothing and closes nothing.
 *
 *   Explore  search, shortcuts, Jump back in and Top Stories (with the feed
 *            ad): the complete home page, and everyone's default.
 *   Focus    search, four shortcuts and Jump back in; no news is fetched.
 *   Zen      the search box alone (shortcuts only if asked for); nothing
 *            optional is drawn or fetched.
 *
 * One stored value, [KEY], read through [get] - anything missing or not one
 * of the three reads as Explore. The first-run introduction and the
 * one-time message to people upgrading are kept in keys of their own, so
 * neither can ever overwrite the mode itself.
 */
object BrowsingMode {

    const val EXPLORE = "explore"
    const val FOCUS = "focus"
    const val ZEN = "zen"
    val ALL = listOf(EXPLORE, FOCUS, ZEN)

    private const val KEY = "browsing_mode"
    private const val INTRO_DONE = "mode_intro_completed"
    private const val ANNOUNCEMENT_SEEN = "mode_announcement_seen"
    private const val SCHEMA = "mode_schema_version"
    private const val SCHEMA_NOW = 1L
    /** Zen with the user's first few shortcuts under the search box (off by default). */
    const val ZEN_SHORTCUTS = "zen_shortcuts"

    fun get(c: Context): String {
        val v = try { Settings.getString(c, KEY, null) } catch (e: Exception) { null }
        return if (v in ALL) v!! else EXPLORE
    }

    /** Saves the mode; false if it could not be written (the caller says so, nothing crashes). */
    fun set(c: Context, mode: String): Boolean =
        mode in ALL && Settings.putStringNow(c, KEY, mode)

    /**
     * Focus and Zen's home sits on the owl's wash (home.html, html.wash): the
     * brand's violet, lilac and amber across the top, and the pale indigo of
     * its night sky below. These are the same colours, for what the app paints
     * itself - the bars over that home page and the modes' previews - so page
     * and bar meet without a seam. Kept in step with home.html's --wash-*.
     */
    fun washTop(dark: Boolean): IntArray =
        if (dark) intArrayOf(0xFF2B1A5C.toInt(), 0xFF271B4A.toInt(), 0xFF3B2034.toInt())
        else intArrayOf(0xFFD4C2F7.toInt(), 0xFFE8DBF7.toInt(), 0xFFFBE2C6.toInt())

    fun washBottom(dark: Boolean): Int =
        if (dark) 0xFF150F33.toInt() else 0xFFE1DAFB.toInt()

    fun title(mode: String): String = when (mode) {
        FOCUS -> "Focus"
        ZEN -> "Zen"
        else -> "Explore"
    }

    fun tagline(mode: String): String = when (mode) {
        FOCUS -> "Keep your attention on the web."
        ZEN -> "Just search. Nothing extra."
        else -> "Discover more as you browse."
    }

    fun detail(mode: String): String = when (mode) {
        FOCUS -> "Search and favorite shortcuts, without the default news feed."
        ZEN -> "A calm, minimal homepage built around search."
        else -> "Search, shortcuts, and Top Stories in one place."
    }

    /**
     * Once per install, before anything else reads the mode. Someone
     * upgrading from a version without modes is put on Explore - the home
     * page they know - and told once that the others exist; a fresh install
     * gets the owl's introduction instead. A mode already saved is never
     * touched, by this or by any later update.
     */
    fun migrate(c: Context) {
        if (Settings.getLong(c, SCHEMA, 0L) >= SCHEMA_NOW) return
        val saved = try { Settings.getString(c, KEY, null) } catch (e: Exception) { null }
        if (isUpgrade(c)) {
            if (saved !in ALL) set(c, EXPLORE)
            Settings.setBool(c, INTRO_DONE, true)
            Settings.setBool(c, ANNOUNCEMENT_SEEN, false)
        } else {
            // A fresh install: the introduction will ask; nothing to announce.
            Settings.setBool(c, ANNOUNCEMENT_SEEN, true)
        }
        Settings.setLong(c, SCHEMA, SCHEMA_NOW)
    }

    /**
     * Installed before this version: Play updated it in place (its install
     * and update times differ), or it has already been used - left at least
     * once, or with history.
     */
    @Suppress("DEPRECATION")
    private fun isUpgrade(c: Context): Boolean {
        val updated = try {
            val pi = c.packageManager.getPackageInfo(c.packageName, 0)
            pi.lastUpdateTime - pi.firstInstallTime > 60_000L
        } catch (e: Exception) { false }
        val used = Settings.getLong(c, Settings.LEFT_AT, 0L) > 0L ||
            try { History.load(c).isNotEmpty() } catch (e: Exception) { false }
        return updated || used
    }

    fun introDone(c: Context): Boolean = Settings.getBool(c, INTRO_DONE, false)
    /** After a choice, or the introduction skipped or closed on purpose. */
    fun markIntroDone(c: Context) = Settings.setBool(c, INTRO_DONE, true)

    fun announcementDue(c: Context): Boolean = !Settings.getBool(c, ANNOUNCEMENT_SEEN, true)
    fun markAnnounced(c: Context) = Settings.setBool(c, ANNOUNCEMENT_SEEN, true)
}
