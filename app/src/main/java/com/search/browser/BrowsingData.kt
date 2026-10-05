package com.search.browser

import android.content.Context
import java.io.File

/**
 * What the browser keeps on the phone, measured and cleared one kind at a
 * time - so clearing the cache to free space does not also sign you out of
 * every site, as the single "Clear browsing data" button used to.
 *
 * Sizes are read from WebView's own folders. Its layout is Chromium's and not
 * a public contract, so the numbers are a close estimate, not an audit; the
 * clearing goes through WebView's real APIs either way.
 */
object BrowsingData {

    /** Intent extra asking MainActivity to open one of its screens. */
    const val EXTRA_OPEN = "com.search.browser.OPEN"
    const val OPEN_DOWNLOADS = "downloads"

    class Sizes(
        val cache: Long,
        val cookies: Long,
        val siteStorage: Long,
        val history: Long,
        val downloads: Long
    )

    private fun webViewDir(c: Context) = File(c.applicationInfo.dataDir, "app_webview")

    private fun dirSize(f: File?): Long {
        if (f == null || !f.exists()) return 0L
        if (f.isFile) return f.length()
        var total = 0L
        f.listFiles()?.forEach { total += dirSize(it) }
        return total
    }

    private val CACHE_NAMES = setOf("Cache", "Code Cache", "GPUCache", "DawnCache",
        "DawnGraphiteCache", "DawnWebGPUCache", "GrShaderCache", "ShaderCache")

    private fun isCookieFile(f: File) = f.isFile && f.name.startsWith("Cookies")

    /** Reads every size. Walks folders and asks DownloadManager: not on the main thread. */
    fun measure(c: Context): Sizes {
        val app = c.applicationContext
        val wv = webViewDir(app)
        val profiles = listOf(wv, File(wv, "Default"))

        var cache = dirSize(File(app.cacheDir, "WebView")) +
            dirSize(File(app.cacheDir, "org.chromium.android_webview"))
        var cookies = 0L
        profiles.forEach { dir ->
            dir.listFiles()?.forEach { f ->
                if (f.isDirectory && f.name in CACHE_NAMES) cache += dirSize(f)
                if (isCookieFile(f)) cookies += f.length()
            }
        }
        // Everything else in WebView's folder is what sites stored: local and
        // session storage, IndexedDB, service workers and their caches.
        val siteStorage = (dirSize(wv) - cookies -
            profiles.sumOf { dir ->
                dir.listFiles()?.filter { it.isDirectory && it.name in CACHE_NAMES }
                    ?.sumOf { dirSize(it) } ?: 0L
            }).coerceAtLeast(0L)

        val prefs = File(app.applicationInfo.dataDir, "shared_prefs")
        val history = dirSize(File(prefs, "search_history.xml")) +
            dirSize(File(prefs, "favicon_cache.xml"))

        val downloads = try {
            Downloads.load(app).sumOf { it.bytesSoFar.coerceAtLeast(0L) }
        } catch (e: Exception) { 0L }

        return Sizes(cache, cookies, siteStorage, history, downloads)
    }

    /**
     * Cached files. clearCache empties the cache shared by the whole app, so a
     * throwaway WebView reaches it from anywhere. Main thread.
     */
    fun clearCache(c: Context) {
        try {
            val w = android.webkit.WebView(c)
            w.clearCache(true)
            w.destroy()
        } catch (e: Exception) { /* WebView unavailable */ }
    }

    /**
     * Cookies, and with them every site sign-in. The removal is WebView's own
     * asynchronous one; the flush that puts it on disk blocks its caller, so it
     * runs on a thread of its own rather than the main one.
     */
    fun clearCookies() {
        try {
            val cookies = android.webkit.CookieManager.getInstance()
            cookies.removeAllCookies {
                Thread { try { cookies.flush() } catch (e: Exception) {} }.start()
            }
        } catch (e: Exception) { /* nothing stored */ }
    }

    /** What sites stored: local storage, IndexedDB, service workers and the like. */
    fun clearSiteStorage() {
        try {
            android.webkit.WebStorage.getInstance().deleteAllData()
        } catch (e: Exception) { /* nothing stored */ }
    }

    /** The history list, and the site icons kept for it. */
    fun clearHistory(c: Context) {
        History.clear(c)
        c.getSharedPreferences("favicon_cache", Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** Everything a browser accumulates about where you have been. Bookmarks stay. */
    fun clearAll(c: Context) {
        clearHistory(c)
        try {
            val cookies = android.webkit.CookieManager.getInstance()
            cookies.removeAllCookies(null)
            cookies.flush()
        } catch (e: Exception) { /* nothing stored */ }
        clearSiteStorage()
        try {
            // clearFormData is deprecated and does nothing: WebView stopped
            // storing form data in API 26. Saved http-auth credentials are
            // still real, so that one stays.
            android.webkit.WebViewDatabase.getInstance(c)
                .clearHttpAuthUsernamePassword()
        } catch (e: Exception) { /* nothing stored */ }
        clearCache(c)
    }
}
