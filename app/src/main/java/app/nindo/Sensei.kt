package app.nindo

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

// ===================================================================== model

/** Where a Sensei item came from: added by hand, or (from Phase 2C) heard in a call. */
enum class SenseiSource { MANUAL, CALL }

/** Something the user is learning or following, e.g. "Python". Shown as a chip under "What Sensei knows". */
data class Interest(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    /** Extra words to match news and videos on; the label always counts too. */
    val keywords: List<String> = emptyList(),
    /** YouTube channel links or ids to take videos from. Resolved to channel ids in the feed step. */
    val channels: List<String> = emptyList(),
    val source: SenseiSource = SenseiSource.MANUAL,
    val created: String = today(),
)

/** A one-off task, apart from habits. [due] is yyyy-MM-dd or null for "any time". */
data class SenseiTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val due: String? = null,
    val done: Boolean = false,
    /** Day it was ticked, so finished tasks stay visible (struck through) until the day ends. */
    val doneDay: String? = null,
    val source: SenseiSource = SenseiSource.MANUAL,
    val created: String = today(),
)

/** One of the day's "For you" items. */
data class Pick(
    val id: String = UUID.randomUUID().toString(),
    val day: String,
    val kind: PickKind,
    val interestId: String,
    val title: String,
    val url: String,
    val videoId: String? = null,
    val reason: String,
    val opened: Boolean = false,
)

// ================================================================== storage

/** Sensei's own database: interests, tasks and daily picks. Kept apart from habits and the tracker. */
class SenseiDb(ctx: Context) : SQLiteOpenHelper(ctx, "sensei.db", null, 1) {

    init { setWriteAheadLoggingEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE interest(id TEXT PRIMARY KEY, label TEXT NOT NULL, keywords TEXT, channels TEXT, source TEXT, created TEXT)")
        db.execSQL("CREATE TABLE task(id TEXT PRIMARY KEY, title TEXT NOT NULL, due TEXT, done INTEGER DEFAULT 0, done_day TEXT, source TEXT, created TEXT)")
        db.execSQL("CREATE TABLE pick(id TEXT PRIMARY KEY, day TEXT NOT NULL, kind TEXT NOT NULL, interest TEXT, title TEXT, url TEXT, " +
            "video_id TEXT, reason TEXT, opened INTEGER DEFAULT 0)")
        db.execSQL("CREATE INDEX pick_day ON pick(day)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private fun changed() { GuardApp.db.writes.incrementAndGet(); SenseiBus.rev.value++ }

    // Lists are stored one item per line.
    private fun List<String>.pack() = joinToString("\n") { it.replace("\n", " ").trim() }.ifEmpty { null }
    private fun String?.unpack() = this?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    private fun src(s: String?) = runCatching { SenseiSource.valueOf(s ?: "") }.getOrDefault(SenseiSource.MANUAL)

    // ---------------------------------------------------------- interests

    fun interests(): List<Interest> =
        readableDatabase.rawQuery("SELECT id,label,keywords,channels,source,created FROM interest ORDER BY created DESC, label", null).use { c ->
            buildList { while (c.moveToNext()) add(Interest(c.getString(0), c.getString(1), c.getString(2).unpack(),
                c.getString(3).unpack(), src(c.getString(4)), c.getString(5) ?: today())) }
        }

    fun upsertInterest(i: Interest) {
        writableDatabase.insertWithOnConflict("interest", null, ContentValues().apply {
            put("id", i.id); put("label", i.label.trim()); put("keywords", i.keywords.pack()); put("channels", i.channels.pack())
            put("source", i.source.name); put("created", i.created)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        changed()
    }

    /** Forgets an interest and any of today's picks made for it. */
    fun deleteInterest(id: String) {
        writableDatabase.delete("interest", "id=?", arrayOf(id))
        writableDatabase.delete("pick", "interest=? AND day>=?", arrayOf(id, today()))
        changed()
    }

    // -------------------------------------------------------------- tasks

    /** Open tasks plus the ones finished today. Overdue and dated tasks first, then by date, then newest. */
    fun tasks(day: String = today()): List<SenseiTask> =
        readableDatabase.rawQuery("SELECT id,title,due,done,done_day,source,created FROM task " +
            "WHERE done=0 OR done_day=? ORDER BY done, due IS NULL, due, created DESC", arrayOf(day)).use { c ->
            buildList { while (c.moveToNext()) add(SenseiTask(c.getString(0), c.getString(1), c.getString(2), c.getInt(3) == 1,
                c.getString(4), src(c.getString(5)), c.getString(6) ?: day)) }
        }

    fun upsertTask(t: SenseiTask) {
        writableDatabase.insertWithOnConflict("task", null, ContentValues().apply {
            put("id", t.id); put("title", t.title.trim()); put("due", t.due); put("done", if (t.done) 1 else 0)
            put("done_day", t.doneDay); put("source", t.source.name); put("created", t.created)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        changed()
    }

    fun setTaskDone(id: String, done: Boolean) {
        writableDatabase.update("task", ContentValues().apply { put("done", if (done) 1 else 0); put("done_day", if (done) today() else null) },
            "id=?", arrayOf(id))
        changed()
    }

    fun deleteTask(id: String) { writableDatabase.delete("task", "id=?", arrayOf(id)); changed() }

    // -------------------------------------------------------------- picks

    fun picks(day: String = today()): List<Pick> =
        readableDatabase.rawQuery("SELECT id,day,kind,interest,title,url,video_id,reason,opened FROM pick WHERE day=? ORDER BY kind, rowid",
            arrayOf(day)).use { c ->
            buildList { while (c.moveToNext()) add(Pick(c.getString(0), c.getString(1),
                runCatching { PickKind.valueOf(c.getString(2)) }.getOrDefault(PickKind.NEWS), c.getString(3) ?: "",
                c.getString(4) ?: "", c.getString(5) ?: "", c.getString(6), c.getString(7) ?: "", c.getInt(8) == 1)) }
        }

    /** Replaces a day's picks (the feed step writes them once a day). */
    fun savePicks(day: String, picks: List<Pick>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("pick", "day=?", arrayOf(day))
            picks.forEach { p ->
                db.insert("pick", null, ContentValues().apply {
                    put("id", p.id); put("day", day); put("kind", p.kind.name); put("interest", p.interestId); put("title", p.title)
                    put("url", p.url); put("video_id", p.videoId); put("reason", p.reason); put("opened", if (p.opened) 1 else 0)
                })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        changed()
    }

    /** Every link shown in the last [days] days, so picks don't repeat. */
    fun shownUrls(days: Int = 60): Set<String> =
        readableDatabase.rawQuery("SELECT url FROM pick WHERE day>=?", arrayOf(Days.shift(today(), -days))).use { c ->
            buildSet { while (c.moveToNext()) c.getString(0)?.let { add(it) } }
        }

    fun markOpened(id: String) {
        writableDatabase.update("pick", ContentValues().apply { put("opened", 1) }, "id=?", arrayOf(id))
        changed()
    }
}

// ============================================================ screen state

object SenseiBus { val rev = MutableStateFlow(0) }

private val senseiIo = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Runs a Sensei database write off the main thread. */
fun senseiWrite(block: SenseiDb.() -> Unit) { senseiIo.launch { runCatching { GuardApp.sensei.block() } } }

/** Everything the Sensei screen shows apart from habits. */
data class SenseiState(
    val word: String = "",
    val tasks: List<SenseiTask> = emptyList(),
    val picks: List<Pick> = emptyList(),
    val interests: List<Interest> = emptyList(),
    val loaded: Boolean = false,
)

object Sensei {
    /** Swapped for the on-device model in Phase 2C. */
    var brain: SenseiBrain = RuleBrain

    /** Builds yesterday's recap from the habit engine. Runs on a background thread. */
    fun recap(interests: List<Interest>): DayRecap {
        val t = today()
        val y = Days.shift(t, -1)
        val snap = HabitEngine.lastOrLoad()
        val yesterday = runCatching { HabitEngine.dayHabits(y) }.getOrDefault(emptyList())
        val top = snap?.let { s -> s.streaks.entries.maxByOrNull { it.value.current }
            ?.let { e -> (s.today.map { it.habit } + s.notToday).firstOrNull { it.id == e.key }?.name?.let { it to e.value.current } } }
        return DayRecap(
            doneYesterday = yesterday.count { it.done },
            totalYesterday = yesterday.size,
            missedYesterday = yesterday.filter { !it.done && it.habit.kind != HabitKind.AVOID }.map { it.habit.name },
            topStreak = top,
            reelCapBroken = yesterday.any { it.habit.auto == AutoSource.REEL_CAP && !it.done },
            pendingToday = snap?.today?.count { !it.done && !it.failed && !it.pending } ?: 0,
            interest = interests.firstOrNull()?.label,
            day = t,
        )
    }

    fun load(): SenseiState {
        val db = GuardApp.sensei
        val interests = db.interests()
        return SenseiState(
            word = brain.todaysWord(recap(interests)),
            tasks = db.tasks(),
            picks = db.picks(),
            interests = interests,
            loaded = true,
        )
    }
}
