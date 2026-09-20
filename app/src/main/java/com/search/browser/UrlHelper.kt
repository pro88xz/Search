package com.search.browser

import android.net.Uri
import android.util.Patterns

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
        val hostForIp = text.substringBefore("/").substringBefore(":")
        if (Patterns.IP_ADDRESS.matcher(hostForIp).matches()) return "http://$text"

        // 4. Spaces -> search
        if (text.contains(" ")) return search(text, searchPrefix)

        // 5. Domain with a *known* TLD -> load
        if (looksLikeDomain(text)) return "https://$text"

        // 6. Fallback -> search
        return search(text, searchPrefix)
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
}
