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
        "facebook.net", "connect.facebook.net", "fbcdn.net/ads",
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

    /** Returns an empty response if the request is an ad/tracker, else null. */
    fun check(c: Context, request: WebResourceRequest?): WebResourceResponse? {
        if (!isEnabled(c)) return null
        val uri = request?.url ?: return null
        val host = uri.host ?: return null
        if (isBlockedHost(host)) return emptyResponse()
        val hostAndPath = (host + (uri.path ?: "")).lowercase()
        if (BLOCKED_PATHS.any { hostAndPath.contains(it) }) return emptyResponse()
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