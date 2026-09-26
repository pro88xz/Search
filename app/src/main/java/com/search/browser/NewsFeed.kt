package com.search.browser

import android.content.Context
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Headlines for the home feed: one editor-curated front page per region.
 *
 * Three things were learned the hard way and are encoded here.
 *
 * First, category dumps are padded. The BBC's category feeds carry evergreen
 * explainers months old - its Top Stories feed held an item 514 days old - and
 * CNN's international feed answers HTTP 200 with stories two and a half to
 * three and a half YEARS stale. Age is therefore an eligibility question, not a
 * ranking one, and the gate below throws out more noise than any scoring could.
 *
 * Second, a front page already covers every category, because choosing across
 * categories is what a front page is. So subscribing to one front page per
 * region buys category coverage, global coverage and an editor's judgement all
 * at once, and more cheaply than a stack of category feeds from one country.
 *
 * Third, corroboration beats taste. If several newsrooms on several continents
 * lead with the same story, it is big, and no heuristic written here could
 * judge it better. Clustering near-duplicate headlines is therefore not a
 * cleanup step but the ranking signal - and single-source fluff sinks by
 * itself, with no rules needed about what fluff looks like.
 *
 * Contract, unchanged across every version of this file: [fetch] returns a JSON
 * array of {title, link, image, source}, rendered by renderFeed in home.html.
 */
object NewsFeed {

    /**
     * @param region where this newsroom reports from. Load-bearing: it is what
     *   separates a story that is big in one country from one that is big
     *   across several, and what the output is woven by so that twelve cards
     *   span the world instead of one country's desks.
     */
    private class Source(
        val publisher: String,
        val region: String,
        val url: String
    )

    /**
     * Sources. This list is the whole configuration surface.
     *
     * Every entry is a front page rather than a category feed, and every size
     * and age span below was measured rather than estimated.
     *
     * Absent on purpose:
     *   United States   Every front page here leads with American news, so a US
     *                   source adds the least of any region - and the best one
     *                   with images (NBC) wanted 50KB, with padding out to 333
     *                   days. CBS carries no images at all; CNN's feed is
     *                   abandoned; ABC News ran 14 junk items in 25.
     *   Middle East     A genuine gap, not a choice: Al Arabiya, Arab News and
     *                   Times of Israel answer 403, while Al Jazeera and Middle
     *                   East Eye publish no per-item image (MEE also 122KB).
     *                   Middle East news still arrives - the Hormuz story led
     *                   eight of the feeds probed at once - but in other
     *                   newsrooms' voices.
     *   Euronews, CBS, Rio Times, AllAfrica, DW    no per-item image.
     *   SCMP 81KB, ABC Australia 84KB, Guardian 148KB, NBC 50KB   size.
     *   Engadget 36KB   tech, one region; front pages carry technology too.
     */
    private val SOURCES = listOf(
        Source("BBC",              "UK",            "https://feeds.bbci.co.uk/news/rss.xml"),                                 // 24KB
        Source("Sky News",         "UK",            "https://feeds.skynews.com/feeds/rss/home.xml"),                          // 13KB, junk 0
        Source("France 24",        "Europe",        "https://www.france24.com/en/rss"),                                       // 22KB, 1h..7h
        Source("NDTV",             "India",         "https://feeds.feedburner.com/ndtvnews-top-stories"),                     // 27KB, freshest probed
        Source("Channel NewsAsia", "Asia",          "https://www.channelnewsasia.com/api/v1/rss-outbound-feed?_format=xml"),  // 18KB, 0h..6h
        Source("Africanews",       "Africa",        "https://www.africanews.com/feed/rss"),                                   // 46KB, junk 0
        Source("Sydney Morning Herald", "Oceania",  "https://www.smh.com.au/rss/feed.xml"),                                   // 16KB, 1h..6h
        Source("Global News",      "Canada",        "https://globalnews.ca/feed/"),                                           // 19KB, 0h..5h
        Source("MercoPress",       "Latin America", "https://en.mercopress.com/rss/")                                         // 15KB, junk 0
    )

    private const val TTL_MS = 15 * 60 * 1000L
    private const val CACHE_FILE = "feed_cache.json"

    /** home.html renders twelve; the rest is headroom. */
    private const val MAX_ITEMS = 24

    /**
     * Anything older than this is not a top story. Set against measurement: the
     * fresh feeds run 0-30h, so 48h keeps a full news cycle plus the slower
     * desks, while excluding padding that starts near 100h and, in the worst
     * feed probed, reached 30,000.
     */
    private const val MAX_AGE_MS = 48L * 60L * 60L * 1000L

    /** Below this a headline is a ticker fragment, not a story. */
    private const val MIN_TITLE_CHARS = 25

    /**
     * Identifies the app honestly. Some publisher CDNs answer 403 to the
     * default "Java/1.8.0" agent, which would look exactly like an outage.
     */
    private const val UA =
        "Mozilla/5.0 (Linux; Android) Search/1.2 (+https://mebs.app)"

    @Volatile private var cache: String? = null
    @Volatile private var cachedAt: Long = 0L

    private class Item(
        val title: String,
        val link: String,
        val image: String,
        val publisher: String,
        val region: String,
        val at: Long
    ) {
        /** Content words, for the overlap test. Computed once. */
        val tokens: Set<String> = tokenise(title)
    }

    /** A cluster of items reporting one event, with its standing. */
    private class Story(
        val item: Item,
        val region: String,
        val publishers: Int,
        val regions: Int,
        val at: Long
    )

    // ---------- entry point ----------

    fun fetch(context: Context): String {
        val now = System.currentTimeMillis()

        if (cache == null) loadDisk(context)

        val warm = cache
        if (warm != null && now - cachedAt < TTL_MS) return warm

        val fresh = build(now)
        if (fresh.isNotEmpty()) {
            val json = toJson(fresh)
            cache = json
            cachedAt = now
            writeDisk(context, json)
            return json
        }
        return cache ?: "[]"
    }

    // ---------- gather, gate, rank ----------

    private fun build(now: Long): List<Item> {
        val raw = fetchAll()

        var kept = raw.filter { eligible(it, now, checkAge = true) }
        var degraded = false
        if (kept.isEmpty() && raw.isNotEmpty()) {
            // Every item failed. On a working day that is not a quiet news
            // cycle, it is a date format nobody here can read - a publisher
            // switching to ISO-8601, say. Falling back to the content gates
            // alone shows an unsorted feed rather than none, which is the
            // better of the two failures.
            kept = raw.filter { eligible(it, now, checkAge = false) }
            degraded = true
        }

        val stories = rank(cluster(kept), now)
        val out = weave(stories)

        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "NewsFeed",
                "raw=" + raw.size + " eligible=" + kept.size +
                " stories=" + stories.size + " shown=" + out.size +
                " regions=" + out.map { it.region }.distinct().size +
                (if (degraded) " DEGRADED(no readable dates)" else "")
            )
        }
        return out
    }

    private fun fetchAll(): List<Item> {
        val pool = Executors.newFixedThreadPool(SOURCES.size)
        val gathered = ArrayList<Item>()
        try {
            val tasks = SOURCES.map { s -> Callable { readSource(s) } }
            // Bounded: one publisher having a slow morning must not hold the
            // whole feed. Whatever arrived in time is what ships.
            for (f in pool.invokeAll(tasks, 15, TimeUnit.SECONDS)) {
                try {
                    gathered.addAll(f.get())
                } catch (e: Exception) {
                    // Cancelled or failed. That region is simply absent today.
                }
            }
        } catch (e: Exception) {
            // Interrupted: continue with whatever was collected.
        } finally {
            pool.shutdownNow()
        }
        return gathered
    }

    private fun readSource(s: Source): List<Item> {
        val body = httpGet(s.url) ?: return emptyList()
        return parseFeed(body, s)
    }

    private fun httpGet(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 7000
                readTimeout = 7000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty(
                    "Accept",
                    "application/rss+xml, application/atom+xml, application/xml, text/xml, */*"
                )
            }
            if (conn.responseCode != 200) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (e: Exception) { }
        }
    }

    // ---------- the gates ----------

    private val JUNK_LINK = Regex(
        "/live/|/av/|/in-pictures|/videos?/|/programmes/|/iplayer/|/live-updates",
        RegexOption.IGNORE_CASE
    )

    private val JUNK_TITLE = Regex(
        "^(watch|video|listen|in pictures|your pictures|live)\\b" +
        "|round-?up|\\bquiz\\b|newsletter|podcast|live updates|live blog" +
        "|most striking pictures|in photos|news bulletin",
        RegexOption.IGNORE_CASE
    )

    /**
     * Supply is roughly 200 items for twelve visible slots, so these can afford
     * to be strict. Every gate was set against measured feed contents.
     *
     * The image requirement is what eliminated CBS, Euronews, AllAfrica, DW,
     * Al Jazeera and Middle East Eye as sources - it is not a formality. A
     * text-only card in an image-led layout reads as broken.
     */
    private fun eligible(it: Item, now: Long, checkAge: Boolean): Boolean {
        if (it.title.length < MIN_TITLE_CHARS) return false
        if (it.image.isEmpty()) return false
        if (JUNK_LINK.containsMatchIn(it.link)) return false
        if (JUNK_TITLE.containsMatchIn(it.title)) return false
        if (checkAge) {
            if (it.at == 0L) return false
            if (now - it.at > MAX_AGE_MS) return false
            // NDTV timestamps some items up to four hours ahead. A future date
            // is a clock or timezone problem, not a scoop, but a few hours of
            // it is normal enough to allow.
            if (it.at - now > 6L * 60L * 60L * 1000L) return false
        }
        return true
    }

    // ---------- clustering: the trend signal ----------

    private val STOPWORDS = setOf(
        "the", "and", "for", "with", "from", "that", "this", "these", "those",
        "his", "her", "its", "their", "they", "she", "was", "were", "been",
        "are", "has", "have", "had", "will", "would", "could", "should", "may",
        "says", "say", "said", "after", "over", "into", "about", "amid", "but",
        "not", "you", "your", "out", "more", "than", "new", "how", "why",
        "what", "who", "all", "can", "off", "own", "too", "now", "way", "get",
        "one", "two", "near", "back", "being", "against"
    )

    /**
     * Content words, with trailing plurals folded so "agency sites" and
     * "another site" can meet. Without the fold, one observed pair of reports
     * on the same story shared only two words and could not have clustered at
     * any threshold; with it, three.
     */
    private fun tokenise(title: String): Set<String> =
        title.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(' ')
            .filter { it.length > 2 && it !in STOPWORDS }
            .map { w ->
                if (w.length > 4 && w.endsWith("s") && !w.endsWith("ss")) w.dropLast(1)
                else w
            }
            .toSet()

    /**
     * How much of the shorter headline must appear in the longer one.
     *
     * Tuned against fourteen real headline pairs from these feeds, not picked by
     * feel. Merges made, out of six pairs reporting one event, against eight
     * pairs that must stay apart:
     *
     *     0.60   3 of 6   no false merges
     *     0.55   4 of 6   no false merges   <- this
     *     0.50   4 of 6   no false merges   (buys nothing over 0.55)
     *
     * So 0.55 is the loosest value that gains anything. What 0.60 lost: "Trump
     * rejects Iran deal to reopen Strait of Hormuz" against "Iran says it has
     * given US plan to reopen Strait of Hormuz" scores 0.56 - two angles on one
     * negotiation, exactly the redundancy this exists to collapse. The nearest
     * pair that must NOT merge scores 0.43, so there is margin either side
     * rather than a value balanced on a knife edge.
     */
    private const val SAME_STORY_RATIO = 0.55

    /**
     * Containment rather than Jaccard, so a terse headline still matches a
     * verbose one about the same event: "Ed Sheeran concerts cancelled" sits
     * wholly inside "Ed Sheeran's next US concerts cancelled due to storm
     * warning", which Jaccard would score far too low to merge.
     *
     * Three shared words minimum, so two stories sharing only a country and a
     * verb are never fused.
     *
     * Two kinds of miss are accepted, both measured: reports of one event
     * sharing almost no vocabulary ("...killed in Athens gas leak explosion"
     * against "...among six dead in building explosion close to Acropolis"),
     * and paraphrases far enough apart to read as separate stories anyway.
     * Nothing lexical catches those, and showing both is no offence to the
     * reader - a looser rule that did catch them would start fusing unrelated
     * news, which is.
     */
    private fun sameStory(a: Set<String>, b: Set<String>): Boolean {
        if (a.size < 3 || b.size < 3) return false
        var shared = 0
        for (t in a) if (t in b) shared++
        if (shared < 3) return false
        return shared.toDouble() / minOf(a.size, b.size) >= SAME_STORY_RATIO
    }

    private fun cluster(items: List<Item>): List<MutableList<Item>> {
        val clusters = ArrayList<MutableList<Item>>()
        val keys = ArrayList<Set<String>>()
        val links = HashSet<String>()

        // Freshest first, so the newest wording becomes the one every later
        // duplicate is compared against.
        outer@ for (it in items.sortedByDescending { it.at }) {
            val link = it.link.substringBefore('#').trimEnd('/')
            if (!links.add(link)) continue
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

    private fun rank(clusters: List<MutableList<Item>>, now: Long): List<Story> {
        val stories = ArrayList<Story>()
        for (c in clusters) {
            // The representative: freshest, then whichever headline says most.
            val lead = c.sortedWith(
                compareByDescending<Item> { it.at }.thenByDescending { it.title.length }
            ).first()
            stories.add(
                Story(
                    lead,
                    lead.region,
                    c.map { it.publisher }.distinct().size,
                    c.map { it.region }.distinct().size,
                    lead.at
                )
            )
        }
        return stories.sortedWith(
            compareByDescending<Story> { score(it, now) }.thenByDescending { it.at }
        )
    }

    /**
     * Score: corroboration first, then how far the story travelled, then
     * freshness.
     *
     * Publisher count alone cannot tell "big in Britain" from "big everywhere"
     * - BBC plus Sky is two publishers, and so is BBC plus France 24. Since
     * most of these users are in neither country, the regional term is what
     * stops the feed reading as one nation's news. It costs nothing, because
     * the clusters already existed.
     *
     *   BBC + Sky, one region, 2h old          6 + 0 + 4 = 10
     *   BBC + France 24 + NDTV, 3 regions, 2h  9 + 4 + 4 = 17
     */
    private fun score(s: Story, now: Long): Int {
        val hours = (now - s.at) / 3_600_000L
        val freshness = when {
            hours <= 3L -> 4
            hours <= 8L -> 3
            hours <= 16L -> 2
            hours <= 30L -> 1
            else -> 0
        }
        // regions - 1: a single-region story is not penalised, one that crossed
        // a border gains 2, one on three continents gains 4.
        return s.publishers * 3 + (s.regions - 1) * 2 + freshness
    }

    /**
     * The biggest story leads; then the regions take turns.
     *
     * Straight score order would fill all twelve visible cards from whichever
     * region had a busy day, which is how a feed ends up looking like one
     * country's news. Taking turns guarantees the spread, and because the
     * stories arrive already ranked, the order of the turns is itself strongest
     * region first.
     *
     * Note what this does not guarantee: category spread. There is no category
     * metadata left to weave on now that every source is a front page - and
     * front pages are mixed by construction, so it arrives anyway. Both cannot
     * be guaranteed in twelve slots; this picks the one that was asked for.
     */
    private fun weave(stories: List<Story>): List<Item> {
        if (stories.isEmpty()) return emptyList()

        val out = ArrayList<Item>()
        out.add(stories.first().item)

        val byRegion = LinkedHashMap<String, MutableList<Story>>()
        for (s in stories.drop(1)) {
            byRegion.getOrPut(s.region) { ArrayList() }.add(s)
        }
        val order = byRegion.keys.toList()

        var round = 0
        while (out.size < MAX_ITEMS) {
            var placed = false
            for (region in order) {
                val list = byRegion[region] ?: continue
                if (round < list.size) {
                    out.add(list[round].item)
                    placed = true
                    if (out.size >= MAX_ITEMS) break
                }
            }
            if (!placed) break
            round++
        }
        return out
    }

    // ---------- parsing ----------

    private val ENTITIES = listOf(
        "&nbsp;" to " ", "&rsquo;" to "’", "&lsquo;" to "‘",
        "&ldquo;" to "“", "&rdquo;" to "”", "&mdash;" to "—",
        "&ndash;" to "–", "&hellip;" to "…", "&pound;" to "£",
        "&euro;" to "€", "&copy;" to "©", "&deg;" to "°"
    )

    private val UNKNOWN_ENTITY =
        Regex("&(?!amp;|lt;|gt;|quot;|apos;|#)[A-Za-z][A-Za-z0-9]{1,9};")

    /**
     * XmlPullParser throws on any named entity it does not know, and one curly
     * quote would otherwise cost a whole feed. Common ones are translated;
     * anything still unrecognised is dropped rather than allowed to abort.
     */
    private fun sanitise(xml: String): String {
        var s = xml
        for ((k, v) in ENTITIES) s = s.replace(k, v)
        return UNKNOWN_ENTITY.replace(s, "")
    }

    private val HTML_TAG = Regex("<[^>]{1,400}>")

    /**
     * Some feed titles contain markup. Sky News was observed publishing an item
     * whose entire title was an escaped anchor element, which decoded and
     * rendered would put visible HTML in a card. Tags out, whitespace
     * collapsed, and the junk gate then sees the real words.
     */
    private fun cleanTitle(raw: String): String =
        HTML_TAG.replace(raw, " ").replace(Regex("\\s+"), " ").trim()

    private fun parseFeed(xml: String, src: Source): List<Item> {
        val items = ArrayList<Item>()
        try {
            val p = Xml.newPullParser()
            // Off, so media:thumbnail arrives under that exact name rather than
            // needing the Yahoo media namespace declared and matched.
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(StringReader(sanitise(xml)))

            var inItem = false
            var title = ""
            var link = ""
            var date = ""
            var image = ""
            var imageWidth = -1

            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                val name = p.name ?: ""
                if (ev == XmlPullParser.START_TAG) {
                    if (name.equals("item", true) || name.equals("entry", true)) {
                        inItem = true
                        title = ""; link = ""; date = ""
                        image = ""; imageWidth = -1
                    } else if (inItem) {
                        when {
                            name.equals("title", true) ->
                                title = cleanTitle(p.nextText())

                            // RSS puts the url in the element's text; Atom puts
                            // it in href and lists several with different rel
                            // values, of which only alternate is the article.
                            name.equals("link", true) -> {
                                val href = p.getAttributeValue(null, "href")
                                val rel = p.getAttributeValue(null, "rel") ?: ""
                                val value =
                                    if (!href.isNullOrBlank()) href.trim()
                                    else p.nextText().trim()
                                if (link.isEmpty() &&
                                    (rel.isEmpty() || rel.equals("alternate", true))) {
                                    link = value
                                }
                            }

                            name.equals("pubDate", true) ||
                            name.equals("published", true) -> date = p.nextText().trim()

                            // Atom's fallback, and only if nothing better came.
                            name.equals("updated", true) ->
                                if (date.isEmpty()) date = p.nextText().trim()

                            name.equals("media:thumbnail", true) ||
                            name.equals("media:content", true) ||
                            name.equals("enclosure", true) -> {
                                val u = p.getAttributeValue(null, "url") ?: ""
                                val type = p.getAttributeValue(null, "type") ?: ""
                                val w = (p.getAttributeValue(null, "width") ?: "")
                                    .toIntOrNull() ?: 0
                                val low = u.lowercase()
                                // enclosure carries podcast audio as often as a
                                // picture.
                                val isImage = type.startsWith("image") ||
                                    low.contains(".jpg") || low.contains(".jpeg") ||
                                    low.contains(".png") || low.contains(".webp")
                                // The same picture is often offered at several
                                // sizes; keep the largest declared.
                                if (u.isNotEmpty() && isImage && w > imageWidth) {
                                    image = u
                                    imageWidth = w
                                }
                            }
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG && inItem &&
                           (name.equals("item", true) || name.equals("entry", true))) {
                    inItem = false
                    if (title.isNotEmpty() && link.isNotEmpty()) {
                        items.add(
                            Item(
                                title, link, resize(image),
                                src.publisher, src.region, parseDate(date)
                            )
                        )
                    }
                }
                ev = p.next()
            }
        } catch (e: Exception) {
            // A malformed feed still contributes whatever parsed before it broke.
        }
        return items
    }

    /**
     * Publishers size thumbnails for a list row. This card is a full-width
     * image 180dp tall, so on a 1080p screen it is a ~1080px surface: the BBC's
     * 240px crop lands visibly soft, and Sky's 1920x1080 original lands at
     * several hundred KB per card. Both CDNs put the size in the URL path.
     * Anything unrecognised is left exactly as it came, and a wrong guess is
     * cosmetic - home.html preloads each image and paints it only on success.
     */
    private fun resize(url: String): String {
        if (url.isEmpty()) return url
        if (url.contains("ichef.bbci.co.uk")) {
            return url.replace(Regex("/\\d{2,4}/cpsprodpb/"), "/800/cpsprodpb/")
        }
        if (url.contains("365dm.com")) {
            return url.replace(Regex("/\\d{3,4}x\\d{3,4}/"), "/768x432/")
        }
        return url
    }

    private val RFC822 = arrayOf(
        "EEE, dd MMM yyyy HH:mm:ss zzz",  // ... 13:23:59 GMT
        "EEE, dd MMM yyyy HH:mm:ss Z",    // ... 15:49:57 +0000
        "EEE, dd MMM yyyy HH:mm zzz"
    )

    /** ISO-8601, once normalised into a shape SimpleDateFormat will take. */
    private const val ISO = "yyyy-MM-dd'T'HH:mm:ssZ"

    /**
     * Freshness decides eligibility, so a date this cannot read costs the item
     * - and if that happened to every item, [build] notices and falls back
     * rather than showing an empty feed.
     */
    private fun parseDate(raw: String): Long {
        if (raw.isEmpty()) return 0L
        for (f in RFC822) {
            try {
                // Locale.US: these feeds name months in English wherever the
                // phone happens to be.
                val d = java.text.SimpleDateFormat(f, java.util.Locale.US).parse(raw)
                if (d != null) return d.time
            } catch (e: Exception) {
                // Try the next shape.
            }
        }
        // Atom: 2026-09-26T13:23:59Z, or a +05:30 style offset, either possibly
        // carrying fractional seconds. The X pattern letter would take the
        // offset directly but only from API 24, and normalising is cheaper than
        // caring.
        try {
            var s = raw.trim()
            s = s.replace(Regex("\\.\\d+"), "")
            s = if (s.endsWith("Z")) s.dropLast(1) + "+0000"
                else s.replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
            val d = java.text.SimpleDateFormat(ISO, java.util.Locale.US).parse(s)
            if (d != null) return d.time
        } catch (e: Exception) {
            // Genuinely unreadable.
        }
        return 0L
    }

    private fun toJson(items: List<Item>): String {
        val out = JSONArray()
        for (it in items) {
            val o = JSONObject()
            o.put("title", it.title)
            o.put("link", it.link)
            o.put("image", it.image)
            o.put("source", it.publisher)
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
     * Takes the cached feed and its age together. cachedAt used to live only in
     * memory, so the first request after every cold start compared against a
     * zero timestamp, decided the cache was ancient and went to the network
     * however fresh the file was - around 200KB of someone's mobile data, every
     * launch. The file's own mtime is the timestamp.
     */
    private fun loadDisk(context: Context) {
        try {
            val f = File(context.filesDir, CACHE_FILE)
            if (!f.exists()) return
            val text = f.readText()
            if (text.isNotBlank()) {
                cache = text
                cachedAt = f.lastModified()
            }
        } catch (e: Exception) { /* best effort */ }
    }
}
