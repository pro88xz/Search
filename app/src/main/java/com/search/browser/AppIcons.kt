package com.search.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The icon of an app a home tile stands for (Via, the music player), taken
 * from its own Play Store listing.
 *
 * A Play Store tile drawn like a site's would wear Google Play's favicon,
 * which says nothing about the app. The listing names the app's icon - the
 * image marked "Icon image", and the page's og:image - on Google's image
 * server, which hands any size asked for; it is fetched once at 256px and
 * kept, so the tile has it from then on with no further request. Only for
 * the built-in app tiles: nothing here touches the user's history.
 */
object AppIcons {

    private const val PREFS = "app_icons"
    private const val SIZE = 256
    private const val MAX_PAGE = 2 * 1024 * 1024
    private const val MAX_IMAGE = 512 * 1024

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "app-icons").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    /** Apps being fetched now, and when each last failed (one try a minute, not one per home render). */
    private val busy = HashSet<String>()
    private val failedAt = HashMap<String, Long>()

    /** The kept icon for [appId] as a data: URL, or null. */
    fun cached(c: Context, appId: String): String? =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(appId, null)

    /** Fetches [appId]'s icon in the background; [onIcon] runs (on a background thread) once it is kept. */
    fun fetch(c: Context, appId: String, onIcon: () -> Unit) {
        if (!Shortcuts.PLAY_ID.matches(appId)) return
        val app = c.applicationContext
        synchronized(busy) {
            val now = android.os.SystemClock.uptimeMillis()
            if (appId in busy || now - (failedAt[appId] ?: -60_000L) < 60_000L) return
            busy.add(appId)
        }
        val ua = try { android.webkit.WebSettings.getDefaultUserAgent(app) } catch (e: Exception) { null }
        io.execute {
            var ok = false
            try {
                val page = read("https://play.google.com/store/apps/details?id=$appId&hl=en", ua, MAX_PAGE)
                    ?.toString(Charsets.UTF_8)
                val src = page?.let { iconUrl(it) }
                val bytes = src?.let { read(sized(it), ua, MAX_IMAGE) }
                val icon = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                if (icon != null && icon.width >= 48) {
                    val out = java.io.ByteArrayOutputStream()
                    val fit = if (icon.width > SIZE) Bitmap.createScaledBitmap(icon, SIZE, SIZE * icon.height / icon.width, true) else icon
                    fit.compress(Bitmap.CompressFormat.PNG, 100, out)
                    val data = "data:image/png;base64," +
                        android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
                    app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(appId, data).apply()
                    ok = true
                }
            } catch (e: Throwable) {
                // No icon is not worth a crash; the tile keeps its letter.
            } finally {
                synchronized(busy) {
                    busy.remove(appId)
                    if (!ok) failedAt[appId] = android.os.SystemClock.uptimeMillis()
                }
            }
            if (ok) try { onIcon() } catch (e: Exception) { }
        }
    }

    /**
     * The listing's icon: the <img> marked "Icon image", else og:image or
     * itemprop=image - always on play-lh.googleusercontent.com, Google's image
     * server, and nothing else is taken.
     */
    internal fun iconUrl(html: String): String? {
        val img = Regex("<img[^>]+>", RegexOption.IGNORE_CASE).findAll(html)
            .map { it.value }
            .firstOrNull { it.contains("alt=\"Icon image\"", ignoreCase = true) }
            ?.let { Regex("\\bsrc=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
        val meta = Regex("<meta[^>]+(?:property=\"og:image\"|itemprop=\"image\")[^>]*content=\"([^\"]+)\"",
            RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
        return listOfNotNull(img, meta)
            .map { it.replace("&amp;", "&") }
            .firstOrNull { it.startsWith("https://play-lh.googleusercontent.com/") }
    }

    /** The same image at [SIZE] pixels: the server reads the size from what follows the "=". */
    internal fun sized(url: String): String = url.substringBefore('=') + "=s$SIZE"

    private fun read(url: String, ua: String?, max: Int): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
                if (ua != null) setRequestProperty("User-Agent", ua)
            }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > max) return null
                }
                out.toByteArray()
            }
        } catch (e: Throwable) {
            null
        } finally {
            try { conn?.disconnect() } catch (e: Exception) {}
        }
    }
}
