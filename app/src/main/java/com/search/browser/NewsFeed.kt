package com.search.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches headlines from NewsData.io for the home feed.
 * Caching is two-tier: an in-memory cache with a TTL for cheap re-requests
 * within a session, and a persistent on-disk cache so the feed still shows
 * the last-loaded stories when the device is offline (even on a cold start).
 */
object NewsFeed {
    private const val API_KEY = "pub_b91e063edf15447f9a0002a483ede4c4"
    private const val ENDPOINT = "https://newsdata.io/api/1/latest"
    private const val TTL_MS = 15 * 60 * 1000L
    private const val CACHE_FILE = "feed_cache.json"

    @Volatile private var cache: String? = null
    @Volatile private var cachedAt: Long = 0L

    fun fetch(context: Context): String {
        val now = System.currentTimeMillis()
        val c = cache
        if (c != null && now - cachedAt < TTL_MS) return c

        return try {
            val url = URL(
                "$ENDPOINT?apikey=$API_KEY&language=en" +
                "&category=world,technology,science,business" +
                "&prioritydomain=top&image=1"
            )
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
            }
            val code = conn.responseCode
            if (code != 200) return cache ?: readDisk(context)
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val root = JSONObject(body)
            val results = root.optJSONArray("results") ?: JSONArray()
            val out = JSONArray()
            for (i in 0 until results.length()) {
                val a = results.optJSONObject(i) ?: continue
                val title = a.optString("title").trim()
                val link = a.optString("link").trim()
                val image = a.optString("image_url").trim()
                val source = a.optString("source_id").trim()
                if (title.isEmpty() || link.isEmpty()) continue
                val o = JSONObject()
                o.put("title", title)
                o.put("link", link)
                o.put("image", image)
                o.put("source", source)
                out.put(o)
            }
            val result = out.toString()
            // Only persist a non-empty result, so a bad response never wipes
            // a good cached feed.
            if (out.length() > 0) {
                cache = result
                cachedAt = now
                writeDisk(context, result)
            }
            if (out.length() > 0) result else (cache ?: readDisk(context))
        } catch (e: Exception) {
            // Offline or error: fall back to memory, then disk. Never blank if
            // we have anything cached.
            cache ?: readDisk(context)
        }
    }

    private fun writeDisk(context: Context, json: String) {
        try {
            File(context.filesDir, CACHE_FILE).writeText(json)
        } catch (_: Exception) { /* best effort */ }
    }

    private fun readDisk(context: Context): String {
        return try {
            val f = File(context.filesDir, CACHE_FILE)
            if (f.exists()) {
                val text = f.readText()
                // Warm the in-memory cache too so subsequent calls are instant.
                if (text.isNotBlank()) cache = text
                text.ifBlank { "[]" }
            } else "[]"
        } catch (_: Exception) { "[]" }
    }
}
