package com.search.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.webkit.WebView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Crisp icons for websites, shown in the search field while searching from a
 * site (MainActivity.showSiteIcon).
 *
 * WebView's own favicon (onReceivedIcon) is whatever the site's <link
 * rel=icon> first offers - usually a 16 or 32px picture, soft at 24dp. Sites
 * also declare large icons for home screens: apple-touch-icon (180px), icons
 * with a sizes attribute, or /apple-touch-icon.png by convention. This asks
 * the page which icons it declares, takes the smallest that is at least
 * [WANT_PX] across (or else the largest), and falls back to the conventional
 * addresses on the site itself. Nothing is asked of any third party, so
 * looking up an icon tells no one but the site where the user is.
 *
 * Kept in memory and, except for Night Owl, in the cache directory, so a site
 * already seen shows its icon at once. SVG icons are skipped: Android cannot
 * draw them without a library.
 */
object SiteIcons {

    private const val WANT_PX = 96
    private const val MAX_BYTES = 512 * 1024

    private val memory = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "site-icons").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val main = Handler(Looper.getMainLooper())
    /** Hosts being looked up now (main thread only), so a second ask does not start a second fetch. */
    private val inFlight = HashMap<String, MutableList<(Bitmap) -> Unit>>()
    /** When each lookup started: one whose page was closed mid-way never answers, so it expires. */
    private val startedAt = HashMap<String, Long>()
    private const val STALE_LOOKUP_MS = 15_000L

    /** The page's declared icons as [[href, size], ...], size 0 when not given. */
    private const val COLLECT_JS = "(function(){try{var o=[];var ls=document.querySelectorAll('link[rel]');" +
        "for(var i=0;i<ls.length;i++){var l=ls[i];var r=(l.getAttribute('rel')||'').toLowerCase();" +
        "if(r.indexOf('icon')<0||r.indexOf('mask-icon')>=0)continue;" +
        "var t=(l.getAttribute('type')||'').toLowerCase();var h=l.href;" +
        "if(!h||t.indexOf('svg')>=0||/\\.svg(\\?|#|$)/i.test(h))continue;" +
        "var s=0,m=(l.getAttribute('sizes')||'').match(/\\d+/g);" +
        "if(m){for(var j=0;j<m.length;j++){var n=parseInt(m[j],10);if(n>s)s=n;}}" +
        "if(!s&&r.indexOf('apple-touch-icon')>=0)s=180;o.push([h,s]);}" +
        "return JSON.stringify(o);}catch(e){return '[]';}})()"

    /** The key an icon is kept under: the page's host, without "www.". */
    fun keyOf(url: String?): String? {
        if (url == null) return null
        val uri = try { Uri.parse(url) } catch (e: Exception) { return null }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        return uri.host?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotEmpty() }
    }

    /** The icon already in memory for [url]'s site, if any. */
    fun cached(url: String?): Bitmap? = keyOf(url)?.let { memory.get(it) }

    /** Memory is short: the icons in memory go; the ones on disk come back when next wanted. */
    fun trimMemory() = memory.evictAll()

    /**
     * Finds the crisp icon for the page [web] is showing at [pageUrl] and hands
     * it to [onIcon] on the main thread; nothing is called when the site has
     * no usable icon. Call on the main thread. [private] (Night Owl): kept in
     * memory only, never written to disk.
     */
    fun load(context: Context, web: WebView, pageUrl: String, private: Boolean, onIcon: (Bitmap) -> Unit) {
        val key = keyOf(pageUrl) ?: return
        memory.get(key)?.let { onIcon(it); return }
        val now = android.os.SystemClock.uptimeMillis()
        val pending = inFlight[key]
        if (pending != null && now - (startedAt[key] ?: 0L) < STALE_LOOKUP_MS) { pending.add(onIcon); return }
        inFlight[key] = mutableListOf(onIcon)
        startedAt[key] = now
        val file = File(File(context.cacheDir, "site_icons"), sha1(key) + ".png")
        val origin = Uri.parse(pageUrl).let { it.scheme + "://" + it.host + (if (it.port > 0) ":" + it.port else "") }
        val ua = try { android.webkit.WebSettings.getDefaultUserAgent(context) } catch (e: Exception) { null }
        io.execute {
            val disk = if (private) null else decodeFile(file)
            main.post {
                if (disk != null) { deliver(key, disk); return@post }
                // The tab may have been closed while the disk was read: a
                // destroyed WebView is off the window, and calling into one
                // aborts the process rather than throwing. isAttachedToWindow
                // is a plain View call that never reaches the page.
                if (!web.isAttachedToWindow) { inFlight.remove(key); startedAt.remove(key); return@post }
                // The page's own list of icons: only the page knows it, and only
                // on the main thread can it be asked.
                val ask = try {
                    web.evaluateJavascript(COLLECT_JS) { result ->
                        val declared = parseDeclared(result)
                        io.execute {
                            val icon = fetchBest(declared, origin, ua)
                            if (icon != null && !private) save(file, icon)
                            main.post { if (icon != null) deliver(key, icon) else inFlight.remove(key) }
                        }
                    }
                    true
                } catch (e: Exception) { false }
                if (!ask) inFlight.remove(key)
            }
        }
    }

    private fun deliver(key: String, icon: Bitmap) {
        memory.put(key, icon)
        synchronized(this) { tileData.remove(key) }
        startedAt.remove(key)
        inFlight.remove(key)?.forEach { cb -> try { cb(icon) } catch (_: Exception) { } }
    }

    private fun parseDeclared(result: String?): List<Pair<String, Int>> = try {
        // evaluateJavascript hands back the JSON of the string the script returned.
        val text = org.json.JSONTokener(result ?: "\"[]\"").nextValue() as? String ?: "[]"
        val arr = org.json.JSONArray(text)
        (0 until arr.length()).mapNotNull { i ->
            val e = arr.optJSONArray(i) ?: return@mapNotNull null
            val href = e.optString(0)
            if (href.isEmpty()) null else href to e.optInt(1, 0)
        }
    } catch (e: Exception) { emptyList() }

    /**
     * Tries the candidates best-first: declared icons of at least [WANT_PX],
     * smallest first (no point fetching 1024px for a 24dp slot); then
     * /apple-touch-icon.png; then the larger of whatever else was declared;
     * then /favicon.ico. Keeps the biggest usable picture seen, stopping at
     * the first one that is big enough.
     */
    private fun fetchBest(declared: List<Pair<String, Int>>, origin: String, ua: String?): Bitmap? {
        val big = declared.filter { it.second >= WANT_PX }.sortedBy { it.second }.map { it.first }
        val rest = declared.filter { it.second < WANT_PX }.sortedByDescending { it.second }.map { it.first }
        val order = LinkedHashSet<String>().apply {
            addAll(big)
            add("$origin/apple-touch-icon.png")
            addAll(rest)
            add("$origin/favicon.ico")
        }
        var best: Bitmap? = null
        for (url in order.take(6)) {
            val b = fetch(url, ua) ?: continue
            if (best == null || minOf(b.width, b.height) > minOf(best.width, best.height)) best = b
            if (minOf(b.width, b.height) >= 64) break
        }
        return best?.takeIf { minOf(it.width, it.height) >= 16 }
    }

    private fun fetch(url: String, ua: String?): Bitmap? {
        if (url.startsWith("data:image/", ignoreCase = true)) {
            val comma = url.indexOf(',')
            if (comma < 0 || !url.substring(0, comma).contains(";base64", ignoreCase = true)) return null
            return try { decode(android.util.Base64.decode(url.substring(comma + 1), android.util.Base64.DEFAULT)) }
            catch (e: Exception) { null }
        }
        if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) return null
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                instanceFollowRedirects = true
                if (ua != null) setRequestProperty("User-Agent", ua)
                setRequestProperty("Accept", "image/png,image/x-icon,image/*;q=0.8")
            }
            if (conn.responseCode !in 200..299) return null
            val type = conn.contentType?.lowercase() ?: ""
            if (type.startsWith("text/") || type.contains("svg")) return null
            val bytes = conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) return null
                }
                out.toByteArray()
            }
            decode(bytes)
        } catch (e: Throwable) {
            // OutOfMemoryError too: on this background thread an uncaught one
            // would end the app, for the sake of an icon.
            null
        } finally {
            try { conn?.disconnect() } catch (e: Exception) {}
        }
    }

    // ---------- For home's shortcut tiles ----------
    //
    // A tile for a site in history used WebView's captured favicon - mostly
    // 16 or 32px, blurry in a 48dp tile - or a letter when there was none,
    // while the built-in tiles fetched sharp ones. The crisp icon found for
    // the address bar (above) is on disk already for every site visited, so
    // the tiles use that; a tile's site without one gets its large icon
    // fetched from the site itself, which learns nothing a visit to it would
    // not tell it.

    private const val TILE_MIN_PX = 48
    private val tileData = object : LruCache<String, String>(48) {}
    private val tilePrefetched = HashSet<String>()

    /**
     * The crisp icon for [host] as a PNG data: URL for the page, from memory
     * or the disk cache, never the network; null when there is none at
     * least [TILE_MIN_PX] across. Also tries "m." and "mobile." for a host
     * visited on its mobile site. Safe from any thread.
     */
    @Synchronized
    fun tileDataUrl(context: Context, host: String): String? {
        if (host.isBlank()) return null
        tileData.get(host)?.let { return it.ifEmpty { null } }
        val icon = listOf(host, "m.$host", "mobile.$host").firstNotNullOfOrNull { key ->
            memory.get(key) ?: decodeFile(File(File(context.cacheDir, "site_icons"), sha1(key) + ".png"))
        }
        if (icon == null || minOf(icon.width, icon.height) < TILE_MIN_PX) return null
        val url = try {
            val side = 192
            val scaled = if (maxOf(icon.width, icon.height) > side) {
                val f = side.toFloat() / maxOf(icon.width, icon.height)
                Bitmap.createScaledBitmap(icon, (icon.width * f).toInt().coerceAtLeast(1),
                    (icon.height * f).toInt().coerceAtLeast(1), true)
            } else icon
            val out = java.io.ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            "data:image/png;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (e: Throwable) { return null }
        tileData.put(host, url)
        return url
    }

    /**
     * Fetches [host]'s large icon from the site itself (its apple-touch-icon,
     * then its favicon) into the cache, once per host per run, and calls
     * [onDone] on the main thread if a crisp one was found.
     */
    fun prefetchForTile(context: Context, host: String, onDone: () -> Unit) {
        if (host.isBlank()) return
        synchronized(this) { if (!tilePrefetched.add(host)) return }
        val dir = File(context.cacheDir, "site_icons")
        // Asked from home's JavaScript bridge thread; WebView's own user agent
        // is read on the main thread, as the icon loader above does.
        main.post { prefetchWith(host, dir,
            try { android.webkit.WebSettings.getDefaultUserAgent(context) } catch (e: Exception) { null }, onDone) }
    }

    private fun prefetchWith(host: String, dir: File, ua: String?, onDone: () -> Unit) {
        io.execute {
            val icon = try {
                fetchBest(emptyList(), "https://$host", ua) ?: fetchBest(emptyList(), "https://www.$host", ua)
            } catch (t: Throwable) { null }
            if (icon != null && minOf(icon.width, icon.height) >= TILE_MIN_PX) {
                save(File(dir, sha1(host) + ".png"), icon)
                main.post {
                    memory.put(host, icon)
                    synchronized(this) { tileData.remove(host) }
                    onDone()
                }
            }
        }
    }

    /** Decodes at no more than twice the wanted size; Skia reads PNG, JPEG, WebP, GIF and ICO. */
    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= WANT_PX * 2) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun decodeFile(file: File): Bitmap? =
        if (!file.isFile) null else try { BitmapFactory.decodeFile(file.path) } catch (e: Throwable) { null }

    private fun save(file: File, icon: Bitmap) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.outputStream().use { icon.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (e: Exception) { }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
