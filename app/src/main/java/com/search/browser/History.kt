package com.search.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Simple visited-page history backed by SharedPreferences (JSON).
 * Keeps the most recent [MAX] entries, newest first, de-duplicated by URL.
 */
object History {

    private const val PREFS = "search_history"
    private const val KEY = "entries"
    private const val MAX = 200

    data class Entry(val title: String, val url: String, val time: Long)

    fun add(context: Context, title: String, url: String) {
        // Skip blanks and every page shipped inside the app. Only home.html was
        // skipped before, so a failed load wrote the error page into history -
        // and with the failed address baked into its query string, the history
        // list filled up with entries nobody visited and nobody can revisit.
        if (url.isBlank() || url == "about:blank" ||
            url.startsWith("file:///android_asset/")
        ) return

        val list = load(context).toMutableList()
        // Remove any existing entry with the same URL (so it moves to top).
        list.removeAll { it.url == url }
        list.add(0, Entry(title.ifBlank { url }, url, System.currentTimeMillis()))
        while (list.size > MAX) list.removeAt(list.size - 1)
        save(context, list)
    }

    fun load(context: Context): List<Entry> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(o.getString("title"), o.getString("url"), o.getLong("time"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun delete(context: Context, url: String) {
        val list = load(context).toMutableList()
        list.removeAll { it.url == url }
        save(context, list)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }

    private fun save(context: Context, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject().apply {
                put("title", e.title)
                put("url", e.url)
                put("time", e.time)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
