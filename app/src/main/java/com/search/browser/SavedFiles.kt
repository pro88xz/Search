package com.search.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Files this app wrote itself, rather than handing to DownloadManager.
 *
 * A blob: or data: address is the page's own in-memory data, which no other
 * process can fetch - so those downloads are written directly and
 * DownloadManager never hears about them. Without a record of its own, the
 * file lands correctly in the Downloads folder while the app's own Downloads
 * list has no idea it exists, which reads to the user as the download having
 * quietly failed.
 */
object SavedFiles {

    private const val PREFS = "saved_files"
    private const val KEY = "entries"
    private const val MAX = 200

    data class Entry(
        val name: String,
        val uri: String,
        val mime: String,
        val size: Long,
        val time: Long
    )

    fun add(c: Context, name: String, uri: String, mime: String, size: Long) {
        val list = load(c).toMutableList()
        list.removeAll { it.uri == uri }
        list.add(0, Entry(name, uri, mime, size, System.currentTimeMillis()))
        while (list.size > MAX) list.removeAt(list.size - 1)
        save(c, list)
    }

    fun remove(c: Context, uri: String) {
        save(c, load(c).filter { it.uri != uri })
    }

    fun load(c: Context): List<Entry> {
        val raw = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(
                    o.getString("name"), o.getString("uri"), o.optString("mime"),
                    o.optLong("size"), o.optLong("time")
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun save(c: Context, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject().apply {
                put("name", e.name)
                put("uri", e.uri)
                put("mime", e.mime)
                put("size", e.size)
                put("time", e.time)
            })
        }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
