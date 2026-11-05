package com.search.browser

/**
 * Chrome's built-in site search shortcuts ("starter pack"): typing @bookmarks,
 * @history or @tabs and a space searches only there, on the phone, and Enter
 * opens the best match rather than sending "@tabs mail" to the search engine.
 * A bare "@" (or the start of a shortcut) offers the shortcuts themselves.
 */
object SearchScopes {

    const val BOOKMARKS = "@bookmarks"
    const val HISTORY = "@history"
    const val TABS = "@tabs"

    /** Each shortcut and what it does, in the order offered. */
    val ALL = listOf(
        BOOKMARKS to "Search bookmarks",
        HISTORY to "Search history",
        TABS to "Search open tabs"
    )

    class Scoped(val scope: String, val rest: String)

    /** "@tabs mail" -> (@tabs, "mail"); "@TABS " -> (@tabs, ""); anything else null. */
    fun parse(query: String): Scoped? {
        val q = query.trimStart()
        if (!q.startsWith("@") || !q.contains(' ')) return null
        val word = q.substringBefore(' ').lowercase()
        val scope = ALL.firstOrNull { it.first == word }?.first ?: return null
        return Scoped(scope, q.substringAfter(' ').trim())
    }

    /** The shortcuts that what is typed could still become: "@" gives all three, "@b" gives @bookmarks. */
    fun offers(query: String): List<Pair<String, String>> {
        val q = query.trim().lowercase()
        if (!q.startsWith("@") || query.trimStart().contains(' ')) return emptyList()
        return ALL.filter { it.first.startsWith(q) }
    }
}
