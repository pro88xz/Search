package com.search.browser

import android.app.Activity
import android.view.ViewGroup

/** Release builds have no debug tools; see src/debug/DebugTools.kt. */
object DebugTools {
    @Suppress("UNUSED_PARAMETER")
    fun install(activity: Activity, rows: ViewGroup, liveTabs: () -> Int, beforeShare: () -> Unit) {
    }
}
