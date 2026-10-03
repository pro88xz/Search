package com.search.browser

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.view.LayoutInflater
import android.view.ViewGroup
import android.webkit.WebView

/**
 * Debug builds only (src/debug): the menu's "Share debug log" row. Release
 * builds compile src/release/DebugTools.kt, which adds nothing.
 */
object DebugTools {

    /**
     * Adds the row to the end of the menu. [beforeShare] closes the menu;
     * [liveTabs] is the live-tab budget, for the log's header.
     */
    fun install(activity: Activity, rows: ViewGroup, liveTabs: () -> Int, beforeShare: () -> Unit) {
        val row = LayoutInflater.from(activity).inflate(R.layout.debug_menu_row, rows, false)
        row.setOnClickListener {
            beforeShare()
            share(activity, liveTabs())
        }
        rows.addView(row)
    }

    private fun share(activity: Activity, liveTabs: Int) {
        val webView = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WebView.getCurrentWebViewPackage()?.let { it.packageName + " " + it.versionName }
        } else null
        val header = "Search " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")" +
            ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")" +
            ", " + Build.MANUFACTURER + " " + Build.MODEL +
            ", WebView " + (webView ?: "unknown") +
            ", live tabs " + liveTabs
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Search debug log")
            putExtra(Intent.EXTRA_TEXT, header + "\n\n" + DebugLog.text())
        }
        try {
            activity.startActivity(Intent.createChooser(send, "Share debug log"))
        } catch (e: Exception) {
            android.widget.Toast.makeText(activity, "Nothing on this phone can share text",
                android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
