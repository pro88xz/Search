package com.search.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/** The agents Search shows sites (UserAgents): mobile without WebView's tokens, desktop at the real Chrome version. */
class UserAgentsTest {

    private val webView = "Mozilla/5.0 (Linux; Android 15; Pixel 9; wv) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Version/4.0 Chrome/153.0.8010.12 Mobile Safari/537.36"

    @Test fun mobileDropsWebViewTokens() {
        assertEquals("Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/153.0.8010.12 Mobile Safari/537.36",
            UserAgents.mobile(webView))
    }

    @Test fun desktopFollowsTheRealVersion() {
        assertEquals("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36",
            UserAgents.desktop(webView))
    }

    @Test fun desktopFallsBackWithoutAVersion() {
        assertEquals("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            UserAgents.desktop("Mozilla/5.0"))
    }
}
