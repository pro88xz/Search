package com.search.browser

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * Lightweight ad/tracker blocker using domain matching against a bundled list.
 * Not as thorough as uBlock (no cosmetic filtering), but blocks the common
 * ad networks and trackers by intercepting their requests.
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
    private fun emptyResponse() = WebResourceResponse(
        "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
    )

    fun isEnabled(c: Context): Boolean =
        Settings.getBool(c, Settings.ADBLOCK_ENABLED, false)

    private fun isBlockedHost(host: String): Boolean {
        val h = host.lowercase().removeSuffix(".")
        return BLOCKED.any { h == it || h.endsWith("." + it) }
    }

    /** Returns a stand-in response if the request is an ad/tracker, else null. */
    fun check(c: Context, request: WebResourceRequest?): WebResourceResponse? {
        if (!isEnabled(c)) return null
        // Never the page itself. A top-level navigation is somewhere the user
        // is going - a link they tapped, or a redirect on the way to one - and
        // links in sign-up and verification emails routinely pass through
        // click trackers on this list (adjust.com, branch.io, doubleclick).
        // Answered with an empty response, the tab showed a blank page and
        // stayed there. Blocking is for what a page pulls in, not for where
        // the user is headed.
        if (request?.isForMainFrame == true) return null
        val uri = request?.url ?: return null
        val host = uri.host?.lowercase()?.removeSuffix(".") ?: return null
        val path = (uri.path ?: "").lowercase()
        if (isBlockedHost(host)) {
            return if (wantsCallbacks(host, path)) callbacksResponse() else emptyResponse()
        }
        if (isFacebookPixel(host, path)) return emptyResponse()
        if (BLOCKED_PATHS.any { (host + path).contains(it) }) return emptyResponse()
        return null
    }

    /**
     * CSS that hides ad containers.
     *
     * The previous selectors were substring matches on class and id, and they
     * took a great deal of the web with them. "[class*='ads']" matches
     * downloads, threads, uploads and headsup; "[class*='ad-']" matches
     * thread-, upload- and header-anything; "[class*='banner']" hides the
     * banner at the top of a site the user actually wants; and
     * "iframe[src*='ad']" hides any iframe whose address contains those two
     * letters anywhere, which includes upload, download, broadcast, read and
     * load - so embedded players and maps disappeared on sites with no ads on
     * them at all.
     *
     * What is left matches things that are only ever ads: the ad libraries'
     * own generated ids, a whole class token rather than a fragment of one,
     * and iframes from the ad networks by name.
     */
    fun hideCss(): String {
        val css = listOf(
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
        ).joinToString(",")
        val js = "(function(){try{var s=document.createElement('style');" +
            "s.innerHTML='" + css + "{display:none !important;visibility:hidden !important;}';" +
            "document.head.appendChild(s);}catch(e){}})();"
        return js
    }
}