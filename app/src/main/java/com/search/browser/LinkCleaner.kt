package com.search.browser

/**
 * Takes the tracking tags off a link: the utm_ campaign labels, Facebook's
 * and Instagram's click ids, newsletter tags. They identify where you came
 * from and who shared the link with you; the page is the same without them.
 *
 * Ad click ids (gclid, gbraid, wbraid, dclid, gad_source, msclkid...) are
 * left alone on purpose: they are how an advertiser counts that an ad worked,
 * and taking them off quietly breaks the ads that pay for free sites - and
 * for this browser.
 *
 * Used on a tapped link before it loads (Settings > Security > Remove
 * tracking from links) and on every link copied or shared. Kept free of
 * Android types so it can be tested on the JVM.
 */
object LinkCleaner {

    private val EXACT = setOf(
        "fbclid", "igshid", "igsh", "mc_cid", "mc_eid", "_hsenc", "_hsmi", "mkt_tok",
        "oly_anon_id", "oly_enc_id", "vero_id", "vero_conv", "__s", "rb_clickid",
        "s_cid", "wickedid", "_openstat"
    )

    private fun tracking(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("utm_") || n in EXACT
    }

    /**
     * [url] without its tracking tags, or null when it has none (or is not a
     * web address) - so the caller can tell nothing needs doing. The fragment
     * and every other parameter, in order and spelled as they were, stay.
     */
    fun clean(url: String): String? {
        if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) return null
        val hashAt = url.indexOf('#')
        val beforeHash = if (hashAt >= 0) url.substring(0, hashAt) else url
        val hash = if (hashAt >= 0) url.substring(hashAt) else ""
        val q = beforeHash.indexOf('?')
        if (q < 0) return null
        val base = beforeHash.substring(0, q)
        val parts = beforeHash.substring(q + 1).split('&')
        val kept = parts.filter { p ->
            if (p.isEmpty()) return@filter false
            val name = decode(p.substringBefore('='))
            !tracking(name)
        }
        if (kept.size == parts.count { it.isNotEmpty() }) return null
        return base + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + hash
    }

    private fun decode(s: String): String = try {
        java.net.URLDecoder.decode(s, "UTF-8")
    } catch (e: Exception) { s }
}
