package app.nindo

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sensei's daily picks: once a day (first app open after 6 am, or the 6:30 alarm) it reads each
 * interest's Google News feed and YouTube channel feeds, lets the brain pick at most 1 update and
 * 2 videos, and saves them. The screen only ever reads the saved picks, so it works offline.
 *
 * Nindō's own traffic skips the VPN filter (DnsVpnService excludes this app), so blocked
 * domains such as youtube.com don't stop these fetches.
 */
object Feeds {
    const val ACTION_REFRESH = "app.nindo.SENSEI_REFRESH"
    private const val MORNING_HOUR = 6
    private const val PER_FEED = 15

    /** True while a refresh runs, for the screen's "finding picks" state. */
    val running = MutableStateFlow(false)
    private val busy = AtomicBoolean(false)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("sensei", Context.MODE_PRIVATE)
    private fun lastDay(ctx: Context) = prefs(ctx).getString("picks_day", null)

    // ------------------------------------------------------------ triggers

    /** Daily alarm at 6:30. Inexact on purpose: a few minutes either way doesn't matter. */
    fun schedule(ctx: Context) {
        val at = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, MORNING_HOUR); set(Calendar.MINUTE, 30); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        val pi = PendingIntent.getBroadcast(ctx, 40, Intent(ctx, FeatureReceiver::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        ctx.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    /** App open or alarm: refresh in the background if today has no picks yet and it's morning. */
    fun refreshIfDue(ctx: Context) {
        if (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < MORNING_HOUR) return
        if (lastDay(ctx) == today()) return
        Thread { runCatching { refresh(ctx.applicationContext) } }.start()
    }

    /** "Find picks now" on the screen. */
    fun refreshAsync(ctx: Context) { Thread { runCatching { refresh(ctx.applicationContext, force = true) } }.start() }

    /** After an interest is added or changed: pick again now, so a new topic or channel shows up today. */
    fun afterInterestsChanged(ctx: Context) { runCatching { refresh(ctx.applicationContext, force = true) } }

    /** What the last refresh managed to read, shown under "For you" so problems are visible. */
    data class Report(val at: Long, val newsOk: Int, val newsTotal: Int, val channelsOk: Int, val channelsTotal: Int,
                      val news: Int, val videos: Int, val unresolved: List<String>)

    fun report(ctx: Context): Report? = prefs(ctx).getString("report", null)?.split("\u0001")?.takeIf { it.size >= 7 }?.let { f ->
        runCatching { Report(f[0].toLong(), f[1].toInt(), f[2].toInt(), f[3].toInt(), f[4].toInt(), f[5].toInt(), f[6].toInt(),
            f.drop(7).filter { it.isNotEmpty() }) }.getOrNull()
    }

    private fun saveReport(ctx: Context, r: Report) = prefs(ctx).edit().putString("report",
        (listOf(r.at, r.newsOk, r.newsTotal, r.channelsOk, r.channelsTotal, r.news, r.videos).map { it.toString() } + r.unresolved)
            .joinToString("\u0001")).apply()

    // ------------------------------------------------------------- refresh

    /**
     * Fetches and saves today's picks. Returns false when nothing could be fetched (offline):
     * then today stays un-refreshed and the next app open tries again.
     */
    fun refresh(ctx: Context, force: Boolean = false): Boolean {
        if (!force && lastDay(ctx) == today()) return true
        if (!busy.compareAndSet(false, true)) return false
        running.value = true
        try {
            val db = GuardApp.sensei
            val interests = resolveChannels(db, db.interests())
            if (interests.isEmpty()) return true

            val now = System.currentTimeMillis()
            val candidates = ArrayList<Candidate>()
            var newsOk = 0; var channelsOk = 0; var channelsTotal = 0
            for (i in interests) {
                val newsItems = http(FeedParser.newsUrl(i))?.let(FeedParser::parse).orEmpty()
                if (newsItems.isNotEmpty()) newsOk++
                newsItems.take(PER_FEED).forEach {
                    candidates += Candidate(PickKind.NEWS, i.id, it.title, it.url, null, it.publishedMs)
                }
                val own = i.channels.mapNotNull { FeedParser.channelIdIn(it) }
                val starter = StarterChannels.forLabel(i.label).filter { it !in own }
                val strict = StarterChannels.strict(i.label)
                val words = (i.keywords + i.label).map { it.lowercase() }
                for (ch in own + starter) {
                    channelsTotal++
                    val items = videosOf(ch)
                    if (items.isEmpty()) continue
                    channelsOk++
                    // Old videos are fine for learning; newer ones simply rank a little higher.
                    items.asSequence()
                        .filter { !FeedParser.isShort(it.url) && it.videoId != null }
                        // Broad starter channels: only videos about this topic. The user's own channels: anything new.
                        .filter { ch in own || !strict || words.any { w -> it.title.lowercase().contains(w) } }
                        .take(PER_FEED)
                        .forEach { candidates += Candidate(PickKind.VIDEO, i.id, it.title, it.url, it.videoId, it.publishedMs) }
                }
            }
            val unresolved = interests.flatMap { i -> i.channels.filter { FeedParser.channelIdIn(it) == null } }
            if (newsOk == 0 && channelsOk == 0) {
                saveReport(ctx, Report(now, 0, interests.size, 0, channelsTotal, 0, 0, unresolved))
                return false
            }

            val brain = Sensei.brain
            val byId = interests.associateBy { it.id }
            val t = today()
            val ranked = brain.rankPicks(candidates, interests, db.shownUrls(), now)
            val keepOpened = db.picks(t).filter { it.opened }.associateBy { it.url }
            db.savePicks(t, ranked.map { c ->
                Pick(day = t, kind = c.kind, interestId = c.interestId, title = c.title, url = c.url, videoId = c.videoId,
                    reason = brain.reasonFor(byId.getValue(c.interestId), c), opened = keepOpened.containsKey(c.url))
            })
            prefs(ctx).edit().putString("picks_day", t).apply()
            saveReport(ctx, Report(now, newsOk, interests.size, channelsOk, channelsTotal,
                ranked.count { it.kind == PickKind.NEWS }, ranked.count { it.kind == PickKind.VIDEO }, unresolved))
            return true
        } finally {
            busy.set(false)
            running.value = false
        }
    }

    /** Turns "@handle" and channel links into channel ids once, and saves them on the interest. */
    private fun resolveChannels(db: SenseiDb, interests: List<Interest>): List<Interest> = interests.map { i ->
        if (i.channels.all { FeedParser.channelIdIn(it) != null }) return@map i
        val resolved = i.channels.map { raw ->
            FeedParser.channelIdIn(raw) ?: FeedParser.channelPageFor(raw)?.let { page -> http(page)?.let(FeedParser::channelIdInPage) } ?: raw
        }
        if (resolved != i.channels) i.copy(channels = resolved).also { db.upsertInterest(it) } else i
    }

    /** A channel's latest videos, trying each feed form until one answers. */
    private fun videosOf(channelId: String): List<FeedParser.Item> {
        for (url in FeedParser.videoFeedsFor(channelId)) {
            val items = http(url)?.let(FeedParser::parse).orEmpty()
            if (items.isNotEmpty()) return items
        }
        return emptyList()
    }

    // ---------------------------------------------------------------- http

    private const val MAX_BYTES = 3 * 1024 * 1024

    private fun http(url: String): String? = runCatching {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 10_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Nindo")
            setRequestProperty("Accept-Language", "en-IN,en;q=0.9")
        }
        try {
            if (c.responseCode !in 200..299) return null
            c.inputStream.use { s ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (out.size() < MAX_BYTES) { val n = s.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                out.toString("UTF-8")
            }
        } finally { c.disconnect() }
    }.getOrNull()
}
