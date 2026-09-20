package com.search.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The home page's quick-access tiles, as the user has arranged them.
 *
 * Two lists, because a tile can be there for two different reasons and each
 * needs its own kind of memory:
 *
 *  - [custom] is what the user added by hand, in the order they added it.
 *  - [hidden] is what they removed. Without it, deleting a built-in shortcut
 *    or a site from history would last exactly until the next render, because
 *    both of those are generated rather than stored.
 *
 * Hidden is keyed by domain rather than url: a site from history arrives with
 * whatever page happened to be visited, but the tile is the site.
 */
object Shortcuts {

    private const val PREFS = "search_shortcuts"
    private const val KEY_CUSTOM = "custom"
    private const val KEY_HIDDEN = "hidden"

    data class Entry(val label: String, val url: String)

    fun domainOf(url: String): String = try {
        (android.net.Uri.parse(url).host ?: "")
            .removePrefix("www.").removePrefix("mobile.").removePrefix("m.")
            .lowercase()
    } catch (e: Exception) { "" }

    // ---- custom ----------------------------------------------------------

    fun loadCustom(c: Context): List<Entry> {
        val raw = prefs(c).getString(KEY_CUSTOM, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val u = o.optString("url")
                if (u.isBlank()) null else Entry(o.optString("label"), u)
            }
        } catch (e: Exception) { emptyList() }
    }

    fun addCustom(c: Context, label: String, url: String) {
        if (url.isBlank()) return
        val dom = domainOf(url)
        val list = loadCustom(c).toMutableList()
        // Same site twice is not two shortcuts.
        list.removeAll { domainOf(it.url) == dom }
        list.add(Entry(label.ifBlank { dom }, url))
        saveCustom(c, list)
        // Adding something back is an undo of having removed it.
        unhide(c, dom)
    }

    fun removeCustom(c: Context, url: String) {
        val dom = domainOf(url)
        saveCustom(c, loadCustom(c).filterNot { domainOf(it.url) == dom })
    }

    private fun saveCustom(c: Context, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("label", it.label).put("url", it.url)) }
        prefs(c).edit().putString(KEY_CUSTOM, arr.toString()).apply()
    }

    // ---- hidden ----------------------------------------------------------

    fun loadHidden(c: Context): List<String> {
        val raw = prefs(c).getString(KEY_HIDDEN, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } }
        } catch (e: Exception) { emptyList() }
    }

    fun hide(c: Context, domain: String) {
        if (domain.isBlank()) return
        val set = loadHidden(c).toMutableList()
        if (set.contains(domain)) return
        set.add(domain)
        saveHidden(c, set)
    }

    private fun unhide(c: Context, domain: String) {
        saveHidden(c, loadHidden(c).filterNot { it == domain })
    }

    private fun saveHidden(c: Context, list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs(c).edit().putString(KEY_HIDDEN, arr.toString()).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
