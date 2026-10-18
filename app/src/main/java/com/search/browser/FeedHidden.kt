package com.search.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the reader asked home's feed not to show, from a story's menu:
 * single stories ("Not interested", by address) and whole publishers
 * ("Hide stories from ...", by domain). home.html reads it as stories arrive
 * (SearchApp.getFeedHidden) and leaves those out. Kept on the phone only;
 * the newest 300 stories are remembered, every publisher until shown again.
 */
object FeedHidden {

    private const val PREFS = "feed_hidden"
    private const val LINKS = "links"
    private const val SOURCES = "sources"
    private const val MAX_LINKS = 300

    @Synchronized
    fun hideLink(c: Context, link: String) {
        val list = load(c, LINKS).filter { it != link }.toMutableList()
        list.add(link)
        while (list.size > MAX_LINKS) list.removeAt(0)
        save(c, LINKS, list)
    }

    @Synchronized
    fun hideSource(c: Context, domain: String) {
        val list = load(c, SOURCES).filter { it != domain }.toMutableList()
        list.add(domain)
        save(c, SOURCES, list)
    }

    @Synchronized
    fun showSource(c: Context, domain: String) {
        save(c, SOURCES, load(c, SOURCES).filter { it != domain })
    }

    @Synchronized
    fun sources(c: Context): List<String> = load(c, SOURCES)

    /** For the page: {"links": [...], "sources": [...]}. */
    @Synchronized
    fun json(c: Context): String = JSONObject()
        .put(LINKS, JSONArray(load(c, LINKS)))
        .put(SOURCES, JSONArray(load(c, SOURCES)))
        .toString()

    @Synchronized
    fun clear(c: Context) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun load(c: Context, key: String): List<String> {
        val raw = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }
        } catch (e: Exception) { emptyList() }
    }

    private fun save(c: Context, key: String, list: List<String>) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(key, JSONArray(list).toString()).apply()
    }
}
