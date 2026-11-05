package com.search.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Each engine's own suggest server (SuggestEndpoints): nobody's keystrokes go to an engine they did not choose. */
class SuggestEndpointsTest {

    @Test fun eachEngineAsksItsOwnServer() {
        assertEquals("https://duckduckgo.com/ac/?q=a+b&type=list",
            SuggestEndpoints.url("DuckDuckGo", "a b", "en-US"))
        assertEquals("https://www.bing.com/osjson.aspx?query=a+b&language=en-US",
            SuggestEndpoints.url("Bing", "a b", "en-US"))
        assertEquals("https://search.yahoo.com/sugg/chrome?output=fxjson&appid=crmas_sfp&command=a+b",
            SuggestEndpoints.url("Yahoo", "a b", "en-US"))
        assertEquals("https://ac.ecosia.org/?q=a+b&mkt=en-US&type=list&language=en-US",
            SuggestEndpoints.url("Ecosia", "a b", "en-US"))
        assertEquals("https://search.brave.com/api/suggest?q=a+b",
            SuggestEndpoints.url("Brave", "a b", "en-US"))
        assertEquals("https://www.startpage.com/osuggestions?q=a+b",
            SuggestEndpoints.url("Startpage", "a b", "en-US"))
        assertEquals("https://suggest.yandex.com/suggest-ff.cgi?part=a+b",
            SuggestEndpoints.url("Yandex", "a b", "en-US"))
        assertEquals("https://www.google.com/complete/search?client=chrome&q=a+b",
            SuggestEndpoints.url("Google", "a b", "en-US"))
    }

    @Test fun onlyGoogleAsksGoogle() {
        Settings.ENGINES.keys.filter { it != "Google" }.forEach { engine ->
            assertFalse(engine, SuggestEndpoints.url(engine, "q", "en").contains("google."))
        }
    }

    @Test fun queryIsEncoded() {
        assertEquals("https://duckduckgo.com/ac/?q=c%2B%2B+%26+r%C3%A9sum%C3%A9&type=list",
            SuggestEndpoints.url("DuckDuckGo", "c++ & résumé", "en"))
    }
}
