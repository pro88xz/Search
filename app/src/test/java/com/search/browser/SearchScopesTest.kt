package com.search.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The @bookmarks, @history and @tabs shortcuts in search (SearchScopes). */
class SearchScopesTest {

    @Test fun parsesAScopedQuery() {
        val s = SearchScopes.parse("@tabs mail")!!
        assertEquals(SearchScopes.TABS, s.scope)
        assertEquals("mail", s.rest)
        assertEquals("", SearchScopes.parse("@History ")!!.rest)
        assertEquals(SearchScopes.HISTORY, SearchScopes.parse("@History ")!!.scope)
        assertEquals("two words", SearchScopes.parse("@bookmarks  two words ")!!.rest)
    }

    @Test fun anythingElseIsAnOrdinaryQuery() {
        assertNull(SearchScopes.parse("@tabs"))
        assertNull(SearchScopes.parse("@gmail is down"))
        assertNull(SearchScopes.parse("email @tabs x"))
        assertNull(SearchScopes.parse("tabs mail"))
    }

    @Test fun offersShortcutsWhileTheyAreBeingTyped() {
        assertEquals(3, SearchScopes.offers("@").size)
        assertEquals(listOf(SearchScopes.BOOKMARKS), SearchScopes.offers("@B").map { it.first })
        assertEquals(listOf(SearchScopes.TABS), SearchScopes.offers("@tabs").map { it.first })
        assertEquals(0, SearchScopes.offers("@tabs x").size)
        assertEquals(0, SearchScopes.offers("@x").size)
        assertEquals(0, SearchScopes.offers("hello").size)
    }
}
