package com.search.browser

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * "Search" in the text-selection menu of every other app (Android's
 * PROCESS_TEXT): the words picked in a mail, a chat or a note open as a search
 * in Search. Draws nothing and is gone at once - it only hands the text to
 * the browser (MainActivity is singleTask, and PROCESS_TEXT is started for a
 * result, so it cannot take the intent itself). Also answers a plain
 * WEB_SEARCH from the system or another app.
 */
class ProcessTextActivity : Activity() {

    companion object {
        const val EXTRA_FROM_SELF = "com.search.browser.FROM_SELF"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = when (intent?.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            Intent.ACTION_WEB_SEARCH -> intent.getStringExtra(android.app.SearchManager.QUERY)
            else -> null
        }?.trim()?.take(2000)
        if (!text.isNullOrEmpty()) {
            // Picked in a page inside Search itself (WebView lists this
            // activity in its own selection menu): a search in a new tab,
            // and Back from it stays in the browser.
            val fromSelf = callingPackage == packageName
            try {
                startActivity(Intent(this, MainActivity::class.java)
                    .setAction(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, text)
                    .putExtra(EXTRA_FROM_SELF, fromSelf)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) { /* nothing to open it in */ }
        }
        finish()
    }
}
