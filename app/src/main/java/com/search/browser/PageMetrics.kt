package com.search.browser

import org.json.JSONObject

/**
 * How a page actually loaded, from the browser's own timing data rather than
 * from onPageFinished, which only says that a load ended.
 *
 * The numbers come from the page's Performance timeline (Navigation Timing,
 * Paint Timing, Largest Contentful Paint, Long Tasks and Resource Timing),
 * read by [collectJs] a moment after the page finishes, so a late large
 * picture still counts. [observeJs] is run as the page finishes, to start
 * watching for the largest paint and for long tasks; buffered, so what came
 * before it is included.
 *
 * Times are in milliseconds from the start of the navigation; -1 when the
 * page did not report one. Nothing here holds an address or page content,
 * and nothing leaves the phone.
 */
class PageMetrics(
    val dnsMs: Double,
    val connectMs: Double,
    val tlsMs: Double,
    val ttfbMs: Double,
    val domContentLoadedMs: Double,
    val loadMs: Double,
    val firstPaintMs: Double,
    val largestPaintMs: Double,
    /** Main-thread tasks over 50ms seen while the page loaded and settled. */
    val longTasks: Int,
    val longTaskMs: Double,
    val transferBytes: Long,
    val resources: Int,
    val scripts: Int,
    val images: Int,
    val domNodes: Int,
    val videos: Int,
    /** "h2", "h3", "http/1.1" - what the page itself came over. */
    val protocol: String
) {
    enum class Weight { LIGHT, MEDIUM, HEAVY }

    /**
     * Light: a static article, documentation, a plain search page. Heavy: a
     * web app, a dashboard, a video site - big transfers, many scripts, a
     * large page structure, or real time spent blocking the main thread.
     * Everything else is medium.
     */
    val weight: Weight
        get() = when {
            transferBytes > 3_000_000 || scripts > 40 || domNodes > 3000 ||
                longTaskMs > 600 || videos > 1 -> Weight.HEAVY
            transferBytes < 600_000 && scripts <= 10 && domNodes < 900 &&
                longTaskMs < 150 && videos == 0 -> Weight.LIGHT
            else -> Weight.MEDIUM
        }

    /** One line for the debug log. */
    fun summary(): String = "ttfb=" + ms(ttfbMs) + " fcp=" + ms(firstPaintMs) +
        " lcp=" + ms(largestPaintMs) + " dcl=" + ms(domContentLoadedMs) +
        " load=" + ms(loadMs) + " dns=" + ms(dnsMs) + " connect=" + ms(connectMs) +
        " tls=" + ms(tlsMs) + " longTasks=" + longTasks + "/" + ms(longTaskMs) +
        " bytes=" + transferBytes + " res=" + resources + " js=" + scripts +
        " img=" + images + " dom=" + domNodes + " video=" + videos +
        " proto=" + protocol.ifBlank { "?" } + " weight=" + weight.name.lowercase()

    private fun ms(v: Double): String = if (v < 0) "-" else v.toLong().toString()

    companion object {
        /**
         * Starts watching the largest paint and long tasks, once per page.
         * Buffered, so entries from before this ran are included.
         */
        const val observeJs = "(function(){try{" +
            "if(window.__searchPerf)return;" +
            "var s=window.__searchPerf={lcp:-1,lt:0,ltMs:0};" +
            "var t=(window.PerformanceObserver&&PerformanceObserver.supportedEntryTypes)||[];" +
            "if(t.indexOf('largest-contentful-paint')>=0){" +
            "new PerformanceObserver(function(l){var e=l.getEntries();" +
            "if(e.length)s.lcp=e[e.length-1].startTime;})" +
            ".observe({type:'largest-contentful-paint',buffered:true});}" +
            "if(t.indexOf('longtask')>=0){" +
            "new PerformanceObserver(function(l){l.getEntries().forEach(function(e){" +
            "s.lt++;s.ltMs+=e.duration;});})" +
            ".observe({type:'longtask',buffered:true});}" +
            "}catch(e){}})();"

        /** Reads everything at once, as one object, or null. */
        const val collectJs = "(function(){try{" +
            "var n=performance.getEntriesByType('navigation')[0];if(!n)return null;" +
            "var s=window.__searchPerf||{lcp:-1,lt:0,ltMs:0};" +
            "var fcp=-1;performance.getEntriesByType('paint').forEach(function(p){" +
            "if(p.name==='first-contentful-paint')fcp=p.startTime;});" +
            "var r=performance.getEntriesByType('resource'),b=n.transferSize||0,js=0,img=0;" +
            "r.forEach(function(e){b+=e.transferSize||0;" +
            "if(e.initiatorType==='script')js++;" +
            "else if(e.initiatorType==='img'||e.initiatorType==='image')img++;});" +
            "function d(a,z){return(a>0&&z>=a)?z-a:-1;}" +
            "return{dns:d(n.domainLookupStart,n.domainLookupEnd)," +
            "connect:d(n.connectStart,n.connectEnd)," +
            "tls:n.secureConnectionStart>0?d(n.secureConnectionStart,n.connectEnd):-1," +
            "ttfb:d(n.requestStart,n.responseStart)," +
            "dcl:n.domContentLoadedEventEnd>0?n.domContentLoadedEventEnd:-1," +
            "load:n.loadEventEnd>0?n.loadEventEnd:-1," +
            "fcp:fcp,lcp:s.lcp,lt:s.lt,ltMs:s.ltMs,bytes:b,res:r.length,js:js,img:img," +
            "dom:document.getElementsByTagName('*').length," +
            "video:document.getElementsByTagName('video').length," +
            "proto:n.nextHopProtocol||''};" +
            "}catch(e){return null;}})();"

        /** The result of [collectJs] as evaluateJavascript hands it back. */
        fun parse(result: String?): PageMetrics? {
            if (result.isNullOrBlank() || result == "null") return null
            return try {
                val o = JSONObject(result)
                PageMetrics(
                    dnsMs = o.optDouble("dns", -1.0),
                    connectMs = o.optDouble("connect", -1.0),
                    tlsMs = o.optDouble("tls", -1.0),
                    ttfbMs = o.optDouble("ttfb", -1.0),
                    domContentLoadedMs = o.optDouble("dcl", -1.0),
                    loadMs = o.optDouble("load", -1.0),
                    firstPaintMs = o.optDouble("fcp", -1.0),
                    largestPaintMs = o.optDouble("lcp", -1.0),
                    longTasks = o.optInt("lt", 0),
                    longTaskMs = o.optDouble("ltMs", 0.0),
                    transferBytes = o.optLong("bytes", 0L),
                    resources = o.optInt("res", 0),
                    scripts = o.optInt("js", 0),
                    images = o.optInt("img", 0),
                    domNodes = o.optInt("dom", 0),
                    videos = o.optInt("video", 0),
                    protocol = o.optString("proto", "")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
