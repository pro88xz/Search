package com.search.browser

import android.content.Context

/**
 * Sites where the ad blocker stands aside: the per-site exceptions.
 *
 * Blocking follows the page the user is on, not the request. Allowing
 * example.com lets everything a page on example.com (or on any of its
 * subdomains) asks for through, whichever hosts that comes from, and leaves
 * blocking as it was everywhere else. That is the switch a site that breaks
 * with blocking on needs, and what a site the user wants to support asks for.
 *
 * Read on WebView's request threads for every request a page makes, so the
 * set is kept in memory and replaced whole on each change rather than read
 * from preferences each time.
 */
object AdBlockSites {

    private const val PREFS = "adblock_sites"
    private const val KEY = "allowed"

    @Volatile private var cache: Set<String>? = null

    /** "news.example.com" from "www.news.example.com.", lower case. */
    fun normalize(host: String?): String? {
        val h = host?.trim()?.lowercase()?.removeSuffix(".")?.removePrefix("www.")
        return if (h.isNullOrEmpty()) null else h
    }

    fun all(c: Context): Set<String> {
        cache?.let { return it }
        val loaded = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet())?.toSet() ?: emptySet()
        cache = loaded
        return loaded
    }

    /**
     * Whether ads are allowed on the page at [pageHost]: the host itself or a
     * site it belongs to is on the list.
     */
    fun isAllowed(c: Context, pageHost: String?): Boolean {
        val h = normalize(pageHost) ?: return false
        val sites = all(c)
        if (sites.isEmpty()) return false
        return sites.any { h == it || h.endsWith(".$it") }
    }

    fun allow(c: Context, host: String) {
        val h = normalize(host) ?: return
        save(c, all(c) + h)
    }

    /**
     * Blocking back on for [host]: the entry for it goes, and so does any
     * entry for a site it belongs to - otherwise "block ads here" on a
     * subdomain of an allowed site would quietly do nothing.
     */
    fun block(c: Context, host: String) {
        val h = normalize(host) ?: return
        save(c, all(c).filterNot { h == it || h.endsWith(".$it") }.toSet())
    }

    fun remove(c: Context, site: String) {
        save(c, all(c) - site)
    }

    private fun save(c: Context, sites: Set<String>) {
        cache = sites
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY, HashSet(sites)).apply()
    }
}
