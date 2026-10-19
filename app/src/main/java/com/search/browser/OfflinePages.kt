package com.search.browser

import android.content.Context

/**
 * Pages kept to read with no connection: Menu > Page tools > Save offline,
 * read again from Menu > History & downloads > Offline pages.
 *
 * What is kept is the page's reading content - the article if there is one
 * (the same choice Reader view makes), otherwise the page's text - with its
 * pictures stored inside it, so it opens on a plane, in a dead zone or with
 * the data switched off. Not the live page: no scripts, no ads, no forms.
 *
 * Each page is one JSON file in the app's own storage (offline/<id>.json),
 * listed in offline/index.json. They are shown by an asset page
 * (saved.html), which asks for one through the privileged bridge and builds
 * it again from an allow-list of plain elements: nothing the site wrote can
 * run there, whatever it slipped into the copy.
 *
 * Never in Night Owl, which keeps nothing.
 */
object OfflinePages {

    const val PAGE = "file:///android_asset/saved.html"
    private const val DIR = "offline"
    private const val INDEX = "index.json"
    /** The oldest go first beyond this many. */
    private const val MAX_PAGES = 200
    /** A page's text beyond this is cut: a feed page's body can run to megabytes of nothing. */
    private const val MAX_HTML = 1_500_000
    const val MAX_IMAGES = 24
    private const val MAX_IMAGE_BYTES = 900 * 1024
    private const val MAX_TOTAL_IMAGE_BYTES = 6 * 1024 * 1024

    class Entry(
        val id: String,
        val url: String,
        val title: String,
        val site: String,
        val savedAt: Long,
        val bytes: Long
    )

    /** What the page handed over (the [JS] answer). */
    class Capture(
        val url: String,
        val title: String,
        val site: String,
        val html: String,
        val imgs: List<String>
    )

    fun urlFor(id: String): String = "$PAGE?id=$id"

    private val ID = Regex("^[A-Za-z0-9-]{1,64}$")

    /** The saved page's id, for saved.html's address; null for anything else. */
    fun idOf(url: String?): String? {
        if (url == null || !url.startsWith(PAGE)) return null
        val id = url.substringAfter("id=", "").substringBefore('&').substringBefore('#')
        return id.takeIf { ID.matches(it) }
    }

    /**
     * Run in the page: the reading content, copied (the page itself is not
     * touched), as {url, title, site, html, imgs}. Each picture kept gets
     * data-i, its place in imgs - the address it was actually shown from,
     * so a responsive picture is the size the phone chose.
     */
    val JS: String = """
(function(){
try{
var best=null,score=0,cs=document.querySelectorAll('article,main,[role=main],[itemprop=articleBody],section,div');
for(var i=0;i<cs.length&&i<4000;i++){var e=cs[i],ps=e.querySelectorAll('p');if(ps.length<3)continue;
var len=0;for(var j=0;j<ps.length;j++){var t=ps[j].textContent.length;if(t>40)len+=t;}
var ln=0,as=e.querySelectorAll('a');for(var k=0;k<as.length;k++)ln+=as[k].textContent.length;
var s=len-ln*1.5;if(e.tagName==='ARTICLE'||e.getAttribute('itemprop')==='articleBody')s*=1.25;
if(s>score*1.1||(s>score&&best&&best.contains(e))){score=s;best=e;}}
var root=(best&&score>=600)?best:document.body;if(!root)return null;
var h=document.querySelector('h1');var title=(h&&h.textContent.trim())||document.title||'';
var oi=root.querySelectorAll('img'),c=root.cloneNode(true),ci=c.querySelectorAll('img');
for(var n=0;n<ci.length;n++){var o=oi[n],im=ci[n];
var src=(o&&o.currentSrc)||im.getAttribute('src')||im.getAttribute('data-src')||'';
try{src=new URL(src,location.href).href;}catch(x){src='';}
im.setAttribute('data-abs',src);}
c.querySelectorAll('a[href]').forEach(function(a){try{a.setAttribute('href',new URL(a.getAttribute('href'),location.href).href);}catch(x){a.removeAttribute('href');}});
var junk='script,style,noscript,iframe,object,embed,form,button,input,select,textarea,nav,aside,footer,header,svg,canvas,video,audio,dialog,template,[hidden],[aria-hidden=true],[role=navigation],[role=complementary],[class*=share],[class*=social],[class*=related],[class*=newsletter],[class*=comment],[class*=promo],[class*=advert],[id*=comment],[id*=related]';
c.querySelectorAll(junk).forEach(function(x){x.remove();});
c.querySelectorAll('h1').forEach(function(x){if(x.textContent.trim()===title)x.remove();});
var imgs=[];
c.querySelectorAll('img').forEach(function(im){var a=im.getAttribute('data-abs')||'';im.removeAttribute('data-abs');
var w=im.getAttribute('width');if(w&&+w>0&&+w<48){im.remove();return;}
if(/^https?:/i.test(a)&&imgs.length<24){im.setAttribute('data-i',String(imgs.length));imgs.push(a);}else im.setAttribute('data-i','-1');});
return {url:location.href,title:title,site:location.hostname.replace(/^www\./,''),html:c.innerHTML,imgs:imgs};
}catch(e){return null;}
})()
""".trimIndent()

    /** The [JS] answer, or null when the page gave nothing worth keeping. */
    fun parseCapture(raw: String?): Capture? {
        if (raw.isNullOrBlank() || raw == "null") return null
        val o = try { org.json.JSONObject(raw) } catch (e: Exception) { return null }
        val url = o.optString("url", "")
        if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) return null
        var html = o.optString("html", "")
        if (html.isBlank()) return null
        if (html.length > MAX_HTML) html = html.substring(0, MAX_HTML)
        val arr = o.optJSONArray("imgs")
        val imgs = ArrayList<String>()
        if (arr != null) for (i in 0 until minOf(arr.length(), MAX_IMAGES)) imgs.add(arr.optString(i, ""))
        return Capture(url, o.optString("title", "").trim().take(300), o.optString("site", "").take(120), html, imgs)
    }

    /**
     * Each picture as a data: address to keep inside the page, or null where
     * it could not be had, was not a picture, or would make the page too
     * heavy. Sent as the page sent it: its user agent, its address as
     * referer, each address's own cookies. Network: never on the main thread.
     */
    fun fetchImages(urls: List<String>, userAgent: String?, referer: String?, cookieFor: (String) -> String?): List<String?> {
        var total = 0L
        return urls.map { u ->
            if (total >= MAX_TOTAL_IMAGE_BYTES || !u.startsWith("http", true)) return@map null
            val got = fetchImage(u, userAgent, referer, cookieFor) ?: return@map null
            if (total + got.second.size > MAX_TOTAL_IMAGE_BYTES) return@map null
            total += got.second.size
            "data:" + got.first + ";base64," +
                android.util.Base64.encodeToString(got.second, android.util.Base64.NO_WRAP)
        }
    }

    private fun fetchImage(url: String, userAgent: String?, referer: String?, cookieFor: (String) -> String?): Pair<String, ByteArray>? {
        val c = try { java.net.URL(url).openConnection() as? java.net.HttpURLConnection } catch (e: Exception) { null } ?: return null
        return try {
            c.connectTimeout = 6000
            c.readTimeout = 8000
            c.instanceFollowRedirects = true
            userAgent?.let { c.setRequestProperty("User-Agent", it) }
            referer?.let { c.setRequestProperty("Referer", it) }
            cookieFor(url)?.takeIf { it.isNotBlank() }?.let { c.setRequestProperty("Cookie", it) }
            if (c.responseCode !in 200..299) return null
            val type = c.contentType?.substringBefore(';')?.trim()?.lowercase() ?: return null
            if (!type.startsWith("image/") || type.contains("svg")) return null
            if (c.contentLengthLong > MAX_IMAGE_BYTES) return null
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_IMAGE_BYTES) return null
                }
            }
            type to out.toByteArray()
        } catch (e: Exception) {
            null
        } finally {
            try { c.disconnect() } catch (e: Exception) {}
        }
    }

    private fun dir(c: Context): java.io.File = java.io.File(c.filesDir, DIR).apply { mkdirs() }

    @Volatile private var cache: List<Entry>? = null

    /** Newest first. */
    @Synchronized
    fun list(c: Context): List<Entry> {
        cache?.let { return it }
        val f = java.io.File(dir(c), INDEX)
        val out = ArrayList<Entry>()
        try {
            if (f.exists()) {
                val arr = org.json.JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id", "")
                    if (!ID.matches(id)) continue
                    out.add(Entry(id, o.optString("url", ""), o.optString("title", ""),
                        o.optString("site", ""), o.optLong("savedAt", 0L), o.optLong("bytes", 0L)))
                }
            }
        } catch (e: Exception) { /* an unreadable index lists nothing; the files stay */ }
        out.sortByDescending { it.savedAt }
        cache = out
        return out
    }

    /** From memory only, for the address bar: the site a saved page came from. */
    fun siteOf(id: String): String? = cache?.firstOrNull { it.id == id }?.site

    private fun writeIndex(c: Context, list: List<Entry>): Boolean {
        val arr = org.json.JSONArray()
        list.forEach { e ->
            arr.put(org.json.JSONObject().put("id", e.id).put("url", e.url).put("title", e.title)
                .put("site", e.site).put("savedAt", e.savedAt).put("bytes", e.bytes))
        }
        return try {
            val f = java.io.File(dir(c), INDEX)
            val tmp = java.io.File(dir(c), "$INDEX.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            cache = list
            true
        } catch (e: Exception) { false }
    }

    /**
     * Keeps [page] with its pictures ([imgs], in data-i order). A page saved
     * again replaces its earlier copy. Null when it could not be written.
     */
    @Synchronized
    fun save(c: Context, page: Capture, imgs: List<String?>): Entry? {
        val existing = list(c)
        val same = existing.firstOrNull { it.url == page.url }
        val id = same?.id ?: java.util.UUID.randomUUID().toString()
        val arr = org.json.JSONArray()
        imgs.forEach { arr.put(it ?: org.json.JSONObject.NULL) }
        val now = System.currentTimeMillis()
        val body = org.json.JSONObject()
            .put("id", id).put("url", page.url).put("title", page.title).put("site", page.site)
            .put("savedAt", now).put("html", page.html).put("imgs", arr)
            .toString()
        val f = java.io.File(dir(c), "$id.json")
        try {
            val tmp = java.io.File(dir(c), "$id.json.tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) return null }
        } catch (e: Exception) {
            return null
        }
        val entry = Entry(id, page.url, page.title.ifBlank { page.site }, page.site, now, f.length())
        val next = ArrayList<Entry>()
        next.add(entry)
        existing.filter { it.id != id }.forEach { next.add(it) }
        while (next.size > MAX_PAGES) {
            val gone = next.removeAt(next.size - 1)
            java.io.File(dir(c), gone.id + ".json").delete()
        }
        if (!writeIndex(c, next)) return null
        return entry
    }

    /** The saved page's JSON for saved.html, or "" when there is none. */
    @Synchronized
    fun read(c: Context, id: String): String {
        if (!ID.matches(id)) return ""
        return try {
            val f = java.io.File(dir(c), "$id.json")
            if (f.exists()) f.readText() else ""
        } catch (e: Exception) { "" }
    }

    @Synchronized
    fun delete(c: Context, id: String) {
        if (!ID.matches(id)) return
        java.io.File(dir(c), "$id.json").delete()
        writeIndex(c, list(c).filter { it.id != id })
    }

    @Synchronized
    fun deleteAll(c: Context) {
        list(c).forEach { java.io.File(dir(c), it.id + ".json").delete() }
        writeIndex(c, emptyList())
    }
}
