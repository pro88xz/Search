package com.search.browser

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * Ad and tracker blocking (Settings > Ad blocking).
 *
 * Requests a website makes are judged by Brave's engine with EasyList and
 * EasyPrivacy (AdblockEngine), and by the short host list below, which is
 * what blocked before the engine came and what still blocks on a phone the
 * engine cannot run on, or in the moment before it has loaded. What either
 * blocks is blocked. On top of that, the ad boxes left empty on a page are
 * hidden ([COSMETIC_JS]).
 *
 * The app's own pages and ads are outside all of it: requests from home and
 * the other pages shipped in the app are only ever held to the host list, as
 * they always were, the app's sites are never blocked ([isOwnSite]), and the
 * feed's ad is a native view that no WebView request carries.
 */
object AdBlocker {

    /**
     * Ad and tracker hosts, matched against the request's host as a whole
     * label suffix - "criteo.com" blocks criteo.com and anything.criteo.com,
     * and nothing else.
     *
     * These used to be matched as a substring of the entire URL, which made
     * every entry a trap. A page at example.com/reviews/hotjar.com-vs-mixpanel
     * was blocked because the path contained a name on this list, and so was
     * any link carrying one as a query parameter. Matching the host is what
     * the list was always describing.
     */
    private val BLOCKED = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "google-analytics.com", "googletagmanager.com", "googletagservices.com",
        "adservice.google.com", "pagead2.googlesyndication.com",
        "adnxs.com", "adsystem.com", "amazon-adsystem.com",
        "fbcdn.net/ads",
        "scorecardresearch.com", "quantserve.com", "criteo.com", "criteo.net",
        "taboola.com", "outbrain.com", "adcolony.com", "applovin.com",
        "unityads.unity3d.com", "chartboost.com", "inmobi.com", "mopub.com",
        "pubmatic.com", "rubiconproject.com", "openx.net", "casalemedia.com",
        "adform.net", "smartadserver.com", "moatads.com", "3lift.com",
        "bidswitch.net", "sharethrough.com", "yieldmo.com", "teads.tv",
        "propellerads.com", "popads.net", "popcash.net", "adsterra.com",
        "exoclick.com", "juicyads.com", "hilltopads.net", "trafficjunky.net",
        "onesignal.com", "pushcrew.com", "mgid.com", "revcontent.com",
        "zedo.com", "adroll.com", "hotjar.com", "mixpanel.com",
        "segment.com", "amplitude.com", "branch.io", "appsflyer.com",
        "adjust.com", "kochava.com", "flurry.com", "crashlytics.com",
        "bugsnag.com", "newrelic.com", "optimizely.com"
    )

    /**
     * The two rules that are a path on a host which is otherwise wanted.
     * fbcdn.net serves Facebook's images as well as its ad assets, and
     * yandex.ru is a search engine, so neither host can be blocked outright.
     */
    private val BLOCKED_PATHS = listOf("fbcdn.net/ads", "yandex.ru/ads")

    /**
     * Facebook's tracking pixel, and only that.
     *
     * facebook.net was on the host list, which took connect.facebook.net with
     * it - and that is where the Facebook SDK lives, so "Continue with
     * Facebook" did nothing on every site that offers it. The pixel is one
     * file on the same host.
     */
    private fun isFacebookPixel(host: String, path: String): Boolean =
        host == "connect.facebook.net" && path.endsWith("/fbevents.js")

    /**
     * Stand-ins for the tag manager and analytics scripts, served in place of
     * the real ones rather than an empty file.
     *
     * Sites wait on these. "Push the sign-up event, and go to the welcome page
     * in its eventCallback" is the standard way to count a conversion, and the
     * callback is only ever called by Google's script. Blocked to nothing, the
     * script never runs, the callback never comes, and the page sits there
     * after the form is sent - signed in, as a reload shows, but stuck. The
     * same goes for analytics.js hitCallback and gtag's event_callback, and for
     * the anti-flicker snippet that keeps a page hidden until the tag manager
     * says otherwise.
     *
     * Each stand-in sends nothing anywhere. It answers every callback that is
     * waiting, now and later, and lifts the anti-flicker hide.
     */
    private const val CALLBACKS_JS = """
(function(){
  var w = window, noop = function(){};
  function later(fn){ if (typeof fn === 'function') setTimeout(function(){ try { fn(); } catch (e) {} }, 1); }
  function answer(item){
    if (!item || typeof item !== 'object') return;
    if (typeof item.eventCallback === 'function') { later(item.eventCallback); item.eventCallback = noop; }
    if (item.length >= 3 && item[0] === 'event' && item[2] && typeof item[2] === 'object') {
      later(item[2].event_callback); item[2].event_callback = noop;
    }
  }
  var dl = w.dataLayer;
  if (dl && typeof dl === 'object') {
    if (dl.hide && typeof dl.hide.end === 'function') { try { dl.hide.end(); } catch (e) {} dl.hide.end = noop; }
    if (Array.isArray(dl)) {
      var push = dl.push;
      dl.push = function(){ for (var i = 0; i < arguments.length; i++) answer(arguments[i]); return push.apply(dl, arguments); };
      for (var j = 0; j < dl.length; j++) answer(dl[j]);
    }
  }
  var name = w.GoogleAnalyticsObject || 'ga', queued = w[name] && w[name].q;
  var tracker = { get: noop, set: noop, send: noop };
  var ga = function(){
    var a = Array.prototype.slice.call(arguments);
    for (var i = 0; i < a.length; i++) {
      if (a[i] === 'hitCallback') { later(a[i + 1]); i++; }
      else if (typeof a[i] === 'function') later(function(f){ return function(){ f(tracker); }; }(a[i]));
      else if (a[i] && typeof a[i] === 'object' && typeof a[i].hitCallback === 'function') later(a[i].hitCallback);
    }
  };
  ga.create = function(){ return tracker; };
  ga.getByName = function(){ return tracker; };
  ga.getAll = function(){ return [tracker]; };
  ga.remove = noop;
  ga.loaded = true;
  w[name] = ga;
  if (Array.isArray(queued)) for (var k = 0; k < queued.length; k++) ga.apply(null, queued[k]);
})();
"""

    /** Whether this blocked request is one of the scripts [CALLBACKS_JS] stands in for. */
    private fun wantsCallbacks(host: String, path: String): Boolean {
        if (host == "www.googletagmanager.com" || host == "googletagmanager.com") {
            return path == "/gtm.js" || path.startsWith("/gtag/js")
        }
        if (host == "google-analytics.com" || host.endsWith(".google-analytics.com")) {
            return path == "/analytics.js" || path == "/ga.js" || path == "/urchin.js"
        }
        return false
    }

    private fun callbacksResponse() = WebResourceResponse(
        "application/javascript", "utf-8",
        ByteArrayInputStream(CALLBACKS_JS.toByteArray(Charsets.UTF_8))
    )

    // A blocked request must get its own WebResourceResponse each time: a
    // response's input stream is consumed once and read from WebView threads,
    // so a single shared instance across concurrent blocked requests is unsafe.
    private fun emptyResponse(mime: String = "text/plain") = WebResourceResponse(
        mime, "utf-8", ByteArrayInputStream(ByteArray(0))
    )

    /** One exception for every blocked read; no stack trace, which nobody reads. */
    private val REFUSAL = object : java.io.IOException("Blocked by Search's ad blocker") {
        override fun fillInStackTrace(): Throwable = this
    }

    /** A body that cannot be read: WebView fails the request, as a network error. */
    private class Refused : java.io.InputStream() {
        override fun read(): Int = throw REFUSAL
        override fun read(b: ByteArray, off: Int, len: Int): Int = throw REFUSAL
        override fun available(): Int = 0
    }

    /**
     * What a blocked request gets: a failure, as Brave and uBlock Origin give
     * it by cancelling the request - the filter lists are written, and their
     * exceptions tested, against exactly that.
     *
     * It used to be an empty page that loaded fine. A page that fetches JSON
     * and reads it then got "success" with nothing in it, and choked: YouTube's
     * player asks youtubei/v1/player/ad_break (on EasyList) for its ad plan,
     * could not read the empty answer, and spun for ever. A script answered
     * empty fired onload with none of its code, where a page that copes with
     * blockers waits for onerror. WebView has no way to cancel from here, but
     * a body that throws on read fails the request the same way: fetch and
     * XMLHttpRequest report a network error, scripts and images their onerror.
     * Frames are the exception: a failed frame shows an error page in the ad's
     * place, so they get an empty page, as before.
     */
    private fun blockedResponse(types: String): WebResourceResponse =
        if (types == "sub_frame") emptyResponse("text/html")
        else WebResourceResponse("text/plain", "utf-8", Refused())

    /**
     * Sites the blocker leaves alone, whatever the setting.
     *
     * YouTube stops its videos when it finds its ads blocked - the player
     * waits on them, and spins for ever. Brave keeps YouTube playing only by
     * running scripts of its own inside YouTube's pages, rewritten every few
     * days as YouTube changes; Search runs no script from the lists. So
     * YouTube is not blocked at all, its pages or its servers - which also
     * keeps a YouTube video playing when it is embedded in another site.
     */
    private val LEFT_ALONE = setOf(
        "youtube.com", "youtube-nocookie.com", "youtu.be", "googlevideo.com", "ytimg.com",
        "youtubei.googleapis.com", "youtube.googleapis.com", "youtubekids.com"
    )

    /** Whether [host] is one of [LEFT_ALONE] or under one. */
    fun isLeftAlone(host: String?): Boolean = under(host, LEFT_ALONE)

    /**
     * The services sites use to tell a person from a bot - AWS WAF (Amazon,
     * IMDb and many more), PerimeterX's Press & Hold, DataDome, Arkose,
     * hCaptcha, Cloudflare Turnstile, reCAPTCHA, GeeTest - never blocked, and
     * nothing hidden in their frames. EasyPrivacy lists parts of them, as the
     * device data they collect is tracking; but a check that cannot report
     * back never passes, and the site stays shut: in testing, Amazon and IMDb
     * came up blank behind an AWS WAF check whose verify call was blocked.
     * uBlock Origin carries exceptions of its own for these; Search's lists
     * do not, so they are here.
     */
    private val SECURITY_CHECKS = setOf(
        "awswaf.com", "perimeterx.net", "px-cdn.net", "px-cloud.net", "px-client.net",
        "perimeterx.com", "humansecurity.com", "datadome.co", "captcha-delivery.com",
        "arkoselabs.com", "funcaptcha.com", "hcaptcha.com", "challenges.cloudflare.com",
        "recaptcha.net", "geetest.com", "geevisit.com", "friendlycaptcha.com", "mtcaptcha.com"
    )

    fun isSecurityCheck(host: String?): Boolean = under(host, SECURITY_CHECKS)

    private fun under(host: String?, set: Set<String>): Boolean {
        val h = host?.lowercase()?.removeSuffix(".") ?: return false
        return set.any { h == it || h.endsWith(".$it") }
    }

    fun isEnabled(c: Context): Boolean =
        Settings.getBool(c, Settings.ADBLOCK_ENABLED, false)

    /**
     * The host or any domain it sits under is on the list: a.b.criteo.com is
     * looked up as itself, then b.criteo.com, then criteo.com - a few set
     * lookups per request instead of comparing it with every entry, on a path
     * every picture, script and frame of every page goes through.
     */
    private fun isBlockedHost(host: String): Boolean {
        var h = host.lowercase().removeSuffix(".")
        while (true) {
            if (h in BLOCKED) return true
            val dot = h.indexOf('.')
            if (dot < 0 || dot == h.length - 1) return false
            h = h.substring(dot + 1)
        }
    }

    /**
     * The app's own sites are never blocked, whatever the setting: the games
     * section's ads (toolsepulse.co) are what pays for the games.
     */
    fun isOwnSite(pageHost: String?): Boolean {
        val h = pageHost?.lowercase() ?: return false
        return h == "toolsepulse.co" || h.endsWith(".toolsepulse.co")
    }

    /**
     * Returns a stand-in response if the request is an ad/tracker, else null.
     * [pageHost] is the site of the page making the request; on a site the
     * user has allowed ads on (AdBlockSites), nothing is blocked.
     */
    fun check(c: Context, request: WebResourceRequest?, pageHost: String? = null): WebResourceResponse? {
        if (!isEnabled(c)) return null
        if (isOwnSite(pageHost) || isLeftAlone(pageHost)) return null
        if (pageHost != null && AdBlockSites.isAllowed(c, pageHost)) return null
        // Never the page itself. A top-level navigation is somewhere the user
        // is going - a link they tapped, or a redirect on the way to one - and
        // links in sign-up and verification emails routinely pass through
        // click trackers on this list (adjust.com, branch.io, doubleclick).
        // Answered with an empty response, the tab showed a blank page and
        // stayed there. Blocking is for what a page pulls in, not for where
        // the user is headed.
        if (request == null || request.isForMainFrame) return null
        val uri = request.url ?: return null
        val host = uri.host?.lowercase()?.removeSuffix(".") ?: return null
        if (isLeftAlone(host) || isSecurityCheck(host)) return null
        val path = (uri.path ?: "").lowercase()
        val types = requestTypes(path, request.method, request.requestHeaders)
        val listed = isBlockedHost(host) || isFacebookPixel(host, path) ||
            BLOCKED_PATHS.any { (host + path).contains(it) }
        if (!listed && !engineBlocks(c, uri, types, pageHost)) return null
        PrivacyStats.blocked()
        return if (wantsCallbacks(host, path)) callbacksResponse() else blockedResponse(types)
    }

    /**
     * Whether Brave's engine blocks this request. Only for websites: a page
     * with no host is one of the app's own (home, the legal pages), and those
     * stay as they were.
     */
    private fun engineBlocks(c: Context, uri: android.net.Uri, types: String, pageHost: String?): Boolean {
        if (pageHost.isNullOrEmpty()) return false
        val scheme = uri.scheme
        if (scheme != "https" && scheme != "http") return false
        AdblockEngine.warmUp(c)
        return AdblockEngine.check(uri.toString(), "https://$pageHost/", types) == AdblockEngine.BLOCK
    }

    /**
     * What a request is for, as the lists name it - "script", "image" - or the
     * things it might be, comma separated.
     *
     * Filter lists say what they block by kind: a rule can be for scripts from
     * a host whose images are wanted. A desktop browser knows each request's
     * kind; Android's WebView does not say. Newer WebViews send the
     * Sec-Fetch-Dest header, which does; otherwise the request's Accept header
     * tells an image, a style sheet and a frame apart, a Range header tells
     * media, and the file's extension most of the rest. What is left - a fetch,
     * or a script with no ".js" on its name, which look alike - is asked about
     * as both, and AdblockEngine honours an exception for either.
     */
    internal fun requestTypes(path: String, method: String?, headers: Map<String, String>?): String {
        var dest: String? = null
        var accept = ""
        var range = false
        headers?.forEach { (k, v) ->
            when (k.lowercase()) {
                "sec-fetch-dest" -> dest = v.lowercase()
                "accept" -> accept = v.lowercase()
                "range" -> range = true
            }
        }
        when (dest) {
            "script", "worker", "sharedworker", "serviceworker", "audioworklet", "paintworklet" -> return "script"
            "style" -> return "stylesheet"
            "image" -> return "image"
            "font" -> return "font"
            "iframe", "frame", "fencedframe", "document" -> return "sub_frame"
            "audio", "video", "track" -> return "media"
            "object", "embed" -> return "object"
            "empty" -> return if (method.equals("POST", true)) "xmlhttprequest,ping" else "xmlhttprequest"
            null, "" -> Unit
            else -> return "other"
        }
        if (accept.startsWith("image/")) return "image"
        if (accept.startsWith("text/css")) return "stylesheet"
        if (accept.startsWith("text/html") || accept.startsWith("application/xhtml")) return "sub_frame"
        if (accept.startsWith("application/json")) return "xmlhttprequest"
        if (range) return "media"
        val ext = path.substringAfterLast('/').substringAfterLast('.', "")
        return when (ext) {
            "js", "mjs" -> "script"
            "css" -> "stylesheet"
            "png", "jpg", "jpeg", "gif", "webp", "avif", "svg", "ico", "bmp", "apng" -> "image"
            "woff", "woff2", "ttf", "otf", "eot" -> "font"
            "mp4", "webm", "m4v", "mov", "mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac",
            "m3u8", "mpd", "m4s" -> "media"
            "json", "xml" -> "xmlhttprequest"
            else -> if (method.equals("POST", true)) "xmlhttprequest,ping" else "script,xmlhttprequest"
        }
    }

    /**
     * The ad containers hidden while Brave's engine is not there to say more
     * - the moment before it loads, or a phone it cannot run on.
     *
     * The selectors these replaced were substring matches on class and id,
     * and they took a great deal of the web with them. "[class*='ads']"
     * matches downloads, threads, uploads and headsup; "[class*='ad-']"
     * matches thread-, upload- and header-anything; "[class*='banner']" hides
     * the banner at the top of a site the user actually wants; and
     * "iframe[src*='ad']" hides any iframe whose address contains those two
     * letters anywhere, which includes upload, download, broadcast, read and
     * load - so embedded players and maps disappeared on sites with no ads on
     * them at all.
     *
     * What is left matches things that are only ever ads: the ad libraries'
     * own generated ids, a whole class token rather than a fragment of one,
     * and iframes from the ad networks by name.
     */
    private val BASIC_CSS = listOf(
        "ins.adsbygoogle",
        "[id^='google_ads_']",
        "[id^='div-gpt-ad']",
        "[id^='aswift_']",
        "[data-ad-slot]",
        "[data-ad-client]",
        "[class~='advertisement']",
        "[id='advertisement']",
        "[aria-label='Advertisement']",
        "iframe[src*='doubleclick.net']",
        "iframe[src*='googlesyndication.com']",
        "iframe[src*='amazon-adsystem.com']"
    ).joinToString("") { "$it{display:none!important}\n" }

    /**
     * The element hiding for a page at [url], for [COSMETIC_JS], as JSON:
     * {"css", "exceptions", "generichide"} - or "" for none: blocking is off,
     * the page is not a website, or it is on a site where blocking does not
     * apply. [pageHost] is the site of the tab's page, which is the one those
     * rules are about: an ad's own frame on a site the user allows ads on is
     * left alone too.
     */
    fun cosmetic(c: Context, url: String?, pageHost: String?): String {
        if (url == null || pageHost.isNullOrEmpty() || !isEnabled(c)) return ""
        if (!url.startsWith("https://") && !url.startsWith("http://")) return ""
        if (isOwnSite(pageHost) || isLeftAlone(pageHost) || AdBlockSites.isAllowed(c, pageHost)) return ""
        // A YouTube player embedded in another site, and a bot check's frame,
        // are left alone too.
        val docHost = try { android.net.Uri.parse(url).host } catch (e: Exception) { null }
        if (isLeftAlone(docHost) || isSecurityCheck(docHost)) return ""
        AdblockEngine.warmUp(c)
        return AdblockEngine.cosmetic(url)
            ?: org.json.JSONObject().put("css", BASIC_CSS).put("generichide", true).toString()
    }

    /**
     * CSS hiding the elements with these classes and ids that the lists'
     * generic rules are about ("\n" separated, from [COSMETIC_JS]).
     * [exceptions] come back from [cosmetic] as they were given.
     */
    fun hidden(c: Context, classes: String?, ids: String?, exceptions: String?): String {
        if (!isEnabled(c)) return ""
        fun names(s: String?) = s?.split('\n')?.filter { it.isNotEmpty() }?.take(1000)?.toTypedArray() ?: emptyArray()
        return AdblockEngine.hidden(names(classes), names(ids), names(exceptions))
    }

    /**
     * Hides the ad boxes on a page: the page's own rules from the lists, then
     * the generic ones for each class and id that turns up, as it turns up.
     *
     * Run as each document starts, where the WebView can (MainActivity adds
     * it to every WebView), so the boxes never show; otherwise once the page
     * has loaded. It asks the app what to hide (SearchApp.adCosmetic), and
     * stops there when the answer is nothing - blocking off, a site allowed,
     * one of the app's own pages. The classes and ids it sends are names
     * only, each sent once, at most a few times a second; the answers are
     * style sheets. Nothing on the page is removed, only hidden.
     */
    const val COSMETIC_JS = """
(function(){
  try {
    if (window.__searchAdHide) return;
    window.__searchAdHide = 1;
    if (location.protocol !== 'https:' && location.protocol !== 'http:') return;
    var app = window.SearchApp;
    if (!app || !app.adCosmetic) return;
    var got = app.adCosmetic(location.href);
    if (!got) return;
    var r = JSON.parse(got), doc = document;
    function add(css){
      if (!css) return;
      var s = doc.createElement('style');
      s.textContent = css;
      var at = doc.head || doc.documentElement;
      if (at) at.appendChild(s);
      else doc.addEventListener('DOMContentLoaded', function(){ (doc.head || doc.documentElement).appendChild(s); });
    }
    add(r.css);
    if (r.generichide) return;
    var exc = (r.exceptions || []).join('\n'), seen = Object.create(null), count = 0, cls = [], ids = [], timer = 0;
    function note(el){
      if (count > 20000) return;
      var id = el.id;
      if (id && typeof id === 'string' && !seen['#' + id]) { seen['#' + id] = 1; ids.push(id); count++; }
      var cl = el.classList;
      if (cl) for (var i = 0; i < cl.length; i++) {
        var c = cl[i];
        if (!seen['.' + c]) { seen['.' + c] = 1; cls.push(c); count++; }
      }
    }
    function flush(){
      timer = 0;
      while (cls.length || ids.length) add(app.adHide(cls.splice(0, 1000).join('\n'), ids.splice(0, 1000).join('\n'), exc));
    }
    function later(){ if (!timer && (cls.length || ids.length)) timer = setTimeout(flush, 60); }
    function scan(node){
      if (node.nodeType !== 1) return;
      note(node);
      var all = node.getElementsByTagName('*');
      for (var i = 0; i < all.length; i++) note(all[i]);
    }
    new MutationObserver(function(ms){
      for (var i = 0; i < ms.length; i++) {
        var m = ms[i];
        if (m.type === 'attributes') note(m.target);
        else for (var j = 0; j < m.addedNodes.length; j++) scan(m.addedNodes[j]);
      }
      later();
    }).observe(doc, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'id'] });
    if (doc.documentElement) { scan(doc.documentElement); later(); }
  } catch (e) {}
})();
"""
}
