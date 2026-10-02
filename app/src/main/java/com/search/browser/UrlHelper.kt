package com.search.browser

import android.net.Uri

object UrlHelper {

    private val PASSTHROUGH_SCHEMES = listOf(
        "http://", "https://", "about:", "file:", "data:",
        "chrome:", "content:", "ftp://"
    )

    // Deliberately NOT passed through: javascript:. Typed or pasted into the
    // address bar it runs in whatever page is open, which is how someone is
    // talked into pasting a line that reads their session back out. Every
    // mainstream browser refuses it from the address bar for this reason. It
    // falls through to search, where it is harmless.

    /**
     * The other way round from a list of real suffixes.
     *
     * There were about a hundred TLDs listed here and anything outside them was
     * sent to search - so .eu, .ac, .gg, .fm, .to, .ly and a thousand others
     * were unreachable by typing them. There are well over 1500 in use and the
     * set changes, so an allowlist is a list that is always wrong somewhere.
     *
     * The list existed to stop "index.php" and "main.py" being treated as
     * addresses, so that is what is listed instead: the handful of suffixes
     * that are overwhelmingly filenames when someone types them into a phone.
     * A few of these are also real TLDs now - .sh and .zip among them - and
     * they lose, because on a phone the filename reading is the likelier one.
     * Typing the full https:// address always wins over this guess.
     */
    private val FILE_SUFFIXES = setOf(
        "js", "mjs", "ts", "tsx", "jsx", "php", "py", "rb", "java", "kt", "kts",
        "html", "htm", "css", "scss", "json", "xml", "yml", "yaml", "toml",
        "txt", "md", "log", "csv", "sql", "sh", "bat", "ini", "cfg", "conf",
        "lock", "env", "gradle", "properties", "class", "jar", "exe", "dll",
        "zip", "tar", "gz", "apk", "iso", "dmg", "bak", "tmp"
    )

    private const val GOOGLE_SEARCH = "https://www.google.com/search?q="

    fun toUrlOrSearch(raw: String, searchPrefix: String = GOOGLE_SEARCH): String {
        val text = raw.trim()
        // Was a hardcoded Google search, ignoring the engine the user picked -
        // so an accidental Go on an empty bar landed on a blank Google page
        // even for someone who had chosen DuckDuckGo. Callers skip blank input
        // anyway; this only decides what an empty string means.
        if (text.isEmpty()) return searchPrefix

        val lower = text.lowercase()

        // 1. Known scheme -> load as-is
        if (PASSTHROUGH_SCHEMES.any { lower.startsWith(it) }) return text

        // 2. localhost (+ optional port/path)
        if (lower == "localhost" || lower.startsWith("localhost:") || lower.startsWith("localhost/")) {
            return "http://$text"
        }

        // 3. Raw IP (+ optional port/path)
        val beforePath = text.substringBefore("/")
        val hostForIp = if (beforePath.startsWith("[")) {
            // Bracketed IPv6, with any port after the bracket dropped.
            beforePath.substringBefore("]") + "]"
        } else {
            beforePath.substringBefore(":")
        }
        if (looksLikeIp(hostForIp)) return "http://$text"

        // 4. Spaces -> search
        if (text.contains(" ")) return search(text, searchPrefix)

        // 5. Domain with a *known* TLD -> load
        if (looksLikeDomain(text)) return "https://$text"

        // 6. Fallback -> search
        return search(text, searchPrefix)
    }

    /**
     * A bare IPv4 address, or a bracketed IPv6 one.
     *
     * android.util.Patterns.IP_ADDRESS is deprecated and only ever understood
     * IPv4, so "[::1]" was treated as a search term. Done directly here, which
     * is also cheaper than a regex on every keystroke.
     */
    private fun looksLikeIp(host: String): Boolean {
        if (host.startsWith("[") && host.endsWith("]")) {
            val inner = host.substring(1, host.length - 1)
            return inner.isNotEmpty() &&
                inner.all { it in "0123456789abcdefABCDEF:." }
        }
        val parts = host.split(".")
        if (parts.size != 4) return false
        return parts.all { p ->
            p.isNotEmpty() && p.length <= 3 &&
                p.all { it in '0'..'9' } && (p.toIntOrNull() ?: 999) <= 255
        }
    }

    private fun looksLikeDomain(text: String): Boolean {
        // Inspect only the host portion (strip path, query, port)
        val host = text.substringBefore("/").substringBefore("?").substringBefore(":")
        if (!host.contains(".")) return false
        if (host.startsWith(".") || host.endsWith(".")) return false

        val labels = host.split(".")
        if (labels.size < 2) return false
        if (labels.any { it.isEmpty() }) return false

        val tld = labels.last().lowercase()
        // A suffix that is letters only and a plausible length is a domain,
        // unless it is one of the few that reads as a filename.
        if (tld.length < 2 || tld.length > 24) return false
        if (!tld.all { it in 'a'..'z' }) return false
        return tld !in FILE_SUFFIXES
    }

    private fun search(query: String, prefix: String): String = prefix + Uri.encode(query)

    /** Query parameters that only an authorisation request or its answer carries. */
    private val SIGN_IN_PARAMS = setOf(
        "client_id", "redirect_uri", "response_type", "code_challenge",
        "oauth_token", "oauth_verifier", "openid.mode", "samlrequest",
        "samlresponse", "id_token", "access_token"
    )

    /**
     * Whether an address is part of a sign-in: an OAuth, OpenID or SAML
     * request to a provider (client_id, redirect_uri and the like), the
     * answer coming back (code with state, or tokens in the fragment), or
     * Firebase's auth handler page. This is the evidence that a pop-up is a
     * sign-in rather than an ordinary link that opened in a new tab.
     */
    fun looksLikeSignIn(url: String?): Boolean {
        if (url == null) return false
        val uri = try { Uri.parse(url) } catch (e: Exception) { return false }
        if (uri.isOpaque) return false
        val names = try {
            uri.queryParameterNames.map { it.lowercase() }
        } catch (e: Exception) { emptyList() }
        if (names.any { it in SIGN_IN_PARAMS }) return true
        if ("code" in names && "state" in names) return true
        val fragment = uri.encodedFragment?.lowercase() ?: ""
        if (fragment.contains("access_token=") || fragment.contains("id_token=")) return true
        if (fragment.contains("code=") && fragment.contains("state=")) return true
        return (uri.path ?: "").contains("/__/auth/handler")
    }

    /**
     * Second-level labels that sit under a country code as a registry of their
     * own: the "co" in example.co.uk. A full public-suffix list is a large,
     * ever-changing download; this covers how sign-in sites are actually
     * named, which is all [siteOf] is used for.
     */
    private val REGISTRY_LABELS = setOf(
        "co", "com", "net", "org", "gov", "edu", "ac", "gob", "gouv", "nic",
        "ne", "or", "go", "mil", "sch", "ltd", "plc"
    )

    /**
     * The site an address belongs to - its registrable domain, roughly:
     * "app.meshy.ai" and "www.meshy.ai" are both "meshy.ai", and
     * "accounts.google.co.uk" is "google.co.uk". Null for anything that is not
     * an http(s) page, and for bare IP addresses, which are their own site.
     */
    fun siteOf(url: String?): String? {
        if (url == null) return null
        val uri = try { Uri.parse(url) } catch (e: Exception) { return null }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase()?.removeSuffix(".") ?: return null
        if (host.isEmpty()) return null
        if (looksLikeIp(host) || !host.contains('.')) return host
        val labels = host.split('.')
        val keep = if (labels.size >= 3 && labels.last().length == 2 &&
            labels[labels.size - 2] in REGISTRY_LABELS
        ) 3 else 2
        return labels.takeLast(keep).joinToString(".")
    }
}
