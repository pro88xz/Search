package com.search.browser

/**
 * The two faces Search shows websites, both made from WebView's own user
 * agent so the Chrome version in them is always the one actually running.
 *
 * Mobile: WebView's agent without the "; wv" token and the legacy
 * "Version/4.0 " token. Strict sites (Notion) read "Version/4.0" as an
 * ancient browser and refuse it; without both, Search reads as mobile Chrome.
 *
 * Desktop: Chrome's desktop agent on Linux with the reduced version
 * "<major>.0.0.0", as Chrome itself sends. It used to be a fixed "Chrome/120",
 * which sites began to treat as out of date as WebView moved on.
 */
object UserAgents {

    private val CHROME_MAJOR = Regex("Chrome/(\\d+)")

    /** The major Chrome version in [agent], or null when there is none. */
    fun chromeMajor(agent: String): String? = CHROME_MAJOR.find(agent)?.groupValues?.get(1)

    fun mobile(webViewDefault: String): String =
        webViewDefault.replace("; wv", "").replace("Version/4.0 ", "")

    fun desktop(webViewDefault: String): String {
        val major = chromeMajor(webViewDefault) ?: "120"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/" + major + ".0.0.0 Safari/537.36"
    }
}
