package com.search.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Search keywords (Keywords): "w cats" offers Wikipedia, "ddg x" DuckDuckGo, and nothing else is taken over. */
class KeywordsTest {

    @Test fun sitesInThePhonesLanguage() {
        val m = Keywords.match("w cats and dogs", "fr")!!
        assertEquals("Wikipedia", m.name)
        assertEquals("cats and dogs", m.terms)
        assertEquals("https://fr.wikipedia.org/w/index.php?search=cats+and+dogs", m.url)
        assertEquals("https://www.youtube.com/results?search_query=lofi", Keywords.match("YT lofi", "en")!!.url)
    }

    @Test fun enginesByShortKeyOrName() {
        assertEquals("https://duckduckgo.com/?q=recipes", Keywords.match("ddg recipes", "en")!!.url)
        assertEquals("Ecosia", Keywords.match("ecosia trees", "en")!!.name)
        assertEquals("https://www.ecosia.org/search?q=trees", Keywords.match("ecosia trees", "en")!!.url)
    }

    @Test fun nothingWithoutTermsOrAKnownKey() {
        assertNull(Keywords.match("w", "en"))
        assertNull(Keywords.match("w ", "en"))
        assertNull(Keywords.match("cats", "en"))
        assertNull(Keywords.match("hotel paris", "en"))
    }

    @Test fun oddLanguageTagsFallBackSafely() {
        assertEquals("https://en.wikipedia.org/w/index.php?search=x", Keywords.match("w x", "")!!.url)
        assertEquals("https://pt.wikipedia.org/w/index.php?search=x", Keywords.match("w x", "PT")!!.url)
    }
}
