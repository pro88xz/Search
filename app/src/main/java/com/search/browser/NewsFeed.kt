package com.search.browser

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches headlines from NewsData.io for the home feed.
 * Results are cached in memory with a TTL so the home page can re-request
 * cheaply without burning the daily quota.
 */
object NewsFeed {
    private const val API_KEY = "pub_b91e063edf15447f9a0002a483ede4c4"
    private const val ENDPOINT = "https://newsdata.io/api/1/latest"
    private const val TTL_MS = 15 * 60 * 1000L

    @Volatile private var cache: String? = null
    @Volatile private var cachedAt: Long = 0L

    fun fetch(): String {
        val now = System.currentTimeMillis()
        val c = cache
        if (c != null && now - cachedAt < TTL_MS) return c

        return try {
            // Global-interest feed: top-10% sources only (prioritydomain=top)
            // across quality categories, English, image required. This filters
            // out hyper-local noise (town-council items, local crime) in favor
            // of world/tech/science/business stories anyone would care about.
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
            if (code != 200) return cache ?: "[]"
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
            cache = result
            cachedAt = now
            result
        } catch (e: Exception) {
            cache ?: "[]"
        }
    }
}
