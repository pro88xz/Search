package com.search.browser

import java.net.URLDecoder

/**
 * What a visited page is called wherever a list shows it: the history and
 * bookmark lists, and the history rows among the search suggestions.
 *
 * Those lists used to show addresses. The second line of every history row
 * was the full URL, and a page that never set a title - or was saved before
 * it did - was stored under its address, so the first line was one too: a
 * wall of "https://m.youtube.com/watch?v=..." where a name should be. And a
 * search on any engine but the five that were recognised came back as its
 * results address rather than as the words that were searched.
 *
 * Now a search reads as what was searched, over the engine's name; a page
 * reads as its title over its site; and a page with no title of its own gets
 * one made from its address - the words of its slug, or its site.
 *
 * Plain Kotlin with no Android types, so it is tested on the JVM.
 */
object PageLabel {

    private class Engine(val host: String, val paths: List<String>, val params: List<String>, val name: String)

    /**
     * Results pages, by host fragment and path, with the parameter that holds
     * the query. The path keeps a Google sign-in redirect (/url?q=) or a maps
     * link (/maps?q=) from being taken for a search.
     */
    private val ENGINES = listOf(
        Engine("google.", listOf("/search"), listOf("q"), "Google"),
        Engine("bing.com", listOf("/search"), listOf("q"), "Bing"),
        Engine("duckduckgo.com", listOf("/", ""), listOf("q"), "DuckDuckGo"),
        Engine("search.yahoo.", listOf("/search"), listOf("p", "q"), "Yahoo"),
        Engine("ecosia.org", listOf("/search"), listOf("q"), "Ecosia"),
        Engine("startpage.com", listOf("/sp/search", "/do/search", "/search"), listOf("query", "q"), "Startpage"),
        Engine("search.brave.com", listOf("/search"), listOf("q"), "Brave"),
        Engine("yandex.", listOf("/search"), listOf("text"), "Yandex"),
        Engine("baidu.com", listOf("/s"), listOf("wd", "word"), "Baidu"),
        Engine("youtube.com", listOf("/results"), listOf("search_query"), "YouTube"),
        Engine("amazon.", listOf("/s"), listOf("k"), "Amazon")
    )

    /** The site an address belongs to, as people say it: "youtube.com". Empty when there is none. */
    fun siteOf(url: String): String {
        val rest = url.substringAfter("://", "")
        if (rest.isEmpty()) return ""
        return rest.substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@').substringBefore(':').lowercase()
            .removePrefix("www.").removePrefix("mobile.").removePrefix("m.")
    }

    /** The words searched for on a results page, with the engine's name, or null for any other page. */
    fun searchOf(url: String): Pair<String, String>? {
        val rest = url.substringAfter("://", "")
        if (rest.isEmpty()) return null
        val host = rest.substringBefore('/').substringBefore('?').substringBefore('#').lowercase()
        val afterHost = rest.substring(host.length)
        val path = afterHost.substringBefore('?').substringBefore('#')
        val engine = ENGINES.firstOrNull { e ->
            host.contains(e.host) && e.paths.any { p -> path == p || (p.length > 1 && path.startsWith("$p/")) }
        } ?: return null
        val query = afterHost.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return null
        val params = query.split('&').mapNotNull { kv ->
            val k = kv.substringBefore('=')
            val v = kv.substringAfter('=', "")
            if (k.isEmpty()) null else k to v
        }
        for (want in engine.params) {
            val raw = params.firstOrNull { it.first == want }?.second ?: continue
            val words = decode(raw).replace(Regex("\\s+"), " ").trim()
            if (words.isNotEmpty()) return words to engine.name
        }
        return null
    }

    /**
     * The first line for a page: the search it was, its own title, or - for a
     * page whose "title" is only its address - a name made from the address.
     */
    fun title(title: String, url: String): String {
        searchOf(url)?.let { return it.first }
        val t = title.replace(Regex("\\s+"), " ").trim()
        if (t.isNotEmpty() && !looksLikeAddress(t, url)) return t
        return slugTitle(url) ?: siteOf(url).ifEmpty { t.ifEmpty { url } }
    }

    /** The second line: "Google search" for a search, otherwise the site. */
    fun sub(url: String): String {
        searchOf(url)?.let { return it.second + " search" }
        return siteOf(url)
    }

    /**
     * Whether a stored title is really just the address. WebView reports the
     * address as the title of a page that has none, and the history kept
     * whatever it was given.
     */
    internal fun looksLikeAddress(t: String, url: String): Boolean {
        val lower = t.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return true
        val bare = url.substringAfter("://", url).lowercase()
        if (lower == url.lowercase() || lower == bare || lower == bare.trimEnd('/')) return true
        if (t.any { it.isWhitespace() }) return false
        val site = siteOf(url)
        return '/' in t || '?' in t || (site.isNotEmpty() && lower.removePrefix("www.").startsWith(site))
    }

    /**
     * "Palestine talks resume in Cairo" from ".../world/palestine-talks-resume-in-cairo-12345.html":
     * the last part of the path that is words joined by hyphens or
     * underscores. Null when the address has no such part - an id, a hash, a
     * bare site - and the site then names the page instead.
     */
    internal fun slugTitle(url: String): String? {
        val rest = url.substringAfter("://", "")
        if (rest.isEmpty()) return null
        val path = rest.substringAfter('/', "").substringBefore('?').substringBefore('#')
        val parts = path.split('/').filter { it.isNotEmpty() }.reversed()
        for (raw in parts) {
            val whole = decode(raw)
            // Only a known page extension is taken off; "v1.2" stays whole.
            val ext = whole.substringAfterLast('.', "").lowercase()
            val seg = if (ext in PAGE_EXTENSIONS) whole.substringBeforeLast('.') else whole
            val words = seg.split('-', '_', '+', ' ')
                .filter { it.isNotEmpty() }
                // Ids and dates are not words: "12345", "2026", "d4e5", "a1b2c3".
                .filterNot { w -> w.all { it.isDigit() } || (w.count { it.isDigit() } >= 2 && w.any { it.isLetter() }) }
            if (words.size < 2) continue
            val text = words.joinToString(" ")
            if (text.length < 8 || text.none { it.isLetter() }) continue
            return text.replaceFirstChar { it.uppercase() }
        }
        return null
    }

    private val PAGE_EXTENSIONS = setOf("html", "htm", "php", "asp", "aspx", "jsp", "shtml", "cms")

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: Exception) {
        s
    }
}
