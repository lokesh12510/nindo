package app.nindo

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/*
 * Reads Google News RSS and YouTube channel (Atom) feeds, and finds YouTube channel ids.
 * Plain JVM code (javax.xml), so it's unit-tested without a phone.
 */
object FeedParser {

    data class Item(val title: String, val url: String, val videoId: String? = null, val publishedMs: Long = 0, val source: String? = null)

    /** Parses either RSS 2.0 or Atom. Returns an empty list for anything it can't read. */
    fun parse(xml: String): List<Item> = runCatching {
        val f = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        val root = f.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
        if (root.name() == "feed") atom(root) else rss(root)
    }.getOrDefault(emptyList())

    private fun Element.name(): String = localName ?: nodeName.substringAfter(':')
    private fun Element.kids(name: String): List<Element> {
        val out = ArrayList<Element>()
        val n = childNodes
        for (i in 0 until n.length) (n.item(i) as? Element)?.let { if (it.name() == name) out += it }
        return out
    }
    private fun Element.kid(name: String): Element? = kids(name).firstOrNull()
    private fun Element.text(name: String): String? = kid(name)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    private fun rss(root: Element): List<Item> {
        val channel = root.kid("channel") ?: return emptyList()
        return channel.kids("item").mapNotNull { e ->
            val title = e.text("title") ?: return@mapNotNull null
            val link = e.text("link") ?: return@mapNotNull null
            val source = e.text("source")
            Item(cleanTitle(title, source), link, null, rfc822(e.text("pubDate")), source)
        }
    }

    private fun atom(root: Element): List<Item> = root.kids("entry").mapNotNull { e ->
        val title = e.text("title") ?: return@mapNotNull null
        val link = e.kids("link").firstOrNull { (it.getAttribute("rel").ifEmpty { "alternate" }) == "alternate" }?.getAttribute("href")
            ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        Item(title, link, e.text("videoId") ?: videoIdIn(link), iso(e.text("published") ?: e.text("updated")))
    }

    /** Google News titles end with " - Source"; the source is kept apart. */
    fun cleanTitle(title: String, source: String?): String =
        if (source != null && title.endsWith(" - $source")) title.removeSuffix(" - $source").trim() else title

    private fun rfc822(s: String?): Long = s?.let {
        runCatching { SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(it)?.time }.getOrNull()
    } ?: 0L

    private fun iso(s: String?): Long = s?.let {
        runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(it)?.time }.getOrNull()
    } ?: 0L

    // ------------------------------------------------------------ youtube

    private val CHANNEL_ID = Regex("UC[A-Za-z0-9_-]{22}")
    private val VIDEO_ID = Regex("""(?:v=|youtu\.be/|/shorts/|/embed/|/live/)([A-Za-z0-9_-]{11})""")

    fun channelIdIn(s: String): String? = CHANNEL_ID.find(s)?.value
    fun videoIdIn(s: String): String? = VIDEO_ID.find(s)?.groupValues?.get(1)
    fun isShort(url: String): Boolean = url.contains("/shorts/")

    /** The channel id inside a channel page's HTML (canonical link or page data). */
    fun channelIdInPage(html: String): String? =
        // A video page: the uploader is in videoDetails (other channel ids on the page are recommendations).
        Regex(""""videoDetails"\s*:\s*\{[^}]*?"channelId"\s*:\s*"(UC[A-Za-z0-9_-]{22})"""").find(html)?.groupValues?.get(1)
            ?: Regex("""itemprop="channelId" content="(UC[A-Za-z0-9_-]{22})"""").find(html)?.groupValues?.get(1)
            ?: Regex("""feeds/videos\.xml\?channel_id=(UC[A-Za-z0-9_-]{22})""").find(html)?.groupValues?.get(1)
            ?: Regex("""<link rel="canonical" href="https://www\.youtube\.com/channel/(UC[A-Za-z0-9_-]{22})"""").find(html)?.groupValues?.get(1)
            ?: Regex(""""externalId"\s*:\s*"(UC[A-Za-z0-9_-]{22})"""").find(html)?.groupValues?.get(1)
            ?: Regex(""""channelId"\s*:\s*"(UC[A-Za-z0-9_-]{22})"""").find(html)?.groupValues?.get(1)

    /** A page to look up for what the user typed: "@name", a channel/handle link, or null if it's already an id. */
    fun channelPageFor(input: String): String? {
        val s = input.trim()
        if (s.isEmpty() || channelIdIn(s) != null) return null
        return when {
            s.startsWith("@") -> "https://www.youtube.com/$s"
            s.contains("youtu.be/") -> videoIdIn(s)?.let { "https://www.youtube.com/watch?v=$it" }
            s.contains("youtube.com/") -> "https://" + s.substringAfter("://").removePrefix("m.").let { if (it.startsWith("www.")) it else "www.$it" }
            !s.contains(' ') && !s.contains('/') -> "https://www.youtube.com/@$s"
            else -> null
        }
    }

    fun channelFeedUrl(channelId: String) = "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId"

    /**
     * Feeds to try for a channel, best first. YouTube's channel feed has been failing with 404s on and
     * off, so the uploads playlists come first: "UULF" lists normal uploads only (no Shorts), "UU" all uploads.
     */
    fun videoFeedsFor(channelId: String): List<String> {
        val rest = channelId.removePrefix("UC")
        return listOf(
            "https://www.youtube.com/feeds/videos.xml?playlist_id=UULF$rest",
            channelFeedUrl(channelId),
            "https://www.youtube.com/feeds/videos.xml?playlist_id=UU$rest",
        )
    }

    /** Google News search for the last week, in Indian English. */
    fun newsUrl(i: Interest): String {
        val q = URLEncoder.encode("${i.label} when:7d", "UTF-8")
        return "https://news.google.com/rss/search?q=$q&hl=en-IN&gl=IN&ceid=IN:en"
    }
}

/**
 * A few trusted teaching channels so common topics get videos without the user finding a channel.
 * Only channel ids that were checked by hand. Other topics need a channel link on the interest.
 */
object StarterChannels {
    private const val FREECODECAMP = "UC8butISFwT-Wl7EV0hUK0BQ"
    private const val COREY_SCHAFER = "UCCezIgC97PvUuR4_gbFUs5g"
    private const val MOSH = "UCWv7vMbMWH4-V0ZXdmDpPBA"
    private const val FIRESHIP = "UCsBjURrPoezykLs9EqgamOA"

    /** [strict]: the channels cover more than this topic, so only videos whose title names it count. */
    private data class Entry(val words: Set<String>, val channels: List<String>, val strict: Boolean)

    private val entries = listOf(
        Entry(setOf("python"), listOf(COREY_SCHAFER, MOSH, FREECODECAMP), strict = true),
        Entry(setOf("coding", "programming", "javascript", "web development", "web dev", "software", "react", "java", "sql",
            "data science", "machine learning", "app development"), listOf(FREECODECAMP, MOSH, FIRESHIP), strict = false),
    )

    private fun entry(label: String): Entry? = label.lowercase().let { l -> entries.firstOrNull { e -> e.words.any { l.contains(it) } } }

    fun forLabel(label: String): List<String> = entry(label)?.channels ?: emptyList()

    /** A known topic named as a whole word in [text] ("Python 30 min" -> "Python"), or null. */
    fun topicIn(text: String): String? {
        val l = text.lowercase()
        for (e in entries) for (w in e.words.sortedByDescending { it.length }) {
            if (Regex("(?<![a-z])" + Regex.escape(w) + "(?![a-z])").containsMatchIn(l)) return w.replaceFirstChar { it.uppercase() }
        }
        return null
    }
    fun strict(label: String): Boolean = entry(label)?.strict ?: false
}
