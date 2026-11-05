package com.search.browser

import org.json.JSONArray

/**
 * Where each search engine's own suggestions come from.
 *
 * Every engine but DuckDuckGo and Bing used to fall through to Google's
 * suggest server, so someone who chose Ecosia, Brave, Startpage, Yahoo or
 * Yandex still sent every keystroke to Google. These are the suggest URLs
 * Chrome itself uses for each engine, read from Chromium's built-in engine
 * list (libchrome.so of Chromium for Android 159, build 1735753). Brave's is
 * asked for without "rich=true" so it answers in the plain list format below.
 *
 * All of them answer in the OpenSearch suggestions format:
 * ["what was typed", ["suggestion", "suggestion", ...], ...]
 */
object SuggestEndpoints {

    fun url(engine: String, query: String, language: String): String {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val lang = java.net.URLEncoder.encode(language, "UTF-8")
        return when (engine) {
            "DuckDuckGo" -> "https://duckduckgo.com/ac/?q=$q&type=list"
            "Bing" -> "https://www.bing.com/osjson.aspx?query=$q&language=$lang"
            "Yahoo" -> "https://search.yahoo.com/sugg/chrome?output=fxjson&appid=crmas_sfp&command=$q"
            "Ecosia" -> "https://ac.ecosia.org/?q=$q&mkt=$lang&type=list&language=$lang"
            "Brave" -> "https://search.brave.com/api/suggest?q=$q"
            "Startpage" -> "https://www.startpage.com/osuggestions?q=$q"
            "Yandex" -> "https://suggest.yandex.com/suggest-ff.cgi?part=$q"
            else -> "https://www.google.com/complete/search?client=chrome&q=$q"
        }
    }

    /**
     * The suggestions in an OpenSearch answer. Google prefixes its answer with
     * ")]}'" against script inclusion. An entry that is an object rather than
     * a string (Brave's rich form) gives its "q".
     */
    fun parse(body: String): List<String> {
        val cleaned = body.trim().removePrefix(")]}'").trim()
        val list = JSONArray(cleaned).optJSONArray(1) ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val o = list.opt(i)
            when (o) {
                is String -> o
                is org.json.JSONObject -> o.optString("q").ifBlank { null }
                else -> null
            }
        }.filter { it.isNotBlank() }
    }
}
