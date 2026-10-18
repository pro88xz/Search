package com.search.browser

import android.content.Context

/**
 * Page zoom remembered per site, as Chrome's Menu > Zoom keeps it: the one
 * site with tiny text gets its own size, and the rest keep the text size
 * picked in Settings > Accessibility. Keyed by the site's domain without
 * www. or m., so the mobile and desktop addresses share one size. A site at
 * the default size is not stored at all. Night Owl visits are never stored.
 */
object SiteZoom {

    private const val PREFS = "site_zoom"
    const val MIN = 50
    const val MAX = 300
    const val STEP = 10

    fun keyOf(url: String?): String? {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return null
        return SiteVisits.domainOf(url)
    }

    /** The size this site was given, or null for the default. */
    fun get(c: Context, url: String?): Int? {
        val key = keyOf(url) ?: return null
        val v = prefs(c).getInt(key, 0)
        return if (v in MIN..MAX) v else null
    }

    fun set(c: Context, url: String?, percent: Int) {
        val key = keyOf(url) ?: return
        prefs(c).edit().putInt(key, percent.coerceIn(MIN, MAX)).apply()
    }

    fun forget(c: Context, site: String) {
        prefs(c).edit().remove(site).apply()
    }

    /** Every site with its own size, by name. */
    fun all(c: Context): List<Pair<String, Int>> =
        prefs(c).all.mapNotNull { (k, v) -> (v as? Int)?.let { k to it } }.sortedBy { it.first }

    /** Changes whenever any site's size does, for MainActivity's settings signature. */
    fun signature(c: Context): Int = prefs(c).all.hashCode()

    fun clearAll(c: Context) {
        prefs(c).edit().clear().apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
