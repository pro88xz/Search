package com.search.browser

/**
 * Release builds carry no debug log. This stands in for src/debug/DebugLog.kt
 * so the call sites compile: add() is inline and never runs its argument, so
 * every call compiles to nothing - no message is built, nothing is kept.
 */
object DebugLog {

    const val enabled = false

    @Suppress("UNUSED_PARAMETER")
    inline fun add(event: () -> String) {
    }

    @Suppress("UNUSED_PARAMETER")
    fun url(u: String?): String = ""
}
