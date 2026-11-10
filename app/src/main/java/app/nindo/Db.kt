package app.nindo

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Kinds of timeline events. */
object Kind {
    const val OPEN = "open"
    const val VISIT = "visit"
    const val BLOCK = "block"
    const val SCROLL = "scroll"
    const val NUDGE = "nudge"
    const val DNS_BLOCK = "dns_block"
    const val FOCUS = "focus"
    const val STRICT = "strict"
    const val TAMPER = "tamper"
    const val REELS = "reels"
    const val URGE = "urge"
}

data class Event(val ts: Long, val kind: String, val pkg: String?, val title: String, val detail: String?, val durMs: Long)
data class AppStat(val pkg: String, val ms: Long, val opens: Int, val scrollMs: Long, val swipes: Int, val blocks: Int)
data class SiteStat(val host: String, val ms: Long, val visits: Int, val blocks: Int)
data class DayStat(
    val day: String, val ms: Long = 0, val opens: Int = 0, val reels: Int = 0, val scrollMs: Long = 0, val blocks: Int = 0,
    /** Pause screens closed without continuing. */
    val urgesResisted: Int = 0,
)
data class DnsStat(val domain: String, val queries: Int, val blocked: Int)
data class Totals(
    val screenMs: Long, val opens: Int, val scrollMs: Long, val swipes: Int,
    val blocks: Int, val nudges: Int, val dnsQueries: Int, val dnsBlocked: Int,
    val reels: Int = 0,
)

/**
 * On-device tracker. Daily aggregates feed limits and the dashboard; the events
 * table is the timeline. Everything is kept for 30 days.
 */
class Db(ctx: Context) : SQLiteOpenHelper(ctx, "tracker.db", null, 2) {

    // WAL: screens can read while the watcher or the history import writes, instead of waiting for each other.
    init { setWriteAheadLoggingEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE app_daily(day TEXT, pkg TEXT, ms INTEGER DEFAULT 0, opens INTEGER DEFAULT 0, scroll_ms INTEGER DEFAULT 0, swipes INTEGER DEFAULT 0, blocks INTEGER DEFAULT 0, reels INTEGER DEFAULT 0, PRIMARY KEY(day, pkg))")
        db.execSQL("CREATE TABLE site_daily(day TEXT, host TEXT, ms INTEGER DEFAULT 0, visits INTEGER DEFAULT 0, blocks INTEGER DEFAULT 0, PRIMARY KEY(day, host))")
        db.execSQL("CREATE TABLE dns_daily(day TEXT, domain TEXT, queries INTEGER DEFAULT 0, blocked INTEGER DEFAULT 0, PRIMARY KEY(day, domain))")
        db.execSQL("CREATE TABLE hourly(day TEXT, hour INTEGER, ms INTEGER DEFAULT 0, PRIMARY KEY(day, hour))")
        db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, kind TEXT, pkg TEXT, title TEXT, detail TEXT, dur_ms INTEGER DEFAULT 0)")
        db.execSQL("CREATE INDEX events_ts ON events(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE app_daily ADD COLUMN reels INTEGER DEFAULT 0")
    }

    /** Bumped on every write so screens can refresh. */
    val writes = AtomicLong(0)

    /** Adds to counters, creating the row if needed (works on SQLite without UPSERT). */
    private fun bump(table: String, keys: Map<String, Any>, adds: Map<String, Long>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cv = ContentValues()
            keys.forEach { (k, v) -> if (v is Int) cv.put(k, v) else cv.put(k, v.toString()) }
            db.insertWithOnConflict(table, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
            val set = adds.keys.joinToString(",") { "$it = $it + ?" }
            val where = keys.keys.joinToString(" AND ") { "$it = ?" }
            val args: Array<Any> = (adds.values.toList() + keys.values.map { it.toString() }).toTypedArray()
            db.execSQL("UPDATE $table SET $set WHERE $where", args)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        writes.incrementAndGet()
    }

    // ---------- writes ----------
    fun addAppTime(pkg: String, ms: Long) {
        if (ms <= 0) return
        val d = today()
        bump("app_daily", mapOf("day" to d, "pkg" to pkg), mapOf("ms" to ms))
        bump("hourly", mapOf("day" to d, "hour" to Calendar.getInstance().get(Calendar.HOUR_OF_DAY)), mapOf("ms" to ms))
    }

    fun addOpen(pkg: String) = bump("app_daily", mapOf("day" to today(), "pkg" to pkg), mapOf("opens" to 1))
    fun addAppBlock(pkg: String) = bump("app_daily", mapOf("day" to today(), "pkg" to pkg), mapOf("blocks" to 1))
    fun addScroll(pkg: String, ms: Long, swipes: Int) =
        bump("app_daily", mapOf("day" to today(), "pkg" to pkg), mapOf("scroll_ms" to ms, "swipes" to swipes.toLong()))

    fun addSiteTime(host: String, ms: Long) {
        if (ms > 0) bump("site_daily", mapOf("day" to today(), "host" to host), mapOf("ms" to ms))
    }
    fun addSiteVisit(host: String) = bump("site_daily", mapOf("day" to today(), "host" to host), mapOf("visits" to 1))
    fun addSiteBlock(host: String) = bump("site_daily", mapOf("day" to today(), "host" to host), mapOf("blocks" to 1))

    // ---------- backdated writes (Android usage-history import) ----------
    fun addAppTimeAt(pkg: String, at: Long, ms: Long) {
        if (ms <= 0) return
        val c = Calendar.getInstance().apply { timeInMillis = at }
        val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.time)
        bump("app_daily", mapOf("day" to d, "pkg" to pkg), mapOf("ms" to ms))
        bump("hourly", mapOf("day" to d, "hour" to c.get(Calendar.HOUR_OF_DAY)), mapOf("ms" to ms))
    }

    fun addOpenAt(pkg: String, at: Long) =
        bump("app_daily", mapOf("day" to SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(at)), "pkg" to pkg), mapOf("opens" to 1))

    fun eventAt(ts: Long, kind: String, pkg: String?, title: String, detail: String? = null, durMs: Long = 0) {
        writableDatabase.insert("events", null, ContentValues().apply {
            put("ts", ts); put("kind", kind); put("pkg", pkg)
            put("title", title); put("detail", detail); put("dur_ms", durMs)
        })
        writes.incrementAndGet()
    }

    fun firstEventTs(kind: String): Long? =
        readableDatabase.rawQuery("SELECT MIN(ts) FROM events WHERE kind=?", arrayOf(kind)).use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
        }

    fun <T> inTransaction(block: () -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try { return block().also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }

    fun event(kind: String, pkg: String?, title: String, detail: String? = null, durMs: Long = 0) {
        writableDatabase.insert("events", null, ContentValues().apply {
            put("ts", System.currentTimeMillis()); put("kind", kind); put("pkg", pkg)
            put("title", title); put("detail", detail); put("dur_ms", durMs)
        })
        writes.incrementAndGet()
    }

    // DNS lookups arrive in bursts from several threads: count in memory, write in batches.
    private val dnsBuf = ConcurrentHashMap<String, IntArray>()

    fun countDns(domain: String, blocked: Boolean) {
        val c = dnsBuf.getOrPut(domain) { IntArray(2) }
        synchronized(c) { c[0]++; if (blocked) c[1]++ }
    }

    fun flushDns() {
        if (dnsBuf.isEmpty()) return
        val d = today()
        val keys = dnsBuf.keys.toList()
        for (k in keys) {
            val c = dnsBuf.remove(k) ?: continue
            bump("dns_daily", mapOf("day" to d, "domain" to k), mapOf("queries" to c[0].toLong(), "blocked" to c[1].toLong()))
        }
    }

    /** Daily totals are kept 180 days (for streaks and reports), the timeline 30 days. */
    fun prune() {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val dayCut = fmt.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -180) }.time)
        val tsCut = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -30) }.timeInMillis
        val db = writableDatabase
        listOf("app_daily", "site_daily", "hourly").forEach { db.delete(it, "day < ?", arrayOf(dayCut)) }
        db.delete("dns_daily", "day < ?", arrayOf(fmt.format(Date(tsCut))))
        db.delete("events", "ts < ?", arrayOf(tsCut.toString()))
    }

    fun clearAll() {
        val db = writableDatabase
        listOf("app_daily", "site_daily", "dns_daily", "hourly", "events").forEach { db.delete(it, null, null) }
        writes.incrementAndGet()
    }

    // ---------- reads ----------
    fun appUsage(pkg: String, day: String = today()): Pair<Long, Int> =
        readableDatabase.rawQuery("SELECT ms, opens FROM app_daily WHERE day=? AND pkg=?", arrayOf(day, pkg)).use {
            if (it.moveToFirst()) it.getLong(0) to it.getInt(1) else 0L to 0
        }

    fun topApps(day: String = today(), limit: Int = 8, by: String = "ms"): List<AppStat> =
        readableDatabase.rawQuery(
            "SELECT pkg, ms, opens, scroll_ms, swipes, blocks FROM app_daily WHERE day=? ORDER BY $by DESC LIMIT $limit",
            arrayOf(day)
        ).use { c ->
            buildList { while (c.moveToNext()) add(AppStat(c.getString(0), c.getLong(1), c.getInt(2), c.getLong(3), c.getInt(4), c.getInt(5))) }
        }

    fun topSites(day: String = today(), limit: Int = 8): List<SiteStat> =
        readableDatabase.rawQuery(
            "SELECT host, ms, visits, blocks FROM site_daily WHERE day=? ORDER BY ms DESC, visits DESC LIMIT $limit", arrayOf(day)
        ).use { c -> buildList { while (c.moveToNext()) add(SiteStat(c.getString(0), c.getLong(1), c.getInt(2), c.getInt(3))) } }

    fun topDns(day: String = today(), limit: Int = 30, blockedOnly: Boolean = false): List<DnsStat> =
        readableDatabase.rawQuery(
            "SELECT domain, queries, blocked FROM dns_daily WHERE day=? ${if (blockedOnly) "AND blocked > 0" else ""} ORDER BY ${if (blockedOnly) "blocked" else "queries"} DESC LIMIT $limit",
            arrayOf(day)
        ).use { c -> buildList { while (c.moveToNext()) add(DnsStat(c.getString(0), c.getInt(1), c.getInt(2))) } }

    fun hourly(day: String = today()): LongArray {
        val out = LongArray(24)
        readableDatabase.rawQuery("SELECT hour, ms FROM hourly WHERE day=?", arrayOf(day)).use { c ->
            while (c.moveToNext()) out[c.getInt(0).coerceIn(0, 23)] = c.getLong(1)
        }
        return out
    }

    /** Screen time for the last [n] days, oldest first, as (yyyy-MM-dd, ms). */
    fun lastDays(n: Int = 7): List<Pair<String, Long>> {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -(n - 1)) }
        val days = (0 until n).map { fmt.format(cal.time).also { cal.add(Calendar.DAY_OF_YEAR, 1) } }
        val map = HashMap<String, Long>()
        readableDatabase.rawQuery("SELECT day, SUM(ms) FROM app_daily WHERE day >= ? GROUP BY day", arrayOf(days.first())).use { c ->
            while (c.moveToNext()) map[c.getString(0)] = c.getLong(1)
        }
        return days.map { it to (map[it] ?: 0L) }
    }

    /** Per-day totals for the calendar, keyed by yyyy-MM-dd (days with no data are missing). */
    fun dayStats(from: String, to: String): Map<String, DayStat> {
        val db = readableDatabase
        val out = HashMap<String, DayStat>()
        db.rawQuery("SELECT day, SUM(ms), SUM(opens), SUM(reels), SUM(scroll_ms) FROM app_daily WHERE day>=? AND day<=? GROUP BY day",
            arrayOf(from, to)).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = DayStat(c.getString(0), c.getLong(1), c.getInt(2), c.getInt(3), c.getLong(4))
        }
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val fromTs = fmt.parse(from)!!.time
        val toTs = fmt.parse(to)!!.time + 86_400_000L
        db.rawQuery("SELECT strftime('%Y-%m-%d', ts / 1000, 'unixepoch', 'localtime') d, COUNT(*) FROM events " +
            "WHERE kind IN ('${Kind.BLOCK}','${Kind.DNS_BLOCK}') AND ts>=? AND ts<? GROUP BY d", arrayOf(fromTs.toString(), toTs.toString())).use { c ->
            while (c.moveToNext()) {
                val d = c.getString(0)
                out[d] = (out[d] ?: DayStat(d)).copy(blocks = c.getInt(1))
            }
        }
        db.rawQuery("SELECT strftime('%Y-%m-%d', ts / 1000, 'unixepoch', 'localtime') d, COUNT(*) FROM events " +
            "WHERE kind='${Kind.URGE}' AND detail='resisted' AND ts>=? AND ts<? GROUP BY d", arrayOf(fromTs.toString(), toTs.toString())).use { c ->
            while (c.moveToNext()) {
                val d = c.getString(0)
                out[d] = (out[d] ?: DayStat(d)).copy(urgesResisted = c.getInt(1))
            }
        }
        return out
    }

    /** The timeline for one day, newest first. */
    fun eventsOn(day: String, kinds: Collection<String>? = null, limit: Int = 500): List<Event> {
        val start = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(day)!!.time
        val kindSql = kinds?.let { k -> " AND kind IN (${k.joinToString(",") { "'$it'" }})" } ?: ""
        return readableDatabase.rawQuery(
            "SELECT ts, kind, pkg, title, detail, dur_ms FROM events WHERE ts>=? AND ts<?$kindSql ORDER BY ts DESC LIMIT $limit",
            arrayOf(start.toString(), (start + 86_400_000L).toString())
        ).use { c ->
            buildList { while (c.moveToNext()) add(Event(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getLong(5))) }
        }
    }

    fun totals(day: String = today()): Totals {
        val db = readableDatabase
        var screen = 0L; var opens = 0; var scroll = 0L; var swipes = 0; var reels = 0
        db.rawQuery("SELECT SUM(ms), SUM(opens), SUM(scroll_ms), SUM(swipes), SUM(reels) FROM app_daily WHERE day=?", arrayOf(day)).use {
            if (it.moveToFirst()) { screen = it.getLong(0); opens = it.getInt(1); scroll = it.getLong(2); swipes = it.getInt(3); reels = it.getInt(4) }
        }
        val start = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(day)!!.time
        val end = start + 86_400_000L
        fun count(kind: String) = db.rawQuery("SELECT COUNT(*) FROM events WHERE kind=? AND ts>=? AND ts<?",
            arrayOf(kind, start.toString(), end.toString())).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        var q = 0; var b = 0
        db.rawQuery("SELECT SUM(queries), SUM(blocked) FROM dns_daily WHERE day=?", arrayOf(day)).use {
            if (it.moveToFirst()) { q = it.getInt(0); b = it.getInt(1) }
        }
        return Totals(screen, opens, scroll, swipes, count(Kind.BLOCK), count(Kind.NUDGE), q, b, reels)
    }

    fun events(kinds: Collection<String>? = null, limit: Int = 1000): List<Event> {
        val where = kinds?.let { k -> "WHERE kind IN (${k.joinToString(",") { "'$it'" }})" } ?: ""
        return readableDatabase.rawQuery(
            "SELECT ts, kind, pkg, title, detail, dur_ms FROM events $where ORDER BY ts DESC LIMIT $limit", null
        ).use { c ->
            buildList { while (c.moveToNext()) add(Event(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getLong(5))) }
        }
    }

    // ---------- reels ----------
    fun addReel(pkg: String) = bump("app_daily", mapOf("day" to today(), "pkg" to pkg), mapOf("reels" to 1))

    fun reelsToday(): Int = readableDatabase.rawQuery("SELECT SUM(reels) FROM app_daily WHERE day=?", arrayOf(today())).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    /** (package, reels) for a day, most first. */
    fun reelsByApp(day: String = today()): List<Pair<String, Int>> =
        readableDatabase.rawQuery("SELECT pkg, reels FROM app_daily WHERE day=? AND reels > 0 ORDER BY reels DESC", arrayOf(day)).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getInt(1)) }
        }

    fun reelsRange(from: String, to: String): Int =
        readableDatabase.rawQuery("SELECT SUM(reels) FROM app_daily WHERE day>=? AND day<=?", arrayOf(from, to)).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    /** Reels per day for the last [n] days, oldest first. */
    fun reelDays(n: Int = 7): List<Pair<String, Int>> {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -(n - 1)) }
        val days = (0 until n).map { fmt.format(cal.time).also { cal.add(Calendar.DAY_OF_YEAR, 1) } }
        val map = HashMap<String, Int>()
        readableDatabase.rawQuery("SELECT day, SUM(reels) FROM app_daily WHERE day >= ? GROUP BY day", arrayOf(days.first())).use { c ->
            while (c.moveToNext()) map[c.getString(0)] = c.getInt(1)
        }
        return days.map { it to (map[it] ?: 0) }
    }

    // ---------- ranges (weekly report, streaks) ----------
    fun rangeApps(from: String, to: String, limit: Int = 5): List<AppStat> =
        readableDatabase.rawQuery(
            "SELECT pkg, SUM(ms), SUM(opens), SUM(scroll_ms), SUM(swipes), SUM(blocks) FROM app_daily WHERE day>=? AND day<=? GROUP BY pkg ORDER BY 2 DESC LIMIT $limit",
            arrayOf(from, to)
        ).use { c -> buildList { while (c.moveToNext()) add(AppStat(c.getString(0), c.getLong(1), c.getInt(2), c.getLong(3), c.getInt(4), c.getInt(5))) } }

    /** (screen ms, opens, scroll ms) summed over days [from]..[to]. */
    fun rangeUsage(from: String, to: String): Triple<Long, Int, Long> =
        readableDatabase.rawQuery("SELECT SUM(ms), SUM(opens), SUM(scroll_ms) FROM app_daily WHERE day>=? AND day<=?", arrayOf(from, to)).use {
            if (it.moveToFirst()) Triple(it.getLong(0), it.getInt(1), it.getLong(2)) else Triple(0L, 0, 0L)
        }

    fun countEvents(kinds: Collection<String>, fromTs: Long, toTs: Long): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM events WHERE kind IN (${kinds.joinToString(",") { "'$it'" }}) AND ts>=? AND ts<?",
            arrayOf(fromTs.toString(), toTs.toString())
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun focusMs(fromTs: Long, toTs: Long): Long =
        readableDatabase.rawQuery("SELECT SUM(dur_ms) FROM events WHERE kind='${Kind.FOCUS}' AND ts>=? AND ts<?",
            arrayOf(fromTs.toString(), toTs.toString())).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    fun dnsBlocked(from: String, to: String): Int =
        readableDatabase.rawQuery("SELECT SUM(blocked) FROM dns_daily WHERE day>=? AND day<=?", arrayOf(from, to)).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
}
