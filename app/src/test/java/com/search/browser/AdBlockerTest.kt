package com.search.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ad blocker's own decisions that need no WebView: what is left alone, and what a request is taken for. */
class AdBlockerTest {

    @Test fun youTubeIsLeftAlone() {
        listOf("youtube.com", "m.youtube.com", "music.youtube.com", "www.youtube-nocookie.com",
            "rr3---sn-q4fl6nsr.googlevideo.com", "i.ytimg.com", "youtu.be", "YouTube.com.")
            .forEach { assertTrue(it, AdBlocker.isLeftAlone(it)) }
        listOf("notyoutube.com", "youtube.com.evil.example", "google.com", "doubleclick.net", null)
            .forEach { assertFalse(it.toString(), AdBlocker.isLeftAlone(it)) }
    }

    @Test fun ownSitesAreNeverBlocked() {
        assertTrue(AdBlocker.isOwnSite("toolsepulse.co"))
        assertTrue(AdBlocker.isOwnSite("games.toolsepulse.co"))
        assertFalse(AdBlocker.isOwnSite("nottoolsepulse.co"))
    }

    @Test fun requestKinds() {
        fun t(path: String, method: String = "GET", vararg h: Pair<String, String>) =
            AdBlocker.requestTypes(path, method, mapOf(*h))
        assertEquals("script", t("/tag/js/gpt.js"))
        assertEquals("stylesheet", t("/a/site.css"))
        assertEquals("image", t("/pixel", "GET", "Accept" to "image/avif,image/webp,*/*"))
        assertEquals("sub_frame", t("/ad", "GET", "accept" to "text/html,application/xhtml+xml"))
        assertEquals("xmlhttprequest", t("/api/items", "GET", "Accept" to "application/json"))
        assertEquals("media", t("/videoplayback", "GET", "Range" to "bytes=0-"))
        assertEquals("script", t("/x", "GET", "Sec-Fetch-Dest" to "script"))
        assertEquals("xmlhttprequest", t("/x", "GET", "sec-fetch-dest" to "empty"))
        // A fetch and an extensionless script look alike: asked about as both.
        assertEquals("script,xmlhttprequest", t("/gtag/js"))
        assertEquals("xmlhttprequest,ping", t("/collect", "POST"))
    }
}
