package com.search.browser

import android.content.Context

/**
 * App settings backed by SharedPreferences.
 * Central place for the default search engine, theme choice, etc.
 */
object Settings {

    private const val PREFS = "search_settings"
    private const val KEY_ENGINE = "search_engine"
    private const val KEY_THEME = "theme_mode"

    // Search engines: label -> query URL prefix
    val ENGINES = linkedMapOf(
        "Google" to "https://www.google.com/search?q=",
        "DuckDuckGo" to "https://duckduckgo.com/?q=",
        "Bing" to "https://www.bing.com/search?q=",
        "Yahoo" to "https://search.yahoo.com/search?p=",
        "Ecosia" to "https://www.ecosia.org/search?q=",
        "Brave" to "https://search.brave.com/search?q=",
        "Startpage" to "https://www.startpage.com/sp/search?query=",
        "Yandex" to "https://yandex.com/search/?text="
    )

    // Theme modes
    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    private fun prefs(c: Context) =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Domain per engine, for loading its favicon in settings.
    val ENGINE_DOMAINS = mapOf(
        "Google" to "google.com",
        "DuckDuckGo" to "duckduckgo.com",
        "Bing" to "bing.com",
        "Yahoo" to "search.yahoo.com",
        "Ecosia" to "ecosia.org",
        "Brave" to "search.brave.com",
        "Startpage" to "startpage.com",
        "Yandex" to "yandex.com"
    )

    fun getEngineName(c: Context): String =
        prefs(c).getString(KEY_ENGINE, "Google") ?: "Google"

    fun getEngineUrl(c: Context): String =
        ENGINES[getEngineName(c)] ?: ENGINES["Google"]!!

    fun setEngine(c: Context, name: String) {
        prefs(c).edit().putString(KEY_ENGINE, name).apply()
    }

    fun getTheme(c: Context): Int =
        prefs(c).getInt(KEY_THEME, THEME_SYSTEM)

    fun setTheme(c: Context, mode: Int) {
        prefs(c).edit().putInt(KEY_THEME, mode).apply()
    }

    /**
     * Hands the saved theme to AppCompat. Every screen calls this before its
     * super.onCreate, so the first frame is already in the chosen theme.
     *
     * MainActivity used to do it after super.onCreate. By then AppCompat had
     * created the activity in the phone's own mode, so on every launch where
     * the choice differed from the phone's, the browser was built, thrown
     * away and built again - a slow cold start - and a screen restored
     * straight from process death (Settings, a section) came back in the
     * phone's mode rather than the chosen one. setDefaultNightMode does
     * nothing when the mode is already set, so calling it often is free.
     */
    fun applyNightMode(c: Context) {
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            when (getTheme(c)) {
                THEME_LIGHT -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                THEME_DARK -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    // ---- Generic counters and timestamps ----
    fun getLong(c: Context, key: String, default: Long): Long =
        prefs(c).getLong(key, default)

    fun setLong(c: Context, key: String, value: Long) {
        prefs(c).edit().putLong(key, value).apply()
    }

    // ---- Generic strings ----
    fun getString(c: Context, key: String, default: String?): String? =
        prefs(c).getString(key, default)

    /** Written at once (commit) and reported, for a choice that must not be lost. */
    fun putStringNow(c: Context, key: String, value: String): Boolean =
        try { prefs(c).edit().putString(key, value).commit() } catch (e: Exception) { false }

    // ---- Generic boolean toggles (security, adblock, etc.) ----
    fun getBool(c: Context, key: String, default: Boolean): Boolean =
        prefs(c).getBoolean(key, default)

    fun setBool(c: Context, key: String, value: Boolean) {
        prefs(c).edit().putBoolean(key, value).apply()
    }

    // Security keys + defaults
    const val SEC_HTTPS_ONLY = "sec_https_only"       // default true
    const val IS_SUPPORTER = "is_supporter"           // set true after a support purchase
    const val GAMES_INTRO_SEEN = "games_intro_seen"   // set true after the games welcome screen is shown once
    const val SEC_SAFE_BROWSING = "sec_safe_browsing" // default true
    const val SEC_BLOCK_POPUPS = "sec_block_popups"   // default true
    const val SEC_BLOCK_3P_COOKIES = "sec_block_3p_cookies" // default false
    const val SEC_CONFIRM_DOWNLOADS = "sec_confirm_downloads" // default true

    const val SEARCH_SUGGESTIONS = "search_suggestions" // default true: the engine's suggestions as you type

    // Adblock
    const val ADBLOCK_ENABLED = "adblock_enabled"     // default false

    // Desktop mode
    const val DESKTOP_MODE = "desktop_mode"           // default false

    // Site settings (what websites are allowed to do)
    const val SITE_JAVASCRIPT = "site_javascript"      // default true
    const val SITE_LOCATION = "site_location"          // default true (ask)
    const val SITE_CAMERA_MIC = "site_camera_mic"      // default true (ask)
    const val SITE_BLOCK_AUTOPLAY = "site_block_autoplay" // default true
    const val SITE_BLOCK_IMAGES = "site_block_images"  // default false
    const val DATA_SAVER_MOBILE = "data_saver_mobile"  // default false: block images on mobile data (metered networks) only
    const val VIDEO_BUTTON = "video_button"            // default true: the Download video button on pages with a saveable video
    const val CLEAN_LINKS = "clean_links"              // default true: tracking tags off tapped, copied and shared links
    const val SEC_RISKY_DOWNLOADS = "sec_risky_downloads" // default true: a warning on app/program files and files sent without https
    const val SEC_LOOKALIKE = "sec_lookalike"          // default true: a warning on sites whose name uses look-alike letters (xn--)
    const val NIGHT_OWL_LOCK = "night_owl_lock"        // default false: Night Owl tabs need the screen lock after leaving the app
    const val NIGHT_OWL_SECURE = "night_owl_secure"    // default false: no screenshots, blank in recent apps, while Night Owl is on
    const val PULL_REFRESH = "pull_refresh"            // default true: pull a page down to reload it
    const val SWIPE_TABS = "swipe_tabs"                // default true: swipe the bottom bar to the next tab

    // Customize your Search (home page)
    const val HOME_BACKGROUND = "home_background"      // "clean" | "gradient" | "dark"
    const val HIDE_BARS_ON_SCROLL = "hide_bars_on_scroll" // default true
    const val TINT_BARS = "tint_bars"                  // default false: bars keep the app's surface; on, they take the site's colour
    const val HOME_ACCENT = "home_accent"              // hex string
    const val HOME_WALLPAPER = "home_wallpaper"        // a HomeWallpapers id, or "none" (the default)
    const val HOME_SHOW_TILES = "home_show_tiles"      // default true
    const val HOME_AFTER_AWAY = "home_after_away"      // default true: open on home after 4 hours away
    const val LEFT_AT = "left_at"                      // wall clock when the browser was last left
    const val HAPTICS = "haptics"                      // default true: a light vibration on bar and owl taps
    const val DARK_WEB = "dark_web_pages"              // default false: darken sites with no dark look of their own, in dark theme (Android 13+)

    fun getHomeBackground(c: Context): String =
        prefs(c).getString(HOME_BACKGROUND, "clean") ?: "clean"
    fun setHomeBackground(c: Context, v: String) { prefs(c).edit().putString(HOME_BACKGROUND, v).apply() }

    fun getHomeWallpaper(c: Context): String =
        prefs(c).getString(HOME_WALLPAPER, "none") ?: "none"
    fun setHomeWallpaper(c: Context, id: String) { prefs(c).edit().putString(HOME_WALLPAPER, id).apply() }

    fun getHomeAccent(c: Context): String =
        prefs(c).getString(HOME_ACCENT, "#8B6BD8") ?: "#8B6BD8"
    fun setHomeAccent(c: Context, v: String) { prefs(c).edit().putString(HOME_ACCENT, v).apply() }

    /** The accents offered in Settings > Customize: colour, name, theme overlay, ink. */
    class Accent(val hex: String, val name: String, val overlay: Int, val ink: Int)
    val ACCENTS = listOf(
        Accent("#2B6CF0", "Blue", R.style.ThemeOverlay_Search_Accent_Blue, R.color.accentBlue),
        Accent("#8B6BD8", "Purple", R.style.ThemeOverlay_Search_Accent_Purple, R.color.accentPurple),
        Accent("#2FB170", "Green", R.style.ThemeOverlay_Search_Accent_Green, R.color.accentGreen),
        Accent("#F5A623", "Amber", R.style.ThemeOverlay_Search_Accent_Amber, R.color.accentAmber),
        Accent("#E5556E", "Rose", R.style.ThemeOverlay_Search_Accent_Rose, R.color.accentRose),
        Accent("#0E0E10", "Ink", R.style.ThemeOverlay_Search_Accent_Ink, R.color.accentInk)
    )
    fun accentOf(c: Context): Accent {
        val hex = getHomeAccent(c)
        return ACCENTS.firstOrNull { it.hex.equals(hex, true) } ?: ACCENTS[1]
    }

    /**
     * Lays the chosen accent over the screen's theme, so its switches, radio
     * buttons, dialog buttons and text handles carry it. Called in onCreate
     * before any view is inflated.
     */
    fun applyAccent(a: android.app.Activity) {
        a.theme.applyStyle(accentOf(a).overlay, true)
    }

    /** The chosen accent as ink that reads on this theme's surfaces (deep on light, light on dark). */
    fun accentInk(c: Context): Int = c.getColor(accentOf(c).ink)

    // Accessibility
    const val A11Y_TEXT_SCALE = "a11y_text_scale"     // int percent, default 100
    const val A11Y_FORCE_ZOOM = "a11y_force_zoom"     // default false: pinch-zoom even where a site turns it off
    fun getTextScale(c: Context): Int = prefs(c).getInt(A11Y_TEXT_SCALE, 100)
    fun setTextScale(c: Context, pct: Int) { prefs(c).edit().putInt(A11Y_TEXT_SCALE, pct).apply() }
}
