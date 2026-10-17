package com.search.browser

import android.content.Context
import org.json.JSONObject

/**
 * How much each site is used, for home's shortcut tiles: a visit adds one,
 * and every score halves over two weeks without a visit (frecency, as
 * Chrome's most-visited tiles use). The tiles used to be the eight most
 * recent sites, so a single visit to somewhere new pushed a daily favourite
 * off the row; now the sites used most stay put, and one that stops being
 * used fades out by itself.
 *
 * Searches, the app's own pages and Night Owl visits are never counted.
 * Synchronized: visits are added on MainActivity.historyIo, read on the
 * WebView bridge thread.
 */
object SiteVisits {

    private const val PREFS = "site_visits"
    private const val KEY = "sites"
    private const val HALF_LIFE_DAYS = 14.0
    private const val MAX_SITES = 300

    private class Site(var score: Double, var last: Long, var url: String)

    @Synchronized
    fun add(c: Context, url: String) {
        if (!(url.startsWith("https://") || url.startsWith("http://"))) return
        if (PageLabel.searchOf(url) != null) return
        val domain = domainOf(url) ?: return
        val now = System.currentTimeMillis()
        val all = load(c)
        val s = all[domain]
        if (s == null) all[domain] = Site(1.0, now, url)
        else {
            s.score = decayed(s, now) + 1.0
            s.last = now
            // The site's front page if that is what was visited; otherwise the
            // first page of it seen, so a tile does not jump between articles.
            if (isFrontPage(url) || !isFrontPage(s.url) && s.score < 1.5) s.url = url
        }
        if (all.size > MAX_SITES) {
            all.entries.sortedBy { decayed(it.value, now) }.take(all.size - MAX_SITES)
                .forEach { all.remove(it.key) }
        }
        save(c, all)
    }

    /** The [n] most used sites, best first, as (domain, url). */
    @Synchronized
    fun top(c: Context, n: Int): List<Pair<String, String>> {
        val now = System.currentTimeMillis()
        return load(c).entries.sortedByDescending { decayed(it.value, now) }
            .filter { decayed(it.value, now) >= 0.25 }
            .take(n).map { it.key to it.value.url }
    }

    /**
     * Takes back the visits made at or after [since]: a site last seen then
     * loses a visit, and one that had only that visit is forgotten.
     */
    @Synchronized
    fun forgetSince(c: Context, since: Long) {
        val all = load(c)
        val touched = all.filterValues { it.last >= since }
        if (touched.isEmpty()) return
        touched.forEach { (k, s) ->
            s.score -= 1.0
            if (s.score < 0.5) all.remove(k)
        }
        save(c, all)
    }

    @Synchronized
    fun clear(c: Context) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    private fun decayed(s: Site, now: Long): Double {
        val days = ((now - s.last).coerceAtLeast(0L)) / 86_400_000.0
        return s.score * Math.pow(0.5, days / HALF_LIFE_DAYS)
    }

    private fun isFrontPage(url: String): Boolean = try {
        val p = android.net.Uri.parse(url).path ?: ""
        p.isEmpty() || p == "/"
    } catch (e: Exception) { false }

    fun domainOf(url: String): String? = try {
        android.net.Uri.parse(url).host?.lowercase()?.removePrefix("www.")
            ?.removePrefix("mobile.")?.removePrefix("m.")?.takeIf { it.isNotBlank() }
    } catch (e: Exception) { null }

    private fun load(c: Context): MutableMap<String, Site> {
        val raw = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return HashMap()
        return try {
            val o = JSONObject(raw)
            val out = HashMap<String, Site>()
            o.keys().forEach { k ->
                val e = o.optJSONObject(k) ?: return@forEach
                out[k] = Site(e.optDouble("s", 1.0), e.optLong("t"), e.optString("u"))
            }
            out
        } catch (e: Exception) { HashMap() }
    }

    private fun save(c: Context, all: Map<String, Site>) {
        val o = JSONObject()
        all.forEach { (k, v) -> o.put(k, JSONObject().put("s", v.score).put("t", v.last).put("u", v.url)) }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, o.toString()).apply()
    }
}
