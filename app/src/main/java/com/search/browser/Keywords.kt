package com.search.browser

/**
 * Search keywords, as Chrome's site search: "w cats" offers Wikipedia's search
 * for cats, "yt lofi" YouTube's, "ddg recipes" DuckDuckGo's. Offered as the
 * first suggestion only - Enter still searches as before - so a query that
 * happens to start with one of these words ("w hotels") is never taken over.
 */
object Keywords {

    class Match(val name: String, val url: String, val terms: String)

    private class Site(val name: String, val keys: List<String>, val template: (String) -> String)

    /** The sites besides the search engines; the template gets the phone's language. */
    private val SITES = listOf(
        Site("Wikipedia", listOf("w", "wiki", "wikipedia")) { lang ->
            "https://" + lang + ".wikipedia.org/w/index.php?search=%s"
        },
        Site("YouTube", listOf("yt", "youtube")) { _ -> "https://www.youtube.com/results?search_query=%s" },
        Site("Maps", listOf("maps")) { _ -> "https://www.google.com/maps/search/%s" }
    )

    /** Short keys for the engines in Settings; each engine's own name works too. */
    private val ENGINE_KEYS = mapOf(
        "g" to "Google", "ddg" to "DuckDuckGo", "b" to "Bing"
    )

    fun match(query: String, language: String): Match? {
        val q = query.trimStart()
        val space = q.indexOf(' ')
        if (space <= 0) return null
        val key = q.substring(0, space).lowercase()
        val terms = q.substring(space + 1).trim()
        if (terms.isEmpty()) return null
        val encoded = java.net.URLEncoder.encode(terms, "UTF-8")
        val lang = language.lowercase().filter { it in 'a'..'z' }.ifEmpty { "en" }
        SITES.firstOrNull { key in it.keys }?.let { site ->
            return Match(site.name, site.template(lang).replace("%s", encoded), terms)
        }
        val engine = ENGINE_KEYS[key]
            ?: Settings.ENGINES.keys.firstOrNull { it.lowercase() == key }
            ?: return null
        val prefix = Settings.ENGINES[engine] ?: return null
        return Match(engine, prefix + encoded, terms)
    }
}
