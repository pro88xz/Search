package com.search.browser

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The home feed: technology and culture from named publishers, with one lead
 * story on top.
 *
 * [fetch] returns a JSON array that MainActivity hands to the page as
 * window.__onFeed(requestId, items). Feed contract v2, in display order:
 *
 *     title      headline, plain text
 *     link       https article URL
 *     image      https image URL, "" if none
 *     source     publisher display name, "The Verge"
 *     domain     publisher domain for its favicon, "theverge.com"
 *     category   one of [CATEGORIES]
 *     summary    plain text, at most [SUMMARY_MAX] chars, cut at a word, "" if none
 *     published  epoch millis, 0 if unknown
 *     lead       true on item 0 only, the hero
 *
 * Version 1 was {title, link, image, source}. A phone that upgrades keeps that
 * file as its offline copy until a refresh succeeds, so the page treats every
 * other field as optional.
 *
 * A refresh, in order:
 *
 *   1. Every [Source] is fetched in parallel. Each has candidate URLs, most
 *      likely first; the first that answers with a feed carrying images is
 *      remembered, so later refreshes go straight to it. A URL that answers
 *      404 or with a web page instead of a feed is skipped for a day.
 *   2. RSS 2.0, RSS 1.0 and Atom are parsed into items: headline, link, date,
 *      summary and the best image the item offers.
 *   3. Gates drop what is not a story: deals, buying guides, puzzle answers,
 *      live blogs, stale padding, headlines too short to be one.
 *   4. Items reporting the same event are clustered by headline overlap. How
 *      many publishers ran it is the momentum signal.
 *   5. Each story is scored on freshness, momentum, source tier, image,
 *      summary and how broad its audience is. The best story with a usable
 *      image and a summary leads, whatever its category, and the lead rotates
 *      during the day; the rest follow by score, woven so no category holds
 *      more than [CATEGORY_CAP] of the first [WOVEN] cards.
 *
 * Lessons the previous, world-news version of this file learned the hard way,
 * kept here: feeds pad themselves with items months or years old, so age is an
 * eligibility gate first and a ranking term second; headline clustering is a
 * ranking signal, not a cleanup step; XmlPullParser aborts on the first named
 * HTML entity unless told otherwise; and a default Java user agent gets 403
 * from some publisher CDNs.
 */
object NewsFeed {

    // ---------- categories ----------

    internal const val AI = "AI"
    internal const val MOBILE = "Mobile"
    internal const val TECH = "Tech"
    internal const val GAMING = "Gaming"
    internal const val ENTERTAINMENT = "Entertainment"
    internal const val MUSIC = "Music"
    internal const val CREATORS = "Creators"
    internal const val INTERNET = "Internet"
    internal const val DEVELOPER = "Developer"
    internal const val SECURITY = "Security"
    internal const val SPACE = "Space"
    internal const val SCIENCE = "Science"
    internal const val ROBOTICS = "Robotics"
    internal const val AUTO = "Auto"
    internal const val BUSINESS = "Business"
    internal const val SPORTS = "Sports"
    internal const val TRAVEL = "Travel"
    internal const val DESIGN = "Design"
    internal const val INTERESTING = "Interesting"

    /** The only values "category" can take. home.html may style each one. */
    internal val CATEGORIES = listOf(
        AI, MOBILE, TECH, GAMING, ENTERTAINMENT, MUSIC, CREATORS, INTERNET,
        DEVELOPER, SECURITY, SPACE, SCIENCE, ROBOTICS, AUTO, BUSINESS, SPORTS,
        TRAVEL, DESIGN, INTERESTING
    )

    private val ANY: Set<String> = CATEGORIES.toSet()

    /**
     * How wide an audience a category has, added to a story's score. This is
     * the "broad interest" term: a space or music story should be able to lead
     * on a day when it is the biggest thing, and a developer-tools story only
     * when nothing else is happening.
     */
    private val BROAD: Map<String, Double> = HashMap<String, Double>().apply {
        for (c in listOf(AI, MOBILE, GAMING, ENTERTAINMENT, MUSIC, SPACE, SCIENCE, SPORTS, INTERESTING)) put(c, 0.6)
        for (c in listOf(TECH, INTERNET, CREATORS, AUTO, ROBOTICS, TRAVEL, DESIGN, SECURITY)) put(c, 0.3)
        for (c in listOf(BUSINESS, DEVELOPER)) put(c, 0.0)
    }

    // ---------- sources ----------

    /**
     * @param id stable key for the remembered URL. Never rename one: the phone
     *   would rediscover its feed URL from scratch.
     * @param category what every item from here is, unless [refine] allows a
     *   headline rule to say otherwise.
     * @param tier 1 for original reporting with a strong editorial record, or
     *   the primary source itself; 2 for solid specialist outlets; 3 for
     *   outlets that mostly follow others.
     * @param urls candidate feed URLs, most likely first.
     * @param refine the categories a headline rule may move an item into. A
     *   general outlet such as Ars Technica covers space, security and science
     *   too; a specialist such as NASA is always what it is.
     * @param maxAgeH older items are not news. Slow publishers, the labs and
     *   agencies that post a few times a week, get a longer window.
     */
    internal class Source(
        val id: String,
        val name: String,
        val domain: String,
        val category: String,
        val tier: Int,
        val urls: List<String>,
        val refine: Set<String> = emptySet(),
        val maxAgeH: Int = 48
    )

    /**
     * The whole configuration surface. 22 publishers. Each feed is read
     * gzipped, stops after [MAX_ITEMS_PER_SOURCE] items or [MAX_BYTES]
     * unzipped, and is fetched at most every [TTL_MS]. Estimated at 300-600KB
     * a refresh, most of it the full-text feeds; the per-source DebugLog
     * lines are where to measure it on a phone.
     *
     * Every one of these publishes an image per item in its feed (media:content,
     * media:thumbnail, enclosure, or an <img> in the item body), which is what
     * an image-led page needs. The candidate URLs are the publishers' long
     * published feed addresses; none of them could be fetched from where this
     * list was written, which is why each has fallbacks and why the one that
     * works is learned on the phone and logged to DebugLog.
     *
     * Categories with no source of their own come from headline rules applied
     * to the general outlets: Creators, Internet, Developer, Security, Science,
     * Robotics, Auto, Business and Interesting. Each of those had candidates
     * that were rejected - BleepingComputer, The Register, GitHub's blog and
     * Hacker News carry no per-item image, and dedicated robotics, automotive
     * and creator-economy feeds post too little to earn a request every half
     * hour - while Ars Technica, WIRED, The Verge and TechCrunch cover all of
     * them every day. Creators also gets YouTube's own trending chart when an
     * API key is configured; see [fetchYouTube].
     */
    internal val SOURCES: List<Source> = listOf(
        // General technology. Headline rules may move any item anywhere.
        Source("verge", "The Verge", "theverge.com", TECH, 1, listOf(
            "https://www.theverge.com/rss/index.xml",
            "https://www.theverge.com/rss/full.xml"
        ), ANY),
        Source("ars", "Ars Technica", "arstechnica.com", TECH, 1, listOf(
            "https://feeds.arstechnica.com/arstechnica/index",
            "https://arstechnica.com/feed/"
        ), ANY),
        Source("wired", "WIRED", "wired.com", TECH, 1, listOf(
            "https://www.wired.com/feed/rss",
            "https://www.wired.com/feed"
        ), ANY),
        Source("techcrunch", "TechCrunch", "techcrunch.com", TECH, 1, listOf(
            "https://techcrunch.com/feed/",
            "https://feeds.feedburner.com/TechCrunch/"
        ), ANY),
        // Future plc sites. The platform serves /feeds/all; /rss is the older
        // address, which redirects.
        Source("techradar", "TechRadar", "techradar.com", TECH, 2, listOf(
            "https://www.techradar.com/feeds/all",
            "https://www.techradar.com/rss",
            "https://www.techradar.com/feeds.xml"
        ), ANY),
        Source("tomshardware", "Tom's Hardware", "tomshardware.com", TECH, 2, listOf(
            "https://www.tomshardware.com/feeds/all",
            "https://www.tomshardware.com/rss",
            "https://www.tomshardware.com/feeds.xml"
        ), setOf(AI, GAMING, SECURITY)),
        Source("windowslatest", "Windows Latest", "windowslatest.com", TECH, 3, listOf(
            "https://www.windowslatest.com/feed/"
        ), setOf(AI, SECURITY)),

        // Mobile.
        Source("androidcentral", "Android Central", "androidcentral.com", MOBILE, 2, listOf(
            "https://www.androidcentral.com/feeds/all",
            "https://www.androidcentral.com/feed",
            "https://www.androidcentral.com/feeds.xml"
        ), setOf(AI)),
        Source("9to5google", "9to5Google", "9to5google.com", MOBILE, 2, listOf(
            "https://9to5google.com/feed/"
        ), setOf(AI)),
        Source("9to5mac", "9to5Mac", "9to5mac.com", MOBILE, 2, listOf(
            "https://9to5mac.com/feed/"
        ), setOf(AI)),

        // Gaming. IGN and GameSpot cover film and TV as well.
        Source("ign", "IGN", "ign.com", GAMING, 2, listOf(
            "https://feeds.feedburner.com/ign/news",
            "https://feeds.feedburner.com/ign/all",
            "https://feeds.ign.com/ign/news"
        ), setOf(ENTERTAINMENT)),
        Source("eurogamer", "Eurogamer", "eurogamer.net", GAMING, 2, listOf(
            "https://www.eurogamer.net/feed/news",
            "https://www.eurogamer.net/feed"
        )),
        Source("gamespot", "GameSpot", "gamespot.com", GAMING, 2, listOf(
            "https://www.gamespot.com/feeds/news/",
            "https://www.gamespot.com/feeds/mashup/"
        ), setOf(ENTERTAINMENT)),

        // AI, from the labs. Few posts, but each is primary-source news, and
        // when the press covers it the same day it counts as momentum.
        Source("googleai", "Google", "blog.google", AI, 1, listOf(
            "https://blog.google/technology/ai/rss/",
            "https://blog.google/rss/"
        ), maxAgeH = 120),
        Source("openai", "OpenAI", "openai.com", AI, 1, listOf(
            "https://openai.com/news/rss.xml",
            "https://openai.com/blog/rss.xml"
        ), maxAgeH = 168),

        // Space.
        Source("nasa", "NASA", "nasa.gov", SPACE, 1, listOf(
            "https://www.nasa.gov/news-release/feed/",
            "https://www.nasa.gov/feed/",
            "https://www.nasa.gov/rss/dyn/breaking_news.rss"
        ), setOf(SCIENCE), 96),
        Source("esa", "ESA", "esa.int", SPACE, 1, listOf(
            "https://www.esa.int/rssfeed/TopNews",
            "https://www.esa.int/rssfeed/Our_Activities",
            "https://www.esa.int/rssfeed/Our_Activities/Space_Science"
        ), setOf(SCIENCE), 168),

        // Culture.
        Source("variety", "Variety", "variety.com", ENTERTAINMENT, 1, listOf(
            "https://variety.com/feed/"
        ), setOf(MUSIC)),
        Source("pitchfork", "Pitchfork", "pitchfork.com", MUSIC, 1, listOf(
            "https://pitchfork.com/rss/news/",
            "https://pitchfork.com/feed/feed-news/rss",
            "https://pitchfork.com/feed/rss"
        )),
        Source("espn", "ESPN", "espn.com", SPORTS, 2, listOf(
            "https://www.espn.com/espn/rss/news"
        )),
        Source("cntraveler", "Condé Nast Traveler", "cntraveler.com", TRAVEL, 1, listOf(
            "https://www.cntraveler.com/feed/rss"
        ), maxAgeH = 96),
        Source("dezeen", "Dezeen", "dezeen.com", DESIGN, 1, listOf(
            "https://www.dezeen.com/feed/",
            "https://feeds.feedburner.com/dezeen"
        ), maxAgeH = 72)
    )

    // ---------- tuning ----------

    /** A tech feed moves fast, but not so fast it is worth more data than this. */
    private const val TTL_MS = 30 * 60 * 1000L
    private const val CACHE_FILE = "feed_cache.json"
    private const val PREFS = "news_feed"

    private const val TIMEOUT_MS = 8000
    /**
     * Decompressed bytes read from one feed; the parser keeps what it had.
     * Full-text feeds (The Verge, 9to5, TechCrunch, Variety) carry whole
     * articles, and this still reaches their newest ten or so.
     */
    private const val MAX_BYTES = 200_000
    /** Items read from one feed. Feeds are newest first; the rest is old. */
    private const val MAX_ITEMS_PER_SOURCE = 20
    private const val THREADS = 6
    /** Wall clock for one source, all candidates included. */
    private const val SOURCE_BUDGET_MS = 20_000L
    /** Wall clock for the whole refresh. Whatever arrived by then ships. */
    private const val REFRESH_BUDGET_MS = 24_000L
    /** A URL that answered 404 or with a web page is not retried for this long. */
    private const val DEAD_MS = 24L * 60 * 60 * 1000

    private const val OUTPUT_ITEMS = 30
    /** The cards a reader sees without much scrolling, which get woven. */
    private const val WOVEN = 12
    private const val CATEGORY_CAP = 3
    /** Per publisher in the first [WOVEN], so one busy newsroom is not the page. */
    private const val SOURCE_CAP = 2

    private const val MIN_TITLE_CHARS = 20
    internal const val SUMMARY_MAX = 220
    /** Freshness halves every this many hours. */
    private const val HALF_LIFE_H = 8.0
    /** A story leads for this long before the hero rotates to something else. */
    private const val HERO_MAX_MS = 4L * 60 * 60 * 1000
    /** Fewer items than this from a refresh is a bad connection, not a feed. */
    private const val MIN_FRESH = 10
    private const val THIN_RETRY_MS = 5L * 60 * 1000
    /** After a refresh that produced nothing at all. */
    private const val EMPTY_RETRY_MS = 60L * 1000
    /** Past this age the old feed is worse than a thin new one. */
    private const val STALE_MS = 6L * 60 * 60 * 1000

    /**
     * Identifies the app honestly. Some publisher CDNs answer 403 to the
     * default "Java/1.8.0" agent, which would look exactly like an outage.
     */
    private const val UA = "Mozilla/5.0 (Linux; Android) Search/1.3 (+https://mebs.app)"
    private const val ACCEPT =
        "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.9, */*;q=0.5"

    private const val Q_NONE = 0
    private const val Q_SMALL = 1
    private const val Q_OK = 2
    private const val Q_LARGE = 3

    private const val YOUTUBE_ID = "youtube"

    @Volatile private var cache: String? = null
    /** When the cached feed was built: its real age, never moved for scheduling. */
    @Volatile private var cachedAt: Long = 0L
    /** After a thin refresh, the cached feed is served until this time. */
    @Volatile private var retryAt: Long = 0L
    private val lock = Any()

    // ---------- model ----------

    internal class Item(
        val title: String,
        val link: String,
        val image: String,
        /** [Q_NONE] .. [Q_LARGE]. Only [Q_OK] and up may lead. */
        val imageQuality: Int,
        val source: String,
        val domain: String,
        val category: String,
        val summary: String,
        val published: Long,
        val tier: Int,
        val sourceId: String,
        /** Momentum from outside the feeds; only YouTube's view counts set it. */
        val boost: Double = 0.0
    ) {
        /** Content words, for the overlap test. Computed once. */
        val tokens: Set<String> = tokenise(title)

        fun withoutImage() = Item(
            title, link, "", Q_NONE, source, domain, category, summary,
            published, tier, sourceId, boost
        )

        fun publishedAt(t: Long) = Item(
            title, link, image, imageQuality, source, domain, category, summary,
            t, tier, sourceId, boost
        )
    }

    /** A cluster of items reporting one event, represented by its best item. */
    internal class Story(
        val rep: Item,
        val outlets: Int,
        val newest: Long,
        val score: Double
    )

    /** One source's refresh: what it gave, and the log line it earned. */
    internal class SourceResult(
        val source: Source,
        val url: String,
        val items: List<Item>,
        val log: String
    )

    /** What led last time, so the hero rotates. */
    internal class LeadMemory(val link: String, val category: String, val since: Long)

    /** SharedPreferences in the app; a map in tests. */
    internal interface Memory {
        fun get(key: String): String?
        fun put(key: String, value: String?)
    }

    private class PrefsMemory(private val p: SharedPreferences) : Memory {
        override fun get(key: String): String? =
            try { p.getString(key, null) } catch (e: Exception) { null }

        override fun put(key: String, value: String?) {
            try {
                val e = p.edit()
                if (value == null) e.remove(key) else e.putString(key, value)
                e.apply()
            } catch (e: Exception) { /* best effort */ }
        }
    }

    // ---------- entry point ----------

    fun fetch(context: Context): String =
        fetchWith(context, SOURCES, System.currentTimeMillis())

    /**
     * Serialised: two home pages opening at once would otherwise fetch every
     * feed twice. The second caller waits and is served the first one's result
     * from the cache.
     */
    internal fun fetchWith(context: Context, sources: List<Source>, now: Long): String {
        synchronized(lock) { return refresh(context, sources, now) }
    }

    private fun refresh(context: Context, sources: List<Source>, now: Long): String {
        if (cache == null) loadDisk(context)

        val warm = cache
        if (warm != null && (now - cachedAt < TTL_MS || now < retryAt)) return warm

        val mem = PrefsMemory(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
        val fresh = build(context, sources, mem, now)

        if (fresh.isEmpty()) {
            // Nothing usable came back - no connection worth the name, or not
            // one story with a picture. With a feed to show, wait a minute
            // before asking every publisher again rather than doing it on each
            // home page open; a minute is short enough that a phone back
            // online soon after still gets fresh stories.
            if (warm != null) retryAt = now + EMPTY_RETRY_MS
            return warm ?: "[]"
        }

        // A handful of items is what a flaky connection produces, and a full
        // feed from an hour ago is better than it. Keep the old one and try
        // again shortly - unless the old one is itself stale or absent.
        if (fresh.size < MIN_FRESH && warm != null && now - cachedAt < STALE_MS) {
            DebugLog.add { "feed  thin refresh (" + fresh.size + " items), kept the cached feed" }
            retryAt = now + THIN_RETRY_MS
            return warm
        }

        val json = toJson(fresh)
        cache = json
        cachedAt = now
        retryAt = 0L
        writeDisk(context, json)
        return json
    }

    // ---------- gather ----------

    private fun build(context: Context, sources: List<Source>, mem: Memory, now: Long): List<Item> {
        val pool = Executors.newFixedThreadPool(THREADS)
        val results = ArrayList<SourceResult>()
        var youtube: List<Item> = emptyList()
        try {
            // Wall clock, not [now]: these bound real waiting.
            val deadline = System.currentTimeMillis() + SOURCE_BUDGET_MS
            val tasks = ArrayList<Callable<Any?>>()
            for (s in sources) tasks.add(Callable<Any?> { readSource(s, mem, now, deadline) })
            tasks.add(Callable<Any?> { fetchYouTube(context, mem, now) })

            val futures: List<Future<Any?>> =
                pool.invokeAll(tasks, REFRESH_BUDGET_MS, TimeUnit.MILLISECONDS)
            for ((i, f) in futures.withIndex()) {
                var failure = ""
                val value: Any? = try {
                    f.get()
                } catch (e: CancellationException) {
                    failure = "timed out"
                    null
                } catch (e: ExecutionException) {
                    // An Error from inside the task (StackOverflowError on a
                    // pathological feed, say) lands here rather than in the
                    // task's own catch blocks.
                    failure = "failed: " + (e.cause ?: e).javaClass.simpleName
                    null
                } catch (e: Exception) {
                    failure = "failed: " + e.javaClass.simpleName
                    null
                }
                if (i < sources.size) {
                    // Still one log line for the source.
                    results.add(
                        value as? SourceResult
                            ?: SourceResult(sources[i], "", emptyList(), failure)
                    )
                } else {
                    @Suppress("UNCHECKED_CAST")
                    youtube = (value as? List<Item>) ?: emptyList()
                }
            }
        } catch (e: Exception) {
            // Interrupted: continue with whatever was collected.
        } finally {
            pool.shutdownNow()
        }
        return assemble(results, youtube, mem, now)
    }

    /**
     * Tries the remembered URL, then the other candidates in order. A candidate
     * is accepted when it parses as a feed with items; during discovery one
     * whose items carry no image is held back in case a later candidate has
     * them. A URL that answers 4xx or with a web page is marked dead for
     * [DEAD_MS]. A network error on the remembered URL ends the attempt, since
     * that is the connection failing, not the address.
     */
    private fun readSource(src: Source, mem: Memory, now: Long, deadline: Long): SourceResult {
        val urlKey = "url:" + src.id
        val remembered = mem.get(urlKey)?.takeIf { it in src.urls }
        val order = if (remembered == null) src.urls
            else listOf(remembered) + src.urls.filter { it != remembered }

        val log = StringBuilder()
        var fallback: Pair<String, List<Item>>? = null

        fun next(): StringBuilder = if (log.isEmpty()) log else log.append(" | ")

        for (url in order) {
            if (System.currentTimeMillis() > deadline) {
                next().append("out of time")
                break
            }
            if (url != remembered && isDead(mem, url, now)) {
                next().append(DebugLog.url(url)).append(" skipped (dead)")
                continue
            }
            val r = download(url, src, deadline)
            next().append(DebugLog.url(url)).append(' ').append(r.status)
            if (r.note.isNotEmpty()) log.append(" (").append(r.note).append(')')

            val items = r.items
            if (items != null && items.isNotEmpty()) {
                if (url == remembered || items.any { it.image.isNotEmpty() }) {
                    if (url != remembered) mem.put(urlKey, url)
                    return SourceResult(src, url, items, log.toString())
                }
                if (fallback == null) fallback = url to items
                log.append(" no images")
                continue
            }
            if (r.dead) {
                markDead(mem, url, now)
                if (url == remembered) mem.put(urlKey, null)
            } else if (r.code < 0 && url == remembered) {
                break
            }
        }

        fallback?.let { (url, items) ->
            mem.put(urlKey, url)
            return SourceResult(src, url, items, log.toString())
        }
        return SourceResult(src, "", emptyList(), log.toString())
    }

    private fun isDead(mem: Memory, url: String, now: Long): Boolean {
        val until = mem.get("dead:" + url)?.toLongOrNull() ?: return false
        return now < until
    }

    private fun markDead(mem: Memory, url: String, now: Long) {
        mem.put("dead:" + url, (now + DEAD_MS).toString())
    }

    private class Download(
        val code: Int,
        val items: List<Item>?,
        val dead: Boolean,
        val note: String
    ) {
        val status: String get() = if (code > 0) code.toString() else "error"
    }

    private fun download(url: String, src: Source, deadline: Long): Download {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", ACCEPT)
                // Asked for explicitly, and so decoded here: Android only
                // unzips transparently when it added the header itself, and
                // this way the same code runs in the JVM test harness.
                setRequestProperty("Accept-Encoding", "gzip")
            }
            val code = conn.responseCode
            if (code != 200) {
                // 408 and 429 are the server being busy, not the URL being wrong.
                val dead = code in 400..499 && code != 408 && code != 429
                return Download(code, null, dead, "")
            }
            var raw: InputStream = conn.inputStream
            if ("gzip".equals(conn.contentEncoding, ignoreCase = true)) raw = GZIPInputStream(raw)
            val capped = Capped(raw, MAX_BYTES, deadline)
            val stream = BufferedInputStream(capped, 16 * 1024)
            if (!sniffFeed(stream)) return Download(200, null, true, "not a feed")
            val parsed = stream.use { parseFeed(it, src, url) }
            val note = when {
                !parsed.isFeed -> "not a feed"
                capped.hitCap -> "capped at " + (MAX_BYTES / 1000) + "KB"
                parsed.note.isNotEmpty() -> parsed.note
                else -> ""
            }
            Download(200, if (parsed.isFeed) parsed.items else null, !parsed.isFeed, note)
        } catch (e: Exception) {
            Download(-1, null, false, e.javaClass.simpleName + (e.message?.let { ": " + it.take(60) } ?: ""))
        } finally {
            try { conn?.disconnect() } catch (e: Exception) { }
        }
    }

    /**
     * Looks at the first bytes without consuming them. A web page answering at
     * a feed's address - a moved feed redirecting to the home page, a soft 404
     * - is recognised here, before reading 200KB of it.
     */
    private fun sniffFeed(s: BufferedInputStream): Boolean {
        s.mark(4096)
        val buf = ByteArray(2048)
        var n = 0
        while (n < buf.size) {
            val r = s.read(buf, n, buf.size - n)
            if (r <= 0) break
            n += r
        }
        s.reset()
        val head = String(buf, 0, n, Charsets.ISO_8859_1).lowercase(Locale.US)
        if (head.contains("<rss") || head.contains("<feed") || head.contains("<rdf")) return true
        return !(head.contains("<html") || head.contains("<!doctype html"))
    }

    /** Ends the stream at [max] bytes, and fails it once [deadline] passes. */
    private class Capped(input: InputStream, private val max: Int, private val deadline: Long) :
        FilterInputStream(input) {
        private var count = 0
        var hitCap = false
            private set

        override fun read(): Int {
            if (count >= max) { hitCap = true; return -1 }
            if (System.currentTimeMillis() > deadline) throw IOException("source time budget spent")
            val b = super.read()
            if (b >= 0) count++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (count >= max) { hitCap = true; return -1 }
            if (System.currentTimeMillis() > deadline) throw IOException("source time budget spent")
            val n = super.read(b, off, minOf(len, max - count))
            if (n > 0) count += n
            return n
        }

        override fun markSupported(): Boolean = false
    }

    // ---------- assemble: gate, cluster, rank, arrange ----------

    /**
     * Everything after the network, kept apart so it can be run on sample feeds.
     * Logs one line per source: the URLs tried and how each answered, then
     * items parsed and items kept.
     */
    internal fun assemble(
        results: List<SourceResult>,
        youtube: List<Item>,
        mem: Memory,
        now: Long
    ): List<Item> {
        val kept = ArrayList<Item>()
        var parsedTotal = 0
        for (r in results) {
            val k = gate(r.items, r.source, now)
            kept.addAll(k)
            parsedTotal += r.items.size
            DebugLog.add {
                "feed  " + r.source.name + "  " +
                (if (r.log.isEmpty()) "no request" else r.log) +
                "  parsed " + r.items.size + "  kept " + k.size }
        }
        kept.addAll(youtube)

        val stories = rank(kept, now)
        val last = readLead(mem)
        val out = arrange(stories, now, last)
        if (out.isNotEmpty()) writeLead(mem, out[0], last, now)

        DebugLog.add {
            "feed  refresh: " + results.count { it.items.isNotEmpty() } + "/" + results.size +
            " sources, parsed " + parsedTotal + ", kept " + kept.size +
            ", stories " + stories.size + ", shown " + out.size +
            (if (out.isEmpty()) "" else ", lead [" + out[0].category + "] " + out[0].source + ": " + out[0].title.take(60)) }
        return out
    }

    private fun readLead(mem: Memory): LeadMemory? {
        val link = mem.get("lead_link") ?: return null
        return LeadMemory(
            link,
            mem.get("lead_cat") ?: "",
            mem.get("lead_since")?.toLongOrNull() ?: 0L
        )
    }

    private fun writeLead(mem: Memory, lead: Item, last: LeadMemory?, now: Long) {
        val since = if (last != null && sameLink(last.link, lead.link)) last.since else now
        mem.put("lead_link", lead.link)
        mem.put("lead_cat", lead.category)
        mem.put("lead_since", since.toString())
    }

    // ---------- the gates ----------

    private val JUNK_LINK = Regex(
        "/live/|/live-(blog|updates|news|coverage)|/videos?/|/podcasts?/|/deals?/|/coupons?/" +
        "|/best/|/best-picks/|/buying-guides?/|/gift-guides?/|/sponsored/|/partner-content/" +
        "|/shopping/|/quiz|/galler(y|ies)/|/av/|/programmes/|/iplayer/",
        RegexOption.IGNORE_CASE
    )

    /**
     * What a tech feed fills up with that is not a story. Each line is a family
     * seen in these publishers' feeds: live and multimedia stubs, the daily
     * puzzle-answer posts, shopping, buying guides, betting copy and roundups.
     * Written to miss news that shares a word with them: "Best Buy closes
     * stores", "Live Nation sued", "Apple picks TSMC", "the best-selling Mac
     * since 2021", "Spotify's podcast business" all pass.
     *
     * Every colon inside [] is escaped. Android compiles these with ICU, which
     * reads "[:" and ":]" as the edges of a [:name:] property and rejects the
     * pattern - and a pattern that fails here fails NewsFeed's initialisation,
     * which crashed the app on launch. The JVM accepts both forms, so a desktop
     * test does not catch it.
     */
    private val JUNK_TITLE = Regex(
        "^(watch|listen)\\b|^(video|live|podcast|newsletter|quiz)\\s*[\\:|\\-–—]" +
        "|\\b(live ?blog|live updates|as it happened|sponsored|giveaway|webinar|horoscope|quiz)\\b" +
        "|\\b(wordle|quordle|nyt connections|connections (hints|answers|today)|nyt strands|strands (hints|answers|today)" +
        "|spelling bee|crossword|hints? and answers?|today'?s answers?)\\b" +
        "|\\b(prime day|black friday|cyber monday|big deal days|coupons?|promo codes?|discount codes?|price drop" +
        "|lowest price|record[- ]low price|all-time low price|best deals?|deals? of the|deal alert|early deals)\\b" +
        "|\\b\\d{1,2}% off\\b" +
        "|\\b(save|drops? to|dropped to|falls? to|fell to|down to|slashed to|reduced to)\\b.{0,30}[$£€]\\s?\\d" +
        "|[$£€]\\s?\\d[\\d,.]*\\s?off\\b|\\bfor (just|only) [$£€]\\s?\\d" +
        "|\\b(how to watch|where to watch|live streams? (free|online)|free live stream)\\b" +
        "|^(the )?best\\b(?!-| buy)|\\bbest\\b(?!-)[^.\\:]{0,60}\\b(of|in|for) 20\\d\\d\\b|\\b(buying guide|gift guide|gift ideas)\\b" +
        "|\\b(best bets|betting (odds|lines|picks|tips)|parlays?|odds,? picks|(expert|staff) picks" +
        "|fantasy (football|baseball|basketball|hockey)|waiver wire)\\b" +
        "|\\b(round-?up|recap|this week in|week in review)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Feed logos, avatars and tracking pixels offered as an item's image. */
    private val JUNK_IMAGE = Regex(
        "[-_/]logo[-_]?(\\d+|white|black|dark|light|colou?r|small|large|square)?\\.(png|jpe?g|gif|webp)|/logos?/" +
        "|placeholder|default[-_]?(image|thumb|og)|/avatars?/|[-_/]avatar\\.|favicon|/icons?/|spacer|blank\\.gif" +
        "|/pixel\\.|1x1\\.|feedburner\\.com|feeds\\.feedblitz|gravatar\\.com|/emoji/|s\\.w\\.org/images" +
        "|share[-_]?button|badge|\\.svg(\\?|$)|doubleclick|/ads?/",
        RegexOption.IGNORE_CASE
    )

    /**
     * One source's items, filtered. The gates were set against what tech and
     * culture feeds actually carry: puzzle answers and deals posts every day,
     * evergreen guides re-dated to look new, the odd year-old item.
     *
     * No image is not a gate here. An imageless item still joins its story's
     * cluster and counts toward that story's momentum - the story is shown
     * through a copy from an outlet that has a picture - but it is never shown
     * itself (see arrange).
     */
    internal fun gate(items: List<Item>, src: Source, now: Long): List<Item> {
        // An image a source attaches to three or more items is its logo or a
        // default card, not a picture of the story.
        val imageUses = HashMap<String, Int>()
        for (it in items) if (it.image.isNotEmpty()) imageUses[it.image] = (imageUses[it.image] ?: 0) + 1

        val clean = items.filter {
            it.title.length >= MIN_TITLE_CHARS &&
            it.link.startsWith("https://") &&
            !JUNK_LINK.containsMatchIn(it.link) &&
            !JUNK_TITLE.containsMatchIn(it.title)
        }.map { if ((imageUses[it.image] ?: 0) >= 3) it.withoutImage() else it }

        // No date readable anywhere in this feed means a format nobody here
        // reads - the publisher switched to something new. Keeping its newest
        // few, undated, beats losing the source; they are scored as a day old.
        if (clean.isNotEmpty() && clean.none { it.published > 0L }) return clean.take(6)

        val maxAge = src.maxAgeH * 3_600_000L
        return clean.filter {
            it.published > 0L &&
            now - it.published <= maxAge &&
            // A few hours ahead is a timezone mistake; more is not a scoop.
            it.published - now <= 6L * 3_600_000L
        }.map {
            // ...and the page should not show "in 3 hours" for it.
            if (it.published > now) it.publishedAt(now) else it
        }
    }

    // ---------- categories by headline ----------

    private class Rule(val category: String, pattern: String) {
        val re = Regex(pattern, RegexOption.IGNORE_CASE)
    }

    /**
     * Headline rules, in priority order; the first that matches decides. Title
     * only, so the label is predictable from what the reader sees. A source
     * accepts the decision only if the category is in its [Source.refine] set;
     * otherwise the item keeps the source's own category - so for IGN, a match
     * on the Gaming rule settles it as Gaming before the Entertainment rule
     * ("trailer") is ever reached.
     *
     * Order matters where words overlap: Mobile comes before Space so a phone
     * with satellite messaging or a Samsung Galaxy stays a phone story, and
     * "satellite" alone is not a space word at all.
     */
    private val RULES: List<Rule> = listOf(
        Rule(SECURITY, "\\b(hack(s|ed|ers?|ing)?|breach(es|ed)?|ransomware|malware|spyware|phishing|vulnerabilit(y|ies)" +
            "|zero-days?|exploit(s|ed)?|cyber ?attacks?|cybersecurity|cybercrim\\w*|data leaks?|botnets?|backdoors?" +
            "|infostealers?|scam(s|mers?)?|cve-\\d+|password managers?)\\b"),
        Rule(AI, "\\b(ai|a\\.i\\.|artificial intelligence|chatgpt|openai|gpt-?\\d[\\w.]*|gemini|claude|anthropic|llms?" +
            "|copilot|deepmind|machine learning|chatbots?|grok|xai|mistral|llama|perplexity|midjourney|sora|deepseek" +
            "|agi|apple intelligence|generative)\\b"),
        Rule(ROBOTICS, "\\b(robots?|robotics|humanoids?|boston dynamics|exoskeletons?)\\b"),
        Rule(AUTO, "\\b(cars?|evs?|electric vehicles?|tesla|rivian|lucid|waymo|robotaxis?|self-driving" +
            "|autonomous (cars?|vehicles?|driving)|cybertruck|byd|porsche|ferrari|lamborghini|polestar|volkswagen" +
            "|toyota|hyundai|kia|ford|general motors|bmw|mercedes(-benz)?|carplay|android auto|superchargers?|ev charging)\\b"),
        Rule(GAMING, "\\b(video ?games?|gaming|gamers?|playstation|ps5|ps6|xbox|nintendo|switch 2|steam deck|steam machine" +
            "|on steam|valve|esports|gta( vi| 6)?|grand theft auto|minecraft|fortnite|roblox|zelda|mario|pok[eé]mon" +
            "|call of duty|elden ring|halo|game pass|ea sports|indie games?)\\b"),
        Rule(MOBILE, "\\b(iphones?|ipads?|ios|ipados|android|pixel|samsung|galaxy (s|z|a|tab|watch|buds|ring|book|fold|flip)\\w*" +
            "|smartphones?|phones?|oneplus|xiaomi|motorola|foldables?|airpods|apple watch|wear ?os|watchos|one ui)\\b"),
        Rule(SPACE, "\\b(nasa|spacex|esa|starship|starliner|rockets?|orbit(s|al|ing)?|astronauts?|cosmonauts?|moon|lunar" +
            "|mars|martian|asteroids?|comets?|galaxies|milky way|andromeda|telescopes?|jwst|webb|hubble|space station" +
            "|iss|exoplanets?|black holes?|artemis|blue origin|nebula|solar system|spacecraft|spacewalk" +
            "|interstellar|supernova|meteors?|cosmic)\\b"),
        Rule(CREATORS, "\\b(youtube|youtubers?|tiktok|creators?|influencers?|live ?streamers?|twitch|mrbeast|vtubers?" +
            "|patreon|substack|onlyfans)\\b"),
        Rule(INTERNET, "\\b(memes?|viral|reddit|twitter|x\\.com|bluesky|threads|mastodon|instagram|facebook|meta|whatsapp" +
            "|social media|social networks?|wikipedia|discord|snapchat|pinterest|tumblr|4chan)\\b"),
        Rule(MUSIC, "\\b(music|albums?|songs?|singers?|songwriters?|rappers?|concerts?|world tour|tour dates|grammys?" +
            "|spotify|apple music|tidal|billboard|vinyl|playlists?|soundtracks?|musicians?|k-?pop|record label" +
            "|taylor swift|beyonc[eé]|drake|bts|kendrick lamar|bad bunny)\\b"),
        Rule(ENTERTAINMENT, "\\b(movies?|films?|filmmakers?|tv shows?|tv series|season \\d+|episodes?|trailers?|netflix" +
            "|disney\\+?|hbo|hulu|prime video|apple tv\\+?|paramount\\+?|peacock|marvel|star wars|box office|oscars?" +
            "|emmys?|golden globes|hollywood|actors?|actress(es)?|sequel|prequel|reboot|anime|showrunners?|sitcom" +
            "|documentar(y|ies)|cinema|blockbuster)\\b"),
        Rule(INTERESTING, "\\b(archaeolog\\w*|ancient|fossils?|dinosaurs?|mumm(y|ies)|shipwrecks?|treasure|mysterious" +
            "|bizarre|weird|world'?s (oldest|largest|biggest|smallest|first|longest|fastest|deepest|tallest)" +
            "|record-breaking|guinness|centuries-old|prehistoric|extinct)\\b"),
        Rule(SCIENCE, "\\b(scientists?|study|studies|research(ers)?|physics|physicists?|biolog\\w*|chemistry|chemists?" +
            "|climate|species|evolution(ary)?|brain|neuro\\w*|genes?|genetic\\w*|genome|dna|rna|quantum|vaccines?" +
            "|virus(es)?|cancer|disease|medical|medicine|ocean|volcan\\w*|earthquakes?|nobel|particle|fusion|cern" +
            "|microb\\w*|bacteria|protein)\\b"),
        Rule(DEVELOPER, "\\b(developers?|devs|programming|programmers?|coding|coders?|github|gitlab|open[- ]source|apis?" +
            "|sdks?|javascript|typescript|python|rust|golang|kotlin|swiftui|xcode|android studio|vs ?code" +
            "|visual studio|compilers?|linux|kernel|npm|docker|kubernetes|webassembly|devops|stack overflow)\\b"),
        Rule(BUSINESS, "\\b(raises?|raised|funding|valuation|ipo|acquires?|acquisition|acquired|merger|layoffs?|lays off" +
            "|startups?|venture|investors?|earnings|revenue|profits?|stocks?|antitrust|lawsuits?|sues|sued|ceo|ftc" +
            "|doj|tariffs?|billion|regulators?|fined)\\b"),
        Rule(SPORTS, "\\b(nfl|nba|wnba|mlb|nhl|mls|fifa|uefa|world cup|premier league|champions league|olympics?|olympic" +
            "|super bowl|f1|formula (1|one)|grand prix|tennis|golf|soccer|football|basketball|baseball|hockey" +
            "|cricket|boxing|ufc|wwe|wimbledon|athletes?)\\b"),
        Rule(DESIGN, "\\b(design(s|ers?|ed)?|redesign(s|ed)?|typography|typefaces?|fonts?|logos?|architecture|architects?" +
            "|interiors?|furniture|branding|rebrand(s|ed|ing)?|illustrat\\w+|artists?|artworks?|museums?" +
            "|exhibitions?|photography|photographers?|sculptures?)\\b"),
        Rule(TRAVEL, "\\b(travel(s|ers?|ing)?|airlines?|airports?|flights?|hotels?|tourism|tourists?|vacations?" +
            "|passports?|airbnb|expedia|cruises?|tsa|road trips?|resorts?)\\b")
    )

    internal fun categorise(title: String, src: Source): String {
        if (src.refine.isEmpty()) return src.category
        for (r in RULES) {
            if (r.re.containsMatchIn(title)) {
                return if (r.category in src.refine) r.category else src.category
            }
        }
        return src.category
    }

    // ---------- clustering: the momentum signal ----------

    private val STOPWORDS = setOf(
        "the", "and", "for", "with", "from", "that", "this", "these", "those",
        "his", "her", "its", "their", "they", "she", "was", "were", "been",
        "are", "has", "have", "had", "will", "would", "could", "should", "may",
        "says", "say", "said", "after", "over", "into", "about", "amid", "but",
        "not", "you", "your", "out", "more", "than", "new", "how", "why",
        "what", "who", "all", "can", "off", "own", "too", "now", "way", "get",
        "one", "two", "near", "back", "being", "against", "just", "here",
        "heres", "gets", "via", "first", "finally", "today"
    )

    private val NON_WORD = Regex("[^\\p{L}\\p{N} ]")

    /**
     * Content words, with trailing plurals folded so "agency sites" and
     * "another site" can meet. Letters in any script count, so "Pokémon" stays
     * one word instead of two fragments.
     */
    internal fun tokenise(title: String): Set<String> =
        NON_WORD.replace(title.lowercase(Locale.ROOT).replace("'s ", " ").replace("’s ", " "), " ")
            .split(' ')
            .filter { it.length > 2 && it !in STOPWORDS }
            .map { w ->
                if (w.length > 4 && w.endsWith("s") && !w.endsWith("ss")) w.dropLast(1) else w
            }
            .toSet()

    /**
     * How much of the shorter headline must appear in the longer one. Tuned by
     * the previous version of this file against fourteen real headline pairs:
     * 0.55 is the loosest value that merged anything 0.60 did not, while the
     * nearest pair that must stay apart scored 0.43.
     */
    private const val SAME_STORY_RATIO = 0.55

    /**
     * Containment rather than Jaccard, so a terse headline still matches a
     * verbose one about the same event; three shared words minimum, so two
     * stories sharing a company name and a verb are never fused.
     */
    private fun sameStory(a: Set<String>, b: Set<String>): Boolean {
        if (a.size < 3 || b.size < 3) return false
        var shared = 0
        for (t in a) if (t in b) shared++
        if (shared < 3) return false
        return shared.toDouble() / minOf(a.size, b.size) >= SAME_STORY_RATIO
    }

    private val TRACKING_PARAM = Regex("(?i)[?&](utm_[a-z]+|ref|src|cmpid|mbid|ito|rss|taid)=[^&#]*")

    private fun linkKey(link: String): String =
        TRACKING_PARAM.replace(link.substringBefore('#'), "")
            .removePrefix("https://").removePrefix("http://").removePrefix("www.")
            .trimEnd('/', '?', '&')
            .lowercase(Locale.ROOT)

    private fun sameLink(a: String, b: String) = linkKey(a) == linkKey(b)

    private fun cluster(items: List<Item>): List<List<Item>> {
        val clusters = ArrayList<MutableList<Item>>()
        val keys = ArrayList<Set<String>>()

        // One item per article first. When an article appears twice - listed
        // again after an edit, or reached through a tracking link - the copy
        // with the picture and the summary is the one kept, not merely the
        // newer one.
        val byLink = LinkedHashMap<String, Item>()
        for (it in items) {
            val k = linkKey(it.link)
            val had = byLink[k]
            if (had == null || betterCopy(it, had)) byLink[k] = it
        }

        // Freshest first, so the newest wording becomes the one every later
        // duplicate is compared against.
        outer@ for (it in byLink.values.sortedByDescending { it.published }) {
            for (i in clusters.indices) {
                if (sameStory(it.tokens, keys[i])) {
                    clusters[i].add(it)
                    continue@outer
                }
            }
            clusters.add(arrayListOf(it))
            keys.add(it.tokens)
        }
        return clusters
    }

    private fun betterCopy(a: Item, b: Item): Boolean {
        if (a.imageQuality != b.imageQuality) return a.imageQuality > b.imageQuality
        if (a.summary.isEmpty() != b.summary.isEmpty()) return a.summary.isNotEmpty()
        return a.published > b.published
    }

    // ---------- ranking ----------

    internal fun rank(items: List<Item>, now: Long): List<Story> {
        val stories = ArrayList<Story>()
        for (c in cluster(items)) {
            // The face of the story: an article over a video, then the best
            // picture, a summary, the stronger publisher, the newest.
            val rep = c.sortedWith(
                compareBy<Item>(
                    { if (it.sourceId == YOUTUBE_ID) 1 else 0 },
                    { -it.imageQuality },
                    { if (it.summary.isEmpty()) 1 else 0 },
                    { it.tier },
                    { -it.published }
                )
            ).first()
            val outlets = c.map { it.sourceId }.distinct().size
            val newest = c.maxOf { it.published }
            stories.add(Story(rep, outlets, newest, score(rep, outlets, newest, now)))
        }
        return stories.sortedWith(
            compareByDescending<Story> { it.score }.thenByDescending { it.newest }
        )
    }

    /**
     * One number per story. Typical ranges, to read the weights against:
     *
     *   freshness   4.0 just published, 2.0 at 8h, 1.0 at 16h, 0.25 at 32h
     *   momentum    +2 per extra publisher running it, up to +8
     *   tier        1.5 / 0.9 / 0.4
     *   image       1.0 large, 0.6 usable, 0.1 thumbnail, -3 none
     *   summary     0.5
     *   audience    0.6 broad categories, 0.3 general, 0 niche
     *
     * So a story on three outlets six hours ago (2.4 + 4 + ...) beats a scoop
     * from one outlet ten minutes ago (4 + 0 + ...), and a fresh single-source
     * story beats a day-old one. A video from YouTube's trending chart is
     * treated as six hours old, with its view count standing in for momentum.
     */
    internal fun score(rep: Item, outlets: Int, newest: Long, now: Long): Double {
        val ageH = when {
            rep.sourceId == YOUTUBE_ID -> 6.0
            newest <= 0L -> 24.0
            else -> (now - newest).coerceAtLeast(0L) / 3_600_000.0
        }
        val freshness = 4.0 * 0.5.pow(ageH / HALF_LIFE_H)
        val momentum = 2.0 * (outlets - 1).coerceIn(0, 4) + rep.boost
        val tier = when (rep.tier) { 1 -> 1.5; 2 -> 0.9; else -> 0.4 }
        val image = when (rep.imageQuality) { Q_LARGE -> 1.0; Q_OK -> 0.6; Q_SMALL -> 0.1; else -> -3.0 }
        val summary = if (rep.summary.length >= 60) 0.5 else 0.0
        val audience = BROAD[rep.category] ?: 0.0
        return freshness + momentum + tier + image + summary + audience
    }

    /**
     * The lead, then the rest.
     *
     * The lead is the best-scoring story that can carry a hero card: a usable
     * image, a summary, and an article rather than a video. Category does not
     * enter into it beyond the score, so on a quiet tech day a NASA launch or
     * a film premiere leads. Two adjustments keep the hero moving: another
     * story from the category that led last time is marked down a little, and
     * a story that has led for [HERO_MAX_MS] is marked down a lot, so the hero
     * changes during the day instead of sitting on one story until it ages out.
     *
     * After it, stories follow in score order, except that within the first
     * [WOVEN] no category takes more than [CATEGORY_CAP] places and no
     * publisher more than [SOURCE_CAP]. Each slot takes the best story that
     * fits; when nothing fits, the publisher limit gives first, then the
     * category one. Every story shown has a picture that holds up full width:
     * the page draws each one as a full-width card, and the owner's rule is
     * that a story without a proper image is not shown at all. Known-small
     * thumbnails and stories with no image are left out rather than placed
     * last.
     */
    internal fun arrange(stories: List<Story>, now: Long, last: LeadMemory?): List<Item> {
        if (stories.isEmpty()) return emptyList()

        fun leadScore(s: Story): Double {
            var v = s.score
            if (last != null) {
                val same = sameLink(s.rep.link, last.link)
                if (!same && s.rep.category == last.category) v -= 0.8
                if (same && now - last.since >= HERO_MAX_MS) v -= 3.0
            }
            return v
        }

        val lead = stories
            .filter { it.rep.imageQuality >= Q_OK && it.rep.summary.isNotEmpty() && it.rep.sourceId != YOUTUBE_ID }
            .maxByOrNull { leadScore(it) }
            ?: stories.filter { it.rep.imageQuality >= Q_OK && it.rep.sourceId != YOUTUBE_ID }
                .maxByOrNull { leadScore(it) }
            ?: return emptyList()

        val out = ArrayList<Item>()
        out.add(lead.rep)
        val perCategory = HashMap<String, Int>()
        val perSource = HashMap<String, Int>()
        perCategory[lead.rep.category] = 1
        perSource[lead.rep.sourceId] = 1

        // Only pictures that survive being shown full width.
        val pool = stories.filter { it !== lead && it.rep.imageQuality >= Q_OK }.toMutableList()

        while (out.size < WOVEN && pool.isNotEmpty()) {
            fun catOk(s: Story) = (perCategory[s.rep.category] ?: 0) < CATEGORY_CAP
            fun srcOk(s: Story) = (perSource[s.rep.sourceId] ?: 0) < SOURCE_CAP
            var i = pool.indexOfFirst { catOk(it) && srcOk(it) }
            if (i < 0) i = pool.indexOfFirst { catOk(it) }
            if (i < 0) i = 0
            val s = pool.removeAt(i)
            out.add(s.rep)
            perCategory[s.rep.category] = (perCategory[s.rep.category] ?: 0) + 1
            perSource[s.rep.sourceId] = (perSource[s.rep.sourceId] ?: 0) + 1
        }
        val rest = pool.sortedWith(compareByDescending<Story> { it.score }.thenByDescending { it.newest })
        for (s in rest) { if (out.size >= OUTPUT_ITEMS) break; out.add(s.rep) }
        return out
    }

    // ---------- parsing ----------

    internal class Parsed(val items: List<Item>, val isFeed: Boolean, val note: String)

    /** One item as the XML gave it, before any cleaning. */
    private class Raw {
        var title = ""
        var link = ""
        var origLink = ""
        var guid = ""
        var date = ""
        var updated = ""
        var description = ""
        var content = ""
        val media = ArrayList<Pair<String, Int>>()
        val thumbs = ArrayList<Pair<String, Int>>()
        var enclosure = ""
        var itunes = ""
        var plainImage = ""
    }

    private const val RELAXED = "http://xmlpull.org/v1/doc/features.html#relaxed"

    internal fun parseFeed(xml: String, src: Source, base: String): Parsed =
        parseFeed(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)), src, base)

    /**
     * RSS 2.0, RSS 1.0 and Atom, from a stream, stopping at
     * [MAX_ITEMS_PER_SOURCE] items so the rest of a long feed is never
     * downloaded.
     *
     * Namespace processing is off, so elements arrive by their written name -
     * "media:content", "content:encoded" - and a feed that uses the media
     * prefix without declaring it, which happens, still parses. Relaxed mode
     * keeps an undeclared HTML entity (&nbsp;, &rsquo;) or a bare ampersand
     * from aborting the whole feed; entities are decoded afterwards.
     *
     * A feed that breaks partway, or is cut off by the byte cap, still
     * contributes every item that closed before the break.
     */
    internal fun parseFeed(input: InputStream, src: Source, base: String): Parsed {
        val out = ArrayList<Item>()
        var isFeed = false
        var note = ""
        try {
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            try { p.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) } catch (e: Exception) { }
            try { p.setFeature(RELAXED, true) } catch (e: Exception) { }
            p.setInput(input, null)

            var raw: Raw? = null
            var itemDepth = -1
            var sawRoot = false
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    val name = p.name ?: ""
                    val colon = name.indexOf(':')
                    val prefix = if (colon > 0) name.substring(0, colon).lowercase(Locale.ROOT) else ""
                    val local = name.substring(colon + 1).lowercase(Locale.ROOT)
                    if (!sawRoot) {
                        sawRoot = true
                        if (local == "html") return Parsed(out, false, "web page")
                        isFeed = local == "rss" || local == "feed" || local == "rdf"
                    }
                    val r = raw
                    if (r == null) {
                        if (prefix.isEmpty() && (local == "item" || local == "entry")) {
                            raw = Raw()
                            itemDepth = p.depth
                        }
                    } else {
                        readField(p, r, prefix, local)
                    }
                } else if (ev == XmlPullParser.END_TAG && raw != null && p.depth == itemDepth) {
                    finish(raw, src, base)?.let { out.add(it) }
                    raw = null
                    if (out.size >= MAX_ITEMS_PER_SOURCE) break
                }
                ev = p.next()
            }
        } catch (e: Exception) {
            note = "parse stopped: " + e.javaClass.simpleName
        }
        return Parsed(out, isFeed || out.isNotEmpty(), note)
    }

    private fun attr(p: XmlPullParser, name: String): String =
        (p.getAttributeValue(null, name) ?: "").trim()

    private fun readField(p: XmlPullParser, r: Raw, prefix: String, local: String) {
        when {
            prefix.isEmpty() && local == "title" -> {
                val t = readText(p)
                if (r.title.isEmpty()) r.title = t
            }

            // RSS puts the URL in the text; Atom puts it in href, listing
            // several with different rel values, of which only alternate (or
            // none) is the article. rel=enclosure with an image type is a
            // picture.
            (prefix.isEmpty() || prefix == "atom") && local == "link" -> {
                val href = attr(p, "href")
                if (href.isNotEmpty()) {
                    val rel = attr(p, "rel").lowercase(Locale.ROOT)
                    val type = attr(p, "type").lowercase(Locale.ROOT)
                    if (rel == "enclosure" && type.startsWith("image")) {
                        if (r.enclosure.isEmpty()) r.enclosure = href
                    } else if ((rel.isEmpty() || rel == "alternate") && r.link.isEmpty() &&
                        !type.contains("xml") && !type.contains("json")) {
                        r.link = href
                    }
                } else if (prefix.isEmpty()) {
                    val t = readText(p).trim()
                    if (r.link.isEmpty()) r.link = t
                }
            }

            // Feedburner rewrites links through its own tracker and keeps the
            // real one here.
            prefix == "feedburner" && local == "origlink" -> r.origLink = readText(p).trim()

            prefix.isEmpty() && local == "guid" -> {
                val perma = attr(p, "isPermaLink")
                val t = readText(p).trim()
                if (!perma.equals("false", true) && t.startsWith("http")) r.guid = t
            }

            prefix.isEmpty() && (local == "pubdate" || local == "published" || local == "issued") ->
                r.date = readText(p).trim()
            prefix == "dc" && local == "date" -> {
                val t = readText(p).trim()
                if (r.date.isEmpty()) r.date = t
            }
            local == "updated" || local == "modified" -> r.updated = readText(p).trim()

            prefix.isEmpty() && (local == "description" || local == "summary") -> {
                val t = readText(p)
                if (r.description.isEmpty()) r.description = t
            }
            prefix == "content" && local == "encoded" -> r.content = readText(p)
            // Atom content: escaped HTML, plain text, or inline XHTML. With a
            // src it lives elsewhere and there is nothing to read.
            prefix.isEmpty() && local == "content" ->
                if (attr(p, "src").isEmpty()) r.content = readText(p)

            prefix == "media" && local == "content" -> {
                val url = attr(p, "url")
                val medium = attr(p, "medium").lowercase(Locale.ROOT)
                val type = attr(p, "type").lowercase(Locale.ROOT)
                val isImage = when {
                    medium == "image" -> true
                    medium.isNotEmpty() -> false
                    type.startsWith("image") -> true
                    type.isNotEmpty() -> false
                    else -> looksLikeImage(url)
                }
                if (url.isNotEmpty() && isImage) r.media.add(url to (attr(p, "width").toIntOrNull() ?: 0))
            }
            prefix == "media" && local == "thumbnail" -> {
                val url = attr(p, "url")
                if (url.isNotEmpty()) r.thumbs.add(url to (attr(p, "width").toIntOrNull() ?: 0))
            }
            // Enclosure carries podcast audio as often as a picture.
            prefix.isEmpty() && local == "enclosure" -> {
                val url = attr(p, "url")
                val type = attr(p, "type").lowercase(Locale.ROOT)
                if (r.enclosure.isEmpty() && url.isNotEmpty() &&
                    (type.startsWith("image") || (type.isEmpty() && looksLikeImage(url)))) {
                    r.enclosure = url
                }
            }
            prefix == "itunes" && local == "image" -> {
                val href = attr(p, "href")
                if (href.isNotEmpty() && r.itunes.isEmpty()) r.itunes = href
            }
            // Not in any spec, but some feeds put the picture in a plain
            // <image> element, as text or as a <url> child.
            prefix.isEmpty() && local == "image" -> {
                val t = readText(p)
                if (r.plainImage.isEmpty()) URL_IN_TEXT.find(t)?.let { r.plainImage = it.value }
            }
        }
    }

    private val URL_IN_TEXT = Regex("https?://[^\\s\"'<>]+")
    private const val MAX_TEXT = 64_000

    /**
     * The text of the current element, through its end tag. Nested elements -
     * inline XHTML in Atom content, unescaped markup in a careless feed - are
     * written back as tags rather than throwing, as nextText() would, so the
     * HTML handling downstream sees them the same way as escaped HTML.
     */
    private fun readText(p: XmlPullParser): String {
        val depth = p.depth
        val sb = StringBuilder()
        var ev = p.next()
        while (ev != XmlPullParser.END_DOCUMENT && !(ev == XmlPullParser.END_TAG && p.depth == depth)) {
            if (sb.length < MAX_TEXT) {
                when (ev) {
                    XmlPullParser.TEXT -> sb.append(p.text)
                    XmlPullParser.START_TAG -> {
                        sb.append('<').append(p.name)
                        for (i in 0 until p.attributeCount) {
                            sb.append(' ').append(p.getAttributeName(i)).append("=\"")
                                .append(p.getAttributeValue(i).replace("\"", "&quot;")).append('"')
                        }
                        sb.append('>')
                    }
                    XmlPullParser.END_TAG -> sb.append("</").append(p.name).append('>')
                }
            }
            ev = p.next()
        }
        return sb.toString()
    }

    private fun looksLikeImage(url: String): Boolean {
        val path = url.substringBefore('?').lowercase(Locale.ROOT)
        return path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") ||
            path.endsWith(".webp") || path.endsWith(".avif") || path.endsWith(".gif")
    }

    /** A cleaned, categorised [Item], or null when there is no headline or link. */
    private fun finish(r: Raw, src: Source, base: String): Item? {
        val title = htmlToText(r.title)
        val link = absolute(r.origLink.ifEmpty { r.link }.ifEmpty { r.guid }, base)
        if (title.isEmpty() || link.isEmpty()) return null

        var published = parseDate(r.date)
        if (published == 0L) published = parseDate(r.updated)

        val (pickedUrl, declared) = chooseImage(r, base)
        var image = ""
        var quality = Q_NONE
        if (pickedUrl.isNotEmpty()) {
            val (upgraded, upgradedWidth) = upgradeImage(pickedUrl)
            image = upgraded
            val w = when {
                upgradedWidth > 0 -> upgradedWidth
                declared > 0 -> declared
                else -> widthHint(upgraded)
            }
            quality = when {
                w <= 0 -> Q_OK
                w < 400 -> Q_SMALL
                w < 800 -> Q_OK
                else -> Q_LARGE
            }
        }

        return Item(
            title = title,
            link = link,
            image = image,
            imageQuality = quality,
            source = src.name,
            domain = src.domain,
            category = categorise(title, src),
            summary = makeSummary(r.description, r.content, title),
            published = published,
            tier = src.tier,
            sourceId = src.id
        )
    }

    // ---------- images ----------

    /**
     * Priority: media:content, media:thumbnail, an image enclosure,
     * itunes:image, a plain <image>, then the first real <img> in the summary
     * or the body. Within media:content and media:thumbnail, where a feed lists
     * the same picture at several sizes, the smallest one at least 1000px wide
     * is taken, or the largest if none is - a card does not need a 4000px
     * original on mobile data.
     */
    private fun chooseImage(r: Raw, base: String): Pair<String, Int> {
        fun ok(u: String) = u.isNotEmpty() && !JUNK_IMAGE.containsMatchIn(u)
        fun bySize(list: List<Pair<String, Int>>): Pair<String, Int>? {
            val good = list.filter { ok(it.first) }
            if (good.isEmpty()) return null
            return good.filter { it.second >= 1000 }.minByOrNull { it.second }
                ?: good.maxByOrNull { it.second }
        }

        val picked: Pair<String, Int>? = bySize(r.media)
            ?: bySize(r.thumbs)
            ?: r.enclosure.takeIf { ok(it) }?.let { it to 0 }
            ?: r.itunes.takeIf { ok(it) }?.let { it to 0 }
            ?: r.plainImage.takeIf { ok(it) }?.let { it to 0 }
            ?: imageInHtml(r.description)
            ?: imageInHtml(r.content)
        if (picked == null) return "" to 0
        val url = absolute(decodeEntities(picked.first.trim()), base)
        return if (url.isEmpty() || !ok(url)) ("" to 0) else (url to picked.second)
    }

    private val IMG_TAG = Regex("(?is)<img\\b[^>]*>")

    private fun attrRegex(name: String) =
        Regex("(?is)\\s$name\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")

    private val A_SRC = attrRegex("src")
    private val A_DATA_SRC = attrRegex("data-src")
    private val A_SRCSET = attrRegex("srcset")
    private val A_DATA_SRCSET = attrRegex("data-srcset")
    private val A_WIDTH = attrRegex("width")
    private val A_HEIGHT = attrRegex("height")

    private fun attrOf(tag: String, re: Regex): String? {
        val m = re.find(tag) ?: return null
        val v = m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { m.groupValues[3] }
        return decodeEntities(v).trim().ifEmpty { null }
    }

    /** The first <img> that is a picture rather than a pixel, an icon or a logo. */
    private fun imageInHtml(html: String): Pair<String, Int>? {
        if (html.isEmpty() || !html.contains("<img", ignoreCase = true)) return null
        var seen = 0
        for (m in IMG_TAG.findAll(html)) {
            if (++seen > 6) break
            val tag = m.value
            val w = attrOf(tag, A_WIDTH)?.toIntOrNull() ?: 0
            val h = attrOf(tag, A_HEIGHT)?.toIntOrNull() ?: 0
            if ((w in 1..2) || (h in 1..2)) continue
            val srcset = attrOf(tag, A_DATA_SRCSET) ?: attrOf(tag, A_SRCSET)
            fromSrcset(srcset)?.let { if (!JUNK_IMAGE.containsMatchIn(it.first)) return it }
            val src = attrOf(tag, A_DATA_SRC) ?: attrOf(tag, A_SRC) ?: continue
            if (src.startsWith("data:") || JUNK_IMAGE.containsMatchIn(src)) continue
            return src to w
        }
        return null
    }

    /** From "a.jpg 320w, b.jpg 1200w": the smallest at least 1000w, else the largest. */
    private fun fromSrcset(srcset: String?): Pair<String, Int>? {
        if (srcset.isNullOrBlank()) return null
        // Candidates are separated by a comma and whitespace; URLs may contain
        // bare commas (Condé Nast's "w_320,c_limit").
        val list = srcset.split(Regex(",\\s+")).mapNotNull { c ->
            val parts = c.trim().split(Regex("\\s+"))
            val url = parts.getOrNull(0) ?: return@mapNotNull null
            if (url.isEmpty() || url.startsWith("data:")) return@mapNotNull null
            val w = parts.getOrNull(1)?.takeIf { it.endsWith("w") }?.dropLast(1)?.toIntOrNull() ?: 0
            url to w
        }
        if (list.isEmpty()) return null
        return list.filter { it.second >= 1000 }.minByOrNull { it.second } ?: list.maxByOrNull { it.second }
    }

    /** https, absolute, or "". http is upgraded: the page may not load cleartext. */
    private fun absolute(u: String, base: String): String {
        val s = u.trim()
        if (s.isEmpty()) return ""
        val abs = when {
            s.startsWith("https://") -> s
            s.startsWith("http://") -> "https://" + s.substring(7)
            s.startsWith("//") -> "https:$s"
            else -> try {
                val resolved = URL(URL(base), s).toString()
                if (resolved.startsWith("http://")) "https://" + resolved.substring(7) else resolved
            } catch (e: Exception) { "" }
        }
        return if (abs.startsWith("https://") && abs.length > 12 && !abs.contains(' ')) abs else ""
    }

    private val FUTURE = Regex("^(https://cdn\\.mos\\.cms\\.futurecdn\\.net/.+)-(\\d{2,4})-(\\d{2})\\.(jpe?g|png|webp)$", RegexOption.IGNORE_CASE)
    private val CONDE = Regex("(/photos/[0-9a-f]{24}/[^/]+/)(pass|w_(\\d+)(,c_limit)?)/")
    private val GAMESPOT = Regex("^(https://www\\.gamespot\\.com/a/uploads/)(square|scale|screen)_(tiny|mini|petite|small|medium)/")
    private val BBC = Regex("/\\d{2,4}/cpsprodpb/")
    private val SKY = Regex("/(\\d{3,4})x(\\d{3,4})/")

    private const val TARGET_W = 1200

    /**
     * Publishers often give a feed reader a list-row thumbnail; the card is a
     * full-width image on a ~1080px screen. Where a CDN's URL carries the size
     * in a shape that is well known, ask for about 1200px instead. Only sizes
     * already present are changed - nothing is added to a URL that had none,
     * since some origins would answer with a multi-megabyte original - and
     * anything unrecognised is returned exactly as it came. A wrong guess is
     * cosmetic: home.html preloads each image and paints it only on success.
     *
     * Returns the URL and the width now asked for, or 0 if unchanged.
     */
    internal fun upgradeImage(url: String): Pair<String, Int> {
        try {
            // Future plc (TechRadar, Tom's Hardware, Android Central): name-WIDTH-QUALITY.jpg
            FUTURE.find(url)?.let { m ->
                val w = m.groupValues[2].toInt()
                if (w < 800) return (m.groupValues[1] + "-" + TARGET_W + "-" + m.groupValues[3] + "." + m.groupValues[4]) to TARGET_W
                return url to 0
            }
            // Condé Nast (WIRED, Pitchfork, Traveler): /photos/ID/RATIO/w_320,c_limit/ or /pass/ (the original).
            if (url.contains("/photos/")) {
                CONDE.find(url)?.let { m ->
                    val w = m.groupValues[3].toIntOrNull()
                    if (m.groupValues[2] == "pass" || (w != null && w < 800)) {
                        return url.replaceRange(m.range, m.groupValues[1] + "w_1280,c_limit/") to 1280
                    }
                    return url to 0
                }
            }
            GAMESPOT.find(url)?.let { m ->
                return url.replaceRange(m.range, m.groupValues[1] + "screen_kubrick/") to 1280
            }
            if (url.contains("ichef.bbci.co.uk")) {
                return url.replace(BBC, "/800/cpsprodpb/") to 800
            }
            if (url.contains("365dm.com")) {
                return url.replace(SKY, "/768x432/") to 768
            }
            val host = url.substringAfter("://").substringBefore('/').lowercase(Locale.ROOT)
            val query = url.substringAfter('?', "")
            if (query.isEmpty()) return url to 0
            return when {
                // WordPress: Jetpack Photon and the VIP file service both size by
                // w/h/resize/fit query parameters.
                url.contains("/wp-content/uploads/") || host.endsWith(".wp.com") || host.endsWith(".files.wordpress.com") ->
                    resizeQuery(url, "w", "h", listOf("resize", "fit"))
                host.endsWith("ignimgs.com") || host.endsWith("gnwcdn.com") ->
                    resizeQuery(url, "width", "height", emptyList())
                host.endsWith("espncdn.com") && url.contains("/combiner/") ->
                    resizeQuery(url, "w", "h", emptyList())
                else -> url to 0
            }
        } catch (e: Exception) {
            return url to 0
        }
    }

    /**
     * Rewrites a width parameter under 800 to [TARGET_W], scaling the height
     * parameter with it so a crop keeps its shape; "W,H" pair parameters
     * likewise. Parameters it does not know are left in place and in order.
     */
    private fun resizeQuery(url: String, wKey: String, hKey: String, pairKeys: List<String>): Pair<String, Int> {
        val head = url.substringBefore('?')
        val fragment = url.substringAfter('#', "")
        val query = url.substringAfter('?').substringBefore('#')
        val params = query.split('&').filter { it.isNotEmpty() }.map {
            val k = it.substringBefore('=')
            k to it.substringAfter('=', "")
        }.toMutableList()

        val wIdx = params.indexOfFirst { it.first == wKey }
        val oldW = if (wIdx >= 0) params[wIdx].second.toIntOrNull() else null
        var changed = false
        if (oldW != null && oldW in 1..799) {
            val hIdx = params.indexOfFirst { it.first == hKey }
            val oldH = if (hIdx >= 0) params[hIdx].second.toIntOrNull() else null
            params[wIdx] = wKey to TARGET_W.toString()
            if (oldH != null && oldH > 0) params[hIdx] = hKey to (oldH * TARGET_W.toDouble() / oldW).roundToInt().toString()
            changed = true
        } else if (oldW == null) {
            for (key in pairKeys) {
                val idx = params.indexOfFirst { it.first == key }
                if (idx < 0) continue
                val sep = if (params[idx].second.contains("%2C", true)) "%2C" else ","
                val parts = params[idx].second.split(Regex(",|%2C|%2c"))
                val pw = parts.getOrNull(0)?.toIntOrNull() ?: continue
                val ph = parts.getOrNull(1)?.toIntOrNull()
                if (pw !in 1..799) continue
                val nh = if (ph != null && ph > 0) sep + (ph * TARGET_W.toDouble() / pw).roundToInt() else ""
                params[idx] = key to (TARGET_W.toString() + nh)
                changed = true
                break
            }
        }
        if (!changed) return url to 0
        val rebuilt = head + "?" + params.joinToString("&") { if (it.second.isEmpty() && !query.contains(it.first + "=")) it.first else it.first + "=" + it.second } +
            (if (fragment.isNotEmpty()) "#$fragment" else "")
        return rebuilt to TARGET_W
    }

    private val HINT_QUERY = Regex("[?&](?:w|width)=(\\d{2,4})")
    private val HINT_PAIR = Regex("[?&](?:resize|fit)=(\\d{2,4})")
    private val HINT_WP = Regex("-(\\d{2,4})x\\d{2,4}\\.(jpe?g|png|webp)", RegexOption.IGNORE_CASE)
    private val HINT_CONDE = Regex("/w_(\\d{2,4})")

    /** The width a URL asks for, where its shape says; 0 when it does not. */
    private fun widthHint(url: String): Int {
        FUTURE.find(url)?.let { return it.groupValues[2].toInt() }
        HINT_QUERY.find(url)?.let { return it.groupValues[1].toInt() }
        HINT_PAIR.find(url)?.let { return it.groupValues[1].toInt() }
        HINT_CONDE.find(url)?.let { return it.groupValues[1].toInt() }
        HINT_WP.find(url.substringBefore('?'))?.let { return it.groupValues[1].toInt() }
        return 0
    }

    // ---------- text ----------

    private val NAMED: Map<String, String> = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "ensp" to " ", "emsp" to " ", "thinsp" to " ", "hairsp" to " ",
        "shy" to "", "zwnj" to "", "zwj" to "", "lrm" to "", "rlm" to "",
        "rsquo" to "’", "lsquo" to "‘", "sbquo" to "‚", "ldquo" to "“", "rdquo" to "”", "bdquo" to "„",
        "mdash" to "—", "ndash" to "–", "hellip" to "…", "minus" to "−", "prime" to "′", "Prime" to "″",
        "laquo" to "«", "raquo" to "»", "lsaquo" to "‹", "rsaquo" to "›", "bull" to "•", "middot" to "·",
        "pound" to "£", "euro" to "€", "yen" to "¥", "cent" to "¢", "copy" to "©", "reg" to "®",
        "trade" to "™", "deg" to "°", "times" to "×", "divide" to "÷", "plusmn" to "±", "sect" to "§",
        "para" to "¶", "dagger" to "†", "frac12" to "½", "frac14" to "¼", "frac34" to "¾",
        "sup2" to "²", "sup3" to "³", "micro" to "µ", "iexcl" to "¡", "iquest" to "¿",
        "larr" to "←", "rarr" to "→", "uarr" to "↑", "darr" to "↓", "hearts" to "♥", "star" to "☆",
        "aacute" to "á", "Aacute" to "Á", "agrave" to "à", "Agrave" to "À", "acirc" to "â", "auml" to "ä",
        "Auml" to "Ä", "atilde" to "ã", "aring" to "å", "aelig" to "æ", "ccedil" to "ç", "Ccedil" to "Ç",
        "eacute" to "é", "Eacute" to "É", "egrave" to "è", "Egrave" to "È", "ecirc" to "ê", "euml" to "ë",
        "iacute" to "í", "Iacute" to "Í", "igrave" to "ì", "icirc" to "î", "iuml" to "ï",
        "ntilde" to "ñ", "Ntilde" to "Ñ", "oacute" to "ó", "Oacute" to "Ó", "ograve" to "ò", "ocirc" to "ô",
        "ouml" to "ö", "Ouml" to "Ö", "otilde" to "õ", "oslash" to "ø", "Oslash" to "Ø",
        "uacute" to "ú", "Uacute" to "Ú", "ugrave" to "ù", "ucirc" to "û", "uuml" to "ü", "Uuml" to "Ü",
        "szlig" to "ß", "yacute" to "ý", "yuml" to "ÿ"
    )

    private val ENTITY = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z][A-Za-z0-9]{1,31});")

    /** Windows-1252 code points that HTML numeric references use for punctuation. */
    private val CP1252 = mapOf(
        128 to "€", 130 to "‚", 132 to "„", 133 to "…", 145 to "‘", 146 to "’",
        147 to "“", 148 to "”", 149 to "•", 150 to "–", 151 to "—", 153 to "™"
    )

    /**
     * Numeric and named references. Two passes, because WordPress escapes
     * twice - "&amp;#8217;" in a title is an apostrophe. Anything unknown is
     * left as written.
     */
    internal fun decodeEntities(s: String): String {
        var cur = s
        repeat(2) {
            if (cur.indexOf('&') < 0) return cur
            val next = ENTITY.replace(cur) { m -> decodeOne(m.groupValues[1]) ?: m.value }
            if (next == cur) return cur
            cur = next
        }
        return cur
    }

    private fun decodeOne(code: String): String? {
        if (code.startsWith("#")) {
            val cp = try {
                if (code.length > 1 && (code[1] == 'x' || code[1] == 'X')) code.substring(2).toInt(16)
                else code.substring(1).toInt()
            } catch (e: Exception) { return null }
            CP1252[cp]?.let { return it }
            if (cp == 0 || cp in 0xD800..0xDFFF || cp > 0x10FFFF) return null
            if (cp == 0xA0) return " "
            return String(Character.toChars(cp))
        }
        return NAMED[code] ?: NAMED[code.lowercase(Locale.ROOT)]
    }

    private val DROP_BLOCKS = Regex("(?is)<(script|style|figure|figcaption|noscript|iframe|svg|button|aside|table|video|audio)\\b.*?</\\1\\s*>")
    private val BLOCK_TAG = Regex("(?i)</?(p|br|div|li|ul|ol|h[1-6]|blockquote|tr|td|th|section|article|header|footer|hr|dd|dt)\\b[^>]*>")
    private val ANY_TAG = Regex("(?s)<[/!?a-zA-Z][^>]{0,2000}>")
    private val SPACES = Regex("[\\s\\u00A0\\u2000-\\u200B\\u202F\\u205F\\u3000\\uFEFF]+")
    private val SPACE_BEFORE_PUNCT = Regex(" ([,.;:!?)])")

    /** HTML or plain text in, one line of plain text out. */
    internal fun htmlToText(html: String): String {
        if (html.isEmpty()) return ""
        var s = DROP_BLOCKS.replace(html, " ")
        s = BLOCK_TAG.replace(s, " ")
        s = ANY_TAG.replace(s, "")
        s = decodeEntities(s)
        // Markup that was escaped twice is only markup now.
        if (s.indexOf('<') >= 0) {
            s = DROP_BLOCKS.replace(s, " ")
            s = BLOCK_TAG.replace(s, " ")
            s = ANY_TAG.replace(s, "")
        }
        s = SPACES.replace(s, " ").trim()
        return SPACE_BEFORE_PUNCT.replace(s, "$1")
    }

    private val BOILERPLATE = listOf(
        Regex("(?i)\\s*The post .{1,300}? appeared first on .{1,120}$"),
        Regex("(?i)\\s*This (article|story|post) (originally )?appeared (first )?(on|in) .{1,120}$"),
        Regex("(?i)\\s*(Continue reading|Read more|Read the full (story|article|post)|Keep reading|Click here)\\b.{0,120}$"),
        Regex("\\s*\\[(…|\\.\\.\\.|&hellip;)\\]\\s*$"),
        Regex("(?i)\\s*\\(more…\\)\\s*$"),
        Regex("(?i)^(Image|Photo|Photograph|Illustration)( credit)?:\\s*[^.]{1,80}?(Getty Images|Shutterstock|AP|Reuters|Alamy)\\s*")
    )

    /**
     * The item's summary, else the opening of its body. Feed furniture is
     * removed - WordPress's "The post ... appeared first on", "Read more",
     * "[…]" - and so is a summary that only repeats the headline.
     */
    private fun makeSummary(description: String, content: String, title: String): String {
        var s = scrub(htmlToText(description.take(12_000)))
        if (s.length < 80) {
            val c = scrub(htmlToText(content.take(16_000)))
            if (c.length > s.length) s = c
        }
        if (s.startsWith(title, ignoreCase = true)) {
            s = s.substring(title.length).trimStart(' ', ':', '-', '–', '—', '.', '|')
        }
        if (s.length < 40) return ""
        return cut(s, SUMMARY_MAX)
    }

    private fun scrub(text: String): String {
        var s = text
        for (re in BOILERPLATE) s = re.replace(s, "")
        return s.trim()
    }

    /**
     * At most [max] chars. Ends at a sentence when one ends past halfway,
     * otherwise at the last whole word with an ellipsis. Never mid-word.
     */
    internal fun cut(s: String, max: Int): String {
        if (s.length <= max) return s
        val window = s.substring(0, max)
        val sentence = Regex("[.!?][\"”’)]?(?=\\s)").findAll(window).lastOrNull()
        if (sentence != null && sentence.range.last + 1 >= max / 2) {
            return window.substring(0, sentence.range.last + 1)
        }
        var end = window.lastIndexOf(' ', max - 2)
        if (end < max / 3) end = max - 1
        val head = s.substring(0, end).trimEnd().trimEnd(',', ';', ':', '-', '–', '—', '(', '“', '"', '‘', '\'', '/')
        return head + "…"
    }

    // ---------- dates ----------

    private val RFC822 = Regex(
        "^(?:[A-Za-z]{2,9},?\\s+)?(\\d{1,2})[\\s-]+([A-Za-z]{3,9})\\.?[\\s-]+(\\d{2,4})[\\sT,]+" +
        "(\\d{1,2}):(\\d{2})(?::(\\d{2}))?(?:\\.\\d+)?\\s*(.*)$"
    )
    private val ISO8601 = Regex(
        "^(\\d{4})-(\\d{1,2})-(\\d{1,2})(?:[T\\s](\\d{1,2}):(\\d{2})(?::(\\d{2})(?:[.,]\\d+)?)?)?\\s*(.*)$"
    )
    private val MONTH_FIRST = Regex(
        "^(?:[A-Za-z]{2,9},?\\s+)?([A-Za-z]{3,9})\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?,?\\s+(\\d{4})" +
        "(?:\\s*(?:-|at|,)?\\s*(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\s*([AaPp]\\.?[Mm]\\.?)?)?\\s*(.*)$"
    )
    private val OFFSET = Regex("^(?:GMT|UTC|UT)?\\s*([+-])(\\d{1,2})(?::?(\\d{2}))?$", RegexOption.IGNORE_CASE)

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    private val ZONES: Map<String, Int> = mapOf(
        "GMT" to 0, "UT" to 0, "UTC" to 0, "Z" to 0, "WET" to 0,
        "EST" to -300, "EDT" to -240, "CST" to -360, "CDT" to -300,
        "MST" to -420, "MDT" to -360, "PST" to -480, "PDT" to -420,
        "AKST" to -540, "AKDT" to -480, "HST" to -600,
        "BST" to 60, "IST" to 330, "CET" to 60, "CEST" to 120, "MET" to 60, "MEST" to 120,
        "EET" to 120, "EEST" to 180, "WEST" to 60, "MSK" to 180,
        "JST" to 540, "KST" to 540, "SGT" to 480, "HKT" to 480, "AWST" to 480,
        "ACST" to 570, "ACDT" to 630, "AEST" to 600, "AEDT" to 660, "NZST" to 720, "NZDT" to 780
    )

    /**
     * Epoch millis, or 0. RFC 822 in its real-world variety (with or without
     * weekday or seconds, one-digit days, two-digit years, named or numeric
     * zones, "+00:00"), ISO 8601 (with or without fraction, offset or time),
     * "October 2, 2026 10:00 am" as some CMSs write it, and bare epoch
     * numbers. No zone means UTC. Done by hand rather than with
     * SimpleDateFormat, whose parsing of zone names and lenient fields differs
     * between Android versions.
     */
    internal fun parseDate(raw: String): Long {
        val s = raw.trim()
        if (s.isEmpty()) return 0L
        try {
            if (s.all { it.isDigit() }) {
                return when (s.length) {
                    10 -> s.toLong() * 1000L
                    13 -> s.toLong()
                    else -> 0L
                }
            }
            ISO8601.find(s)?.let { m ->
                val g = m.groupValues
                return utc(g[1].toInt(), g[2].toInt(), g[3].toInt(),
                    g[4].toIntOrNull() ?: 0, g[5].toIntOrNull() ?: 0, g[6].toIntOrNull() ?: 0, zone(g[7]))
            }
            RFC822.find(s)?.let { m ->
                val g = m.groupValues
                val month = monthIndex(g[2]) ?: return 0L
                return utc(year(g[3].toInt()), month + 1, g[1].toInt(),
                    g[4].toInt(), g[5].toInt(), g[6].toIntOrNull() ?: 0, zone(g[7]))
            }
            MONTH_FIRST.find(s)?.let { m ->
                val g = m.groupValues
                val month = monthIndex(g[1]) ?: return 0L
                var hour = g[4].toIntOrNull() ?: 0
                val ampm = g[7].lowercase(Locale.ROOT)
                if (ampm.startsWith("p") && hour < 12) hour += 12
                if (ampm.startsWith("a") && hour == 12) hour = 0
                return utc(g[3].toInt(), month + 1, g[2].toInt(),
                    hour, g[5].toIntOrNull() ?: 0, g[6].toIntOrNull() ?: 0, zone(g[8]))
            }
        } catch (e: Exception) {
            // Unreadable.
        }
        return 0L
    }

    private fun monthIndex(name: String): Int? {
        val i = MONTHS.indexOf(name.take(3).lowercase(Locale.ROOT))
        return if (i >= 0) i else null
    }

    private fun year(y: Int) = when {
        y >= 100 -> y
        y < 70 -> 2000 + y
        else -> 1900 + y
    }

    /** Minutes east of UTC, from "Z", "+0530", "-04:00", "GMT+1", "EDT", "(EDT)"; 0 when unknown. */
    private fun zone(raw: String): Int {
        val z = raw.trim().removePrefix("(").substringBefore(' ').removeSuffix(")").trim()
        if (z.isEmpty()) return 0
        OFFSET.find(z)?.let { m ->
            val sign = if (m.groupValues[1] == "-") -1 else 1
            val h = m.groupValues[2].toInt()
            val min = m.groupValues[3].toIntOrNull() ?: 0
            // "+0530" arrives as h=05, min=30; "+530" is not worth guessing at.
            if (h > 14 || min > 59) return 0
            return sign * (h * 60 + min)
        }
        return ZONES[z.uppercase(Locale.ROOT)] ?: 0
    }

    private fun utc(y: Int, mon: Int, d: Int, h: Int, min: Int, sec: Int, offsetMin: Int): Long {
        if (y !in 1990..2100 || mon !in 1..12 || d !in 1..31 || h !in 0..24 || min !in 0..59 || sec !in 0..60) return 0L
        val c = GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.US)
        c.clear()
        c.set(y, mon - 1, d, h, min, sec)
        return c.timeInMillis - offsetMin * 60_000L
    }

    // ---------- YouTube trending (optional) ----------

    private const val YT_TTL_MS = 3L * 60 * 60 * 1000
    private const val YT_BACKOFF_MS = 6L * 60 * 60 * 1000
    private const val YT_STALE_MS = 24L * 60 * 60 * 1000
    private const val YT_MAX = 3

    /**
     * The URL from the API's documentation, with a fields filter that drops
     * descriptions and tags: about 6KB instead of 60KB for ten videos.
     */
    private val YT_ENDPOINT =
        "https://www.googleapis.com/youtube/v3/videos?part=snippet,statistics&chart=mostPopular" +
        "&maxResults=10&regionCode=US&fields=" + URLEncoder.encode(
            "items(id,snippet(title,channelTitle,publishedAt,categoryId,thumbnails),statistics(viewCount))",
            "UTF-8"
        )

    /**
     * Up to [YT_MAX] of YouTube's US trending videos, when
     * BuildConfig.YOUTUBE_API_KEY is set; with no key, nothing is requested.
     *
     * The quota is per project, not per install - 10,000 units a day, one unit
     * per call - so the answer is kept for [YT_TTL_MS], three hours, rather
     * than the feed's half hour; trending moves slowly enough for that. A
     * refused key (403, 429, 400) stops all requests for [YT_BACKOFF_MS].
     *
     * The key ships inside the APK, so it is restricted in Google Cloud to
     * this package and signing certificate. Those restrictions are checked
     * against the X-Android-Package and X-Android-Cert headers sent here.
     */
    private fun fetchYouTube(context: Context, mem: Memory, now: Long): List<Item> {
        val key = BuildConfig.YOUTUBE_API_KEY.trim()
        if (key.isEmpty()) {
            DebugLog.add { "feed  YouTube  no API key in this build, not requested" }
            return emptyList()
        }

        val cachedAt = mem.get("yt_at")?.toLongOrNull() ?: 0L
        val cachedBody = mem.get("yt_body")
        if (cachedBody != null && now - cachedAt < YT_TTL_MS) {
            val items = mapYouTube(cachedBody)
            DebugLog.add { "feed  YouTube  cached  parsed " + items.size }
            return items
        }
        // When YouTube cannot be asked, the last answer stands in for up to a
        // day; after that a "trending" row would be yesterday's news.
        fun lastGood(): List<Item> =
            if (cachedBody != null && now - cachedAt < YT_STALE_MS) mapYouTube(cachedBody) else emptyList()

        val backoff = mem.get("yt_backoff")?.toLongOrNull() ?: 0L
        if (now < backoff) {
            DebugLog.add { "feed  YouTube  backing off after a refusal" }
            return lastGood()
        }

        var conn: HttpURLConnection? = null
        val url = YT_ENDPOINT + "&key=" + URLEncoder.encode(key, "UTF-8")
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Encoding", "gzip")
                setRequestProperty("X-Android-Package", context.packageName)
                val cert = signingSha1(context)
                if (cert.isNotEmpty()) setRequestProperty("X-Android-Cert", cert)
            }
            val code = conn.responseCode
            if (code != 200) {
                if (code == 400 || code == 403 || code == 429) mem.put("yt_backoff", (now + YT_BACKOFF_MS).toString())
                DebugLog.add { "feed  YouTube  " + DebugLog.url(url) + "  " + code + "  parsed 0  kept 0" }
                return lastGood()
            }
            var raw: InputStream = conn.inputStream
            if ("gzip".equals(conn.contentEncoding, ignoreCase = true)) raw = GZIPInputStream(raw)
            val body = Capped(raw, 256_000, System.currentTimeMillis() + SOURCE_BUDGET_MS).use { String(it.readBytes(), Charsets.UTF_8) }
            val items = mapYouTube(body)
            if (items.isNotEmpty()) {
                mem.put("yt_body", body)
                mem.put("yt_at", now.toString())
            }
            DebugLog.add { "feed  YouTube  " + DebugLog.url(url) + "  200  parsed " + items.size + "  kept " + items.size }
            return items
        } catch (e: Exception) {
            DebugLog.add { "feed  YouTube  " + DebugLog.url(url) + "  error " + e.javaClass.simpleName + "  parsed 0  kept 0" }
            return lastGood()
        } finally {
            try { conn?.disconnect() } catch (e: Exception) { }
        }
    }

    /** videos.list JSON to feed items, in YouTube's own trending order. */
    internal fun mapYouTube(body: String): List<Item> {
        val out = ArrayList<Item>()
        try {
            val arr = JSONObject(body).optJSONArray("items") ?: return out
            for (i in 0 until arr.length()) {
                if (out.size >= YT_MAX) break
                val v = arr.optJSONObject(i) ?: continue
                val id = v.optString("id")
                val sn = v.optJSONObject("snippet") ?: continue
                val title = htmlToText(sn.optString("title"))
                if (id.isEmpty() || title.length < 8) continue

                val thumbs = sn.optJSONObject("thumbnails")
                var image = ""
                var width = 0
                if (thumbs != null) {
                    // maxres is 1280x720. standard and high are 4:3 frames
                    // around a 16:9 picture; cropped to a 16:9 card the bars fall
                    // away, so they are the next best.
                    for (k in listOf("maxres", "standard", "high", "medium", "default")) {
                        val t = thumbs.optJSONObject(k) ?: continue
                        val u = t.optString("url")
                        if (u.startsWith("http")) {
                            image = absolute(u, "https://i.ytimg.com/")
                            width = t.optInt("width", 0)
                            break
                        }
                    }
                }
                if (image.isEmpty()) continue

                val views = v.optJSONObject("statistics")?.optString("viewCount")?.toLongOrNull() ?: 0L
                val channel = htmlToText(sn.optString("channelTitle"))
                val summary = listOf(channel, if (views > 0) compactCount(views) + " views" else "")
                    .filter { it.isNotEmpty() }.joinToString(" · ")
                val category = when (sn.optString("categoryId")) {
                    "10" -> MUSIC
                    "20" -> GAMING
                    "1", "24" -> ENTERTAINMENT
                    "28" -> TECH
                    else -> CREATORS
                }
                out.add(
                    Item(
                        title = title,
                        link = "https://www.youtube.com/watch?v=" + URLEncoder.encode(id, "UTF-8"),
                        image = image,
                        imageQuality = when {
                            width >= 800 -> Q_LARGE
                            width >= 400 || width == 0 -> Q_OK
                            else -> Q_SMALL
                        },
                        source = "YouTube",
                        domain = "youtube.com",
                        category = category,
                        summary = summary,
                        published = parseDate(sn.optString("publishedAt")),
                        tier = 2,
                        sourceId = YOUTUBE_ID,
                        boost = when {
                            views >= 10_000_000L -> 2.0
                            views >= 1_000_000L -> 1.2
                            views >= 100_000L -> 0.5
                            else -> 0.0
                        }
                    )
                )
            }
        } catch (e: Exception) {
            // Malformed answer: no videos this time.
        }
        return out
    }

    /** 950 / 12K / 1.2M / 3.4B, one decimal below ten. */
    internal fun compactCount(n: Long): String {
        fun fmt(v: Double, unit: String): String =
            if (v < 10) String.format(Locale.US, "%.1f", v).removeSuffix(".0") + unit
            else v.toLong().toString() + unit
        return when {
            n >= 1_000_000_000L -> fmt(n / 1_000_000_000.0, "B")
            n >= 1_000_000L -> fmt(n / 1_000_000.0, "M")
            n >= 1_000L -> fmt(n / 1_000.0, "K")
            else -> n.toString()
        }
    }

    @Volatile private var certSha1: String? = null

    /**
     * SHA-1 of the certificate this install is signed with, upper-case hex
     * without colons, as Google's Android key restriction expects. With Play
     * App Signing, a Play install is signed by Google's key, not the upload
     * key, so the restriction needs both fingerprints listed.
     */
    @Suppress("DEPRECATION")
    private fun signingSha1(context: Context): String {
        certSha1?.let { return it }
        val value = try {
            val pm = context.packageManager
            val sig: Signature? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val si = info.signingInfo
                when {
                    si == null -> null
                    si.hasMultipleSigners() -> si.apkContentsSigners?.firstOrNull()
                    // In rotation order: the original first, the current last.
                    else -> si.signingCertificateHistory?.lastOrNull()
                }
            } else {
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                info.signatures?.firstOrNull()
            }
            if (sig == null) "" else sha1Hex(sig.toByteArray())
        } catch (e: Exception) {
            ""
        }
        certSha1 = value
        return value
    }

    internal fun sha1Hex(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-1").digest(bytes)
        val hex = "0123456789ABCDEF"
        val sb = StringBuilder(d.size * 2)
        for (b in d) {
            val v = b.toInt() and 0xff
            sb.append(hex[v ushr 4]).append(hex[v and 0x0f])
        }
        return sb.toString()
    }

    // ---------- output ----------

    internal fun toJson(items: List<Item>): String {
        val out = JSONArray()
        for ((i, it) in items.withIndex()) {
            val o = JSONObject()
            o.put("title", it.title)
            o.put("link", it.link)
            o.put("image", it.image)
            o.put("source", it.source)
            o.put("domain", it.domain)
            o.put("category", it.category)
            o.put("summary", it.summary)
            o.put("published", it.published)
            o.put("lead", i == 0)
            out.put(o)
        }
        return out.toString()
    }

    // ---------- disk ----------

    private fun writeDisk(context: Context, json: String) {
        try {
            File(context.filesDir, CACHE_FILE).writeText(json)
        } catch (e: Exception) { /* best effort */ }
    }

    /**
     * Takes the cached feed and its age together; the file's mtime is the
     * timestamp, so a cold start honours the TTL instead of refetching.
     *
     * A file in the version 1 shape (no "lead" field) is kept as the offline
     * copy but treated as expired, so an upgraded phone that is online gets
     * the new feed on its first home page rather than after the TTL.
     */
    private fun loadDisk(context: Context) {
        try {
            val f = File(context.filesDir, CACHE_FILE)
            if (!f.exists()) return
            val text = f.readText()
            if (text.isNotBlank()) {
                cache = text
                cachedAt = if (text.contains("\"lead\"")) f.lastModified() else 0L
            }
        } catch (e: Exception) { /* best effort */ }
    }

    /** Tests only: forget the in-memory copy, as a cold start would. */
    internal fun resetMemoryCache() {
        cache = null
        cachedAt = 0L
        retryAt = 0L
    }
}
