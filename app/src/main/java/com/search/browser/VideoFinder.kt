package com.search.browser

/**
 * The video downloader's eyes: which videos on the page in front of you are
 * plain files that can be saved, as Chrome's own "Download video" offers.
 *
 * A page is asked (evaluateJavascript, main frame only) for every address a
 * video could come from - a <video>'s current source and its <source>s, the
 * page's og:video, and links straight to a video file. What comes back is
 * kept only if it is a whole file on the web:
 *
 *  - http or https. A blob: or mediastream: address is a player feeding
 *    itself pieces (adaptive streaming, DRM) and is not a file at all.
 *  - Not a stream's playlist (.m3u8, .mpd, .ism): those are lists of
 *    fragments, not a video.
 *  - Never from YouTube, on YouTube or embedded anywhere: its terms do not
 *    allow saving its videos, and Google Play removes apps that do.
 *  - Not on a search engine's own pages, and not a player that is
 *    decoration: a silent loop, a clip of a few seconds, a tiny preview
 *    ([decorative]) - the "GIF" a results page plays is not a video anyone
 *    asked to save.
 *
 * Kept free of Android types beyond org.json, so it can be tested on the JVM.
 */
object VideoFinder {

    /** One saveable video: its address, and what the page says about it. */
    data class Found(
        val url: String,
        val type: String?,
        val width: Int,
        val height: Int,
        val title: String
    )

    /**
     * Search engines' own pages: what plays there is a preview of something
     * elsewhere - a silent loop of a result - never the video itself.
     */
    private val SEARCH_PAGES = listOf(
        "google.", "bing.com", "duckduckgo.com", "search.yahoo.com", "yandex.",
        "ecosia.org", "startpage.com", "search.brave.com"
    )

    /** Videos on a page beyond this are not listed: a chooser of hundreds helps nobody. */
    private const val MAX = 12

    private val VIDEO_EXT = Regex("""\.(mp4|m4v|webm|mov|3gp|mkv|ogv|avi|flv)$""", RegexOption.IGNORE_CASE)
    private val STREAM_EXT = Regex("""\.(m3u8|mpd|ism|isml|f4m)$""", RegexOption.IGNORE_CASE)

    /** Hosts whose videos are never offered (the host itself or anything under it). */
    private val NEVER = listOf(
        "youtube.com", "youtu.be", "youtube-nocookie.com", "googlevideo.com",
        "ytimg.com", "youtubekids.com", "youtube.googleapis.com"
    )

    /** True on a page whose videos are never offered, whatever it holds. */
    fun pageExcluded(pageHost: String?): Boolean = blocked(pageHost) || isSearchPage(pageHost)

    /** YouTube's own hosts (the page or the video). */
    fun youTube(host: String?): Boolean = blocked(host)

    private fun isSearchPage(host: String?): Boolean {
        val h = host?.lowercase()?.trimEnd('.') ?: return false
        return SEARCH_PAGES.any { p ->
            if (p.endsWith(".")) h.startsWith(p) || h.contains(".$p") else h == p || h.endsWith(".$p")
        }
    }

    private fun blocked(host: String?): Boolean {
        val h = host?.lowercase()?.trimEnd('.') ?: return false
        return NEVER.any { h == it || h.endsWith(".$it") }
    }

    /**
     * Run in the page. Returns an array (evaluateJavascript hands it back as
     * JSON) of {u, t, w, h, n}: address, declared type, size and a name. Wrapped
     * so a page that has redefined something it uses yields an empty list,
     * never an error in its console.
     */
    const val JS = """(function(){
  try {
    var out = [], seen = {};
    function add(u, t, w, h, n, f){
      if (!u || typeof u !== 'string') return;
      try { u = new URL(u, location.href).href; } catch (e) { return; }
      if (seen[u] || out.length >= 40) return;
      seen[u] = 1;
      var o = {u:u, t:t || '', w:w || 0, h:h || 0, n:(n || '').slice(0, 120)};
      if (f) { for (var k in f) o[k] = f[k]; }
      out.push(o);
    }
    // What a player is: a silent loop (a GIF in all but name), a clip of a
    // few seconds, a thumbnail-sized preview - or something being watched.
    function kind(v){
      var r = v.getBoundingClientRect();
      var d = (isFinite(v.duration) && v.duration > 0) ? v.duration : 0;
      return {v:1, m:(v.muted || v.hasAttribute('muted')) ? 1 : 0, l:v.loop ? 1 : 0,
        c:v.controls ? 1 : 0, d:Math.round(d * 10) / 10, rw:Math.round(r.width), rh:Math.round(r.height),
        p:(!v.paused && !v.muted && v.currentTime > 0) ? 1 : 0};
    }
    var title = (document.title || '').trim();
    var vids = document.getElementsByTagName('video');
    for (var i = 0; i < vids.length && i < 40; i++){
      var v = vids[i];
      var name = v.getAttribute('title') || v.getAttribute('aria-label') || title;
      var f = kind(v);
      add(v.currentSrc || v.src, v.getAttribute('type'), v.videoWidth, v.videoHeight, name, f);
      var ss = v.getElementsByTagName('source');
      for (var j = 0; j < ss.length; j++){
        add(ss[j].src, ss[j].getAttribute('type'), v.videoWidth, v.videoHeight, name, f);
      }
    }
    var metas = document.querySelectorAll('meta[property="og:video"],meta[property="og:video:url"],meta[property="og:video:secure_url"]');
    for (var k = 0; k < metas.length; k++){
      var mt = document.querySelector('meta[property="og:video:type"]');
      add(metas[k].getAttribute('content'), mt ? mt.getAttribute('content') : '', 0, 0, title);
    }
    var links = document.links;
    for (var m = 0; m < links.length && m < 2000; m++){
      var a = links[m], p = (a.pathname || '').toLowerCase();
      if (/\.(mp4|m4v|webm|mov|3gp|mkv|ogv)$/.test(p)) add(a.href, '', 0, 0, (a.textContent || '').trim() || title);
    }
    return out;
  } catch (e) { return []; }
})()"""

    /**
     * The saveable videos in [raw] - what [JS] returned, as JSON - for the
     * page at [pageUrl]. Empty for anything unreadable, and for a page whose
     * videos are never offered.
     */
    fun parse(raw: String?, pageUrl: String?): List<Found> {
        if (raw.isNullOrBlank() || raw == "null") return emptyList()
        if (pageExcluded(hostOf(pageUrl))) return emptyList()
        val arr = try { org.json.JSONArray(raw) } catch (e: Exception) { return emptyList() }
        val out = ArrayList<Found>()
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("u", "")
            val type = o.optString("t", "").ifBlank { null }
            if (!usable(url, type)) continue
            if (decorative(o)) continue
            if (!seen.add(url.substringBefore('#'))) continue
            out.add(Found(url, type, o.optInt("w", 0), o.optInt("h", 0),
                o.optString("n", "").trim()))
            if (out.size >= MAX) break
        }
        return out
    }

    /**
     * A player that is decoration rather than a video to keep: a silent loop
     * (sites, and Google's results, serve GIFs as muted looping videos), a
     * clip under 10 seconds, or a preview smaller than a thumbnail on screen.
     * One the reader has set playing with its sound on is never decoration.
     * Only players are judged; a link to a video file or a page's og:video
     * is what the page itself offers.
     */
    fun decorative(o: org.json.JSONObject): Boolean {
        if (o.optInt("v", 0) != 1) return false
        if (o.optInt("p", 0) == 1) return false
        val muted = o.optInt("m", 0) == 1
        val loop = o.optInt("l", 0) == 1
        if (muted && loop) return true
        val d = o.optDouble("d", 0.0)
        if (d > 0.0 && d < 10.0) return true
        val rw = o.optInt("rw", 0)
        val rh = o.optInt("rh", 0)
        if (rw in 1..159 || rh in 1..89) return true
        return false
    }

    /** A whole video file on the web, not a stream, not YouTube. */
    fun usable(url: String, type: String?): Boolean {
        if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) return false
        val host = hostOf(url) ?: return false
        if (blocked(host)) return false
        val path = pathOf(url)
        if (STREAM_EXT.containsMatchIn(path)) return false
        val t = type?.lowercase() ?: ""
        if ("mpegurl" in t || "dash" in t || "vnd.apple" in t) return false
        // An address with a video file's name, or one a player or the page
        // declared as video. A <video> pointing at an address with neither
        // is still a file more often than not (a CDN with no extension), and
        // the type the server gives when it is checked decides (looksLikeVideo).
        return true
    }

    /** What the server says the address is: a video, or a generic file with a video's name. */
    fun looksLikeVideo(url: String, contentType: String?): Boolean {
        val ct = contentType?.substringBefore(';')?.trim()?.lowercase() ?: ""
        if (ct.startsWith("video/")) return true
        if ("mpegurl" in ct || "dash+xml" in ct) return false
        if (ct.startsWith("text/") || ct.startsWith("image/") || ct.startsWith("audio/") ||
            ct == "application/json" || ct.endsWith("+xml")) return false
        // application/octet-stream and friends, or nothing said: the name decides.
        return VIDEO_EXT.containsMatchIn(pathOf(url)) || ct.isEmpty() || ct == "application/octet-stream" ||
            ct == "binary/octet-stream"
    }

    /** A line for the chooser: "720p · MP4 · cdn.example.com". */
    fun label(f: Found, index: Int): String {
        val parts = ArrayList<String>()
        if (f.height > 0) parts.add("${f.height}p")
        val ext = VIDEO_EXT.find(pathOf(f.url))?.groupValues?.get(1)?.uppercase()
            ?: f.type?.substringAfter('/', "")?.substringBefore(';')?.uppercase()?.takeIf { it.isNotBlank() }
        if (ext != null) parts.add(ext)
        hostOf(f.url)?.removePrefix("www.")?.let { parts.add(it) }
        val head = f.title.ifBlank { "Video ${index + 1}" }
        return head + if (parts.isEmpty()) "" else "\n" + parts.joinToString(" · ")
    }

    /** What the server said about a video address: its type, its full size (-1 unknown), where it ended up. */
    class Probe(val type: String?, val length: Long, val finalUrl: String)

    /**
     * Asks the server about [url] the way the download will: the page's
     * user agent and address as referer, and the cookies for each address on
     * the way (asked per hop, so one host's cookies never go to another).
     * One byte is asked for, not the video. Null when the server cannot be
     * reached or says no - the download is still tried, it may know better.
     * Network: never on the main thread.
     */
    fun probe(url: String, userAgent: String?, referer: String?, cookieFor: (String) -> String?): Probe? {
        var target = url
        for (hop in 0 until 5) {
            val c = (java.net.URL(target).openConnection() as? java.net.HttpURLConnection) ?: return null
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 6000
                c.readTimeout = 6000
                c.requestMethod = "GET"
                c.setRequestProperty("Range", "bytes=0-0")
                userAgent?.let { c.setRequestProperty("User-Agent", it) }
                referer?.let { c.setRequestProperty("Referer", it) }
                cookieFor(target)?.takeIf { it.isNotBlank() }?.let { c.setRequestProperty("Cookie", it) }
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: return null
                    target = java.net.URL(java.net.URL(target), loc).toString()
                    if (!target.startsWith("http", true)) return null
                    continue
                }
                if (code >= 400) return null
                val total = c.getHeaderField("Content-Range")?.substringAfter('/', "")?.trim()?.toLongOrNull()
                    ?: if (code == 200) c.contentLengthLong.takeIf { it > 0 } ?: -1L else -1L
                return Probe(c.contentType, total, target)
            } catch (e: Exception) {
                return null
            } finally {
                try { c.disconnect() } catch (e: Exception) {}
            }
        }
        return null
    }

    /** The type to save [f] as when the server does not say: from its name, else MP4. */
    fun mimeFor(f: Found): String {
        val ext = VIDEO_EXT.find(pathOf(f.url))?.groupValues?.get(1)?.lowercase()
        return when (ext) {
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "3gp" -> "video/3gpp"
            "mkv" -> "video/x-matroska"
            "ogv" -> "video/ogg"
            "avi" -> "video/x-msvideo"
            "flv" -> "video/x-flv"
            else -> f.type?.lowercase()?.substringBefore(';')?.trim()?.takeIf { it.startsWith("video/") } ?: "video/mp4"
        }
    }

    /**
     * A file name for [f] from the page's title rather than the address -
     * "Sunset over Freetown.mp4", not "a8f3c9.mp4" - handed to the download
     * as if the server had said it. Null for a video with no title, where
     * the address's own name is used.
     */
    fun disposition(f: Found, mime: String): String? {
        val name = f.title.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]+"""), " ")
            .replace(Regex("""\s+"""), " ").trim().take(80).trim()
        if (name.isEmpty()) return null
        val ext = VIDEO_EXT.find(pathOf(f.url))?.groupValues?.get(1)?.lowercase() ?: when (mime) {
            "video/webm" -> "webm"
            "video/quicktime" -> "mov"
            "video/3gpp" -> "3gp"
            "video/x-matroska" -> "mkv"
            "video/ogg" -> "ogv"
            else -> "mp4"
        }
        return "attachment; filename=\"" + name + "." + ext + "\""
    }

    private fun hostOf(url: String?): String? = try {
        java.net.URI(url ?: return null).host?.lowercase()
    } catch (e: Exception) {
        url?.substringAfter("://", "")?.substringBefore('/')?.substringBefore('?')
            ?.substringBefore(':')?.lowercase()?.ifBlank { null }
    }

    private fun pathOf(url: String): String =
        url.substringAfter("://", url).substringAfter('/', "").substringBefore('?').substringBefore('#')
}
