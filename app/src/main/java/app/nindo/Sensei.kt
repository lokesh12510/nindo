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

/** Where a Sensei item came from: added by hand, found in a habit or task name, or (Phase 2C) heard in a call. */
enum class SenseiSource { MANUAL, HABIT, TASK, CALL }

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
    /** Watched in the Sensei pass (70% or to the end). */
    val watched: Boolean = false,
)

// ================================================================== storage

/** Sensei's own database: interests, tasks and daily picks. Kept apart from habits and the tracker. */
class SenseiDb(ctx: Context) : SQLiteOpenHelper(ctx, "sensei.db", null, 3) {
    private val prefs = ctx.getSharedPreferences("sensei", Context.MODE_PRIVATE)

    /** Topics you told Sensei to forget. Ones found in habits and tasks never come back on their own. */
    fun forgotten(): Set<String> = prefs.getStringSet("forgotten", emptySet()) ?: emptySet()

    init { setWriteAheadLoggingEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE interest(id TEXT PRIMARY KEY, label TEXT NOT NULL, keywords TEXT, channels TEXT, source TEXT, created TEXT)")
        db.execSQL("CREATE TABLE task(id TEXT PRIMARY KEY, title TEXT NOT NULL, due TEXT, done INTEGER DEFAULT 0, done_day TEXT, source TEXT, created TEXT)")
        db.execSQL("CREATE TABLE pick(id TEXT PRIMARY KEY, day TEXT NOT NULL, kind TEXT NOT NULL, interest TEXT, title TEXT, url TEXT, " +
            "video_id TEXT, reason TEXT, opened INTEGER DEFAULT 0)")
        db.execSQL("CREATE INDEX pick_day ON pick(day)")
        createMemory(db)
        createCalls(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createMemory(db)
        if (oldVersion < 3) createCalls(db)
    }

    /** Version 3: calls Sensei makes to you (reminders and snoozes; the daily call is a setting). */
    private fun createCalls(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS scheduled_call(id TEXT PRIMARY KEY, at INTEGER NOT NULL, kind TEXT, title TEXT, state TEXT)")
    }

    /** Version 2: call notes and remembered facts. */
    private fun createMemory(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS call_log(id TEXT PRIMARY KEY, started INTEGER, ended INTEGER, summary TEXT, transcript TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS fact(id TEXT PRIMARY KEY, text TEXT NOT NULL, created TEXT, call_id TEXT)")
    }

    private fun changed() { GuardApp.db.writes.incrementAndGet(); SenseiBus.rev.value++; HabitBus.rev.value++ }

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
        if (i.source == SenseiSource.MANUAL && i.label.lowercase() in forgotten())
            prefs.edit().putStringSet("forgotten", forgotten() - i.label.lowercase()).apply()
        writableDatabase.insertWithOnConflict("interest", null, ContentValues().apply {
            put("id", i.id); put("label", i.label.trim()); put("keywords", i.keywords.pack()); put("channels", i.channels.pack())
            put("source", i.source.name); put("created", i.created)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        changed()
    }

    /** Forgets an interest and any of today's picks made for it. */
    fun deleteInterest(id: String) {
        interests().firstOrNull { it.id == id }?.let { i ->
            prefs.edit().putStringSet("forgotten", forgotten() + i.label.lowercase()).apply()
        }
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
                c.getString(4) ?: "", c.getString(5) ?: "", c.getString(6), c.getString(7) ?: "", c.getInt(8) >= 1, c.getInt(8) >= 2)) }
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
                    put("url", p.url); put("video_id", p.videoId); put("reason", p.reason); put("opened", if (p.watched) 2 else if (p.opened) 1 else 0)
                })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        changed()
    }

    /** Every link shown on the [days] days before today, so picks don't repeat. Today's own picks may be picked again. */
    fun shownUrls(days: Int = 60): Set<String> =
        readableDatabase.rawQuery("SELECT url FROM pick WHERE day>=? AND day<?", arrayOf(Days.shift(today(), -days), today())).use { c ->
            buildSet { while (c.moveToNext()) c.getString(0)?.let { add(it) } }
        }

    // ------------------------------------------------------------ memory

    fun saveCall(c: CallLog) {
        writableDatabase.insertWithOnConflict("call_log", null, ContentValues().apply {
            put("id", c.id); put("started", c.started); put("ended", c.ended); put("summary", c.summary); put("transcript", c.transcript)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        changed()
    }

    fun calls(limit: Int = 30): List<CallLog> =
        readableDatabase.rawQuery("SELECT id,started,ended,summary,transcript FROM call_log ORDER BY started DESC LIMIT $limit", null).use { c ->
            buildList { while (c.moveToNext()) add(CallLog(c.getString(0), c.getLong(1), c.getLong(2), c.getString(3) ?: "", c.getString(4) ?: "")) }
        }

    fun deleteCall(id: String) {
        writableDatabase.delete("call_log", "id=?", arrayOf(id))
        writableDatabase.delete("fact", "call_id=?", arrayOf(id))
        changed()
    }

    fun facts(): List<Fact> =
        readableDatabase.rawQuery("SELECT id,text,created,call_id FROM fact ORDER BY created DESC, rowid DESC", null).use { c ->
            buildList { while (c.moveToNext()) add(Fact(c.getString(0), c.getString(1), c.getString(2) ?: "", c.getString(3))) }
        }

    /** Adds a fact unless an equal one is already there. Returns true when added. */
    fun addFact(text: String, callId: String?): Boolean {
        if (SenseiMemory.isKnown(text, facts().map { it.text })) return false
        writableDatabase.insert("fact", null, ContentValues().apply {
            put("id", UUID.randomUUID().toString()); put("text", text.trim()); put("created", today()); put("call_id", callId)
        })
        changed()
        return true
    }

    fun deleteFact(id: String) { writableDatabase.delete("fact", "id=?", arrayOf(id)); changed() }

    // ------------------------------------------------------ calls to you

    fun addScheduled(c: ScheduledCall) {
        writableDatabase.insertWithOnConflict("scheduled_call", null, ContentValues().apply {
            put("id", c.id); put("at", c.at); put("kind", c.kind.name); put("title", c.title); put("state", c.state.name)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        changed()
    }

    /** Calls still to come or ringing, soonest first. */
    fun scheduled(): List<ScheduledCall> =
        readableDatabase.rawQuery("SELECT id,at,kind,title,state FROM scheduled_call WHERE state IN ('PENDING','RINGING') ORDER BY at", null).use { c ->
            buildList { while (c.moveToNext()) add(ScheduledCall(c.getString(0), c.getLong(1),
                runCatching { ScheduledCall.Kind.valueOf(c.getString(2)) }.getOrDefault(ScheduledCall.Kind.REMINDER), c.getString(3) ?: "",
                runCatching { ScheduledCall.State.valueOf(c.getString(4)) }.getOrDefault(ScheduledCall.State.PENDING))) }
        }

    fun scheduledCall(id: String): ScheduledCall? =
        readableDatabase.rawQuery("SELECT id,at,kind,title,state FROM scheduled_call WHERE id=?", arrayOf(id)).use { c ->
            if (!c.moveToFirst()) null else ScheduledCall(c.getString(0), c.getLong(1),
                runCatching { ScheduledCall.Kind.valueOf(c.getString(2)) }.getOrDefault(ScheduledCall.Kind.REMINDER), c.getString(3) ?: "",
                runCatching { ScheduledCall.State.valueOf(c.getString(4)) }.getOrDefault(ScheduledCall.State.PENDING))
        }

    fun setScheduledState(id: String, s: ScheduledCall.State) {
        writableDatabase.update("scheduled_call", ContentValues().apply { put("state", s.name) }, "id=?", arrayOf(id))
        changed()
    }

    fun markOpened(id: String) {
        writableDatabase.execSQL("UPDATE pick SET opened=1 WHERE id=? AND opened<1", arrayOf(id))
        changed()
    }

    /** Watched in the Sensei pass: ticks the "Watch my Sensei pick" habit for today. */
    fun markWatched(id: String) {
        writableDatabase.execSQL("UPDATE pick SET opened=2 WHERE id=?", arrayOf(id))
        changed()
        runCatching {
            GuardApp.habits.habits().filter { it.auto == AutoSource.SENSEI_PICK }.forEach { GuardApp.habits.setValue(today(), it.id, 1) }
        }
    }

    /** Days with a video pick, and days one was watched (for the daily mission). */
    fun videoDays(from: String, to: String): Pair<Set<String>, Set<String>> =
        readableDatabase.rawQuery("SELECT day, MAX(opened) FROM pick WHERE kind='VIDEO' AND day>=? AND day<=? GROUP BY day", arrayOf(from, to)).use { c ->
            val all = HashSet<String>(); val watched = HashSet<String>()
            while (c.moveToNext()) { all += c.getString(0); if (c.getInt(1) >= 2) watched += c.getString(0) }
            all to watched
        }
}

/** A call Sensei will make to you. The daily check-in isn't stored here; it's a time setting. */
data class ScheduledCall(
    val id: String = UUID.randomUUID().toString(),
    val at: Long,
    val kind: Kind,
    /** What it's about, e.g. "Call mom". */
    val title: String,
    val state: State = State.PENDING,
) {
    enum class Kind { DAILY, REMINDER, SNOOZE, TEST }
    enum class State { PENDING, RINGING, ANSWERED, DECLINED, MISSED, CANCELLED }
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
    /** Interests Sensei can't find videos for: no channel of their own and no starter channels. */
    val noVideos: List<Interest> = emptyList(),
    /** What the last feed check read. */
    val check: Feeds.Report? = null,
    /** What Sensei remembers from calls. */
    val facts: List<Fact> = emptyList(),
    val lastCall: CallLog? = null,
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

    /**
     * Adds topics found in habit and task names as interests (once; forgotten ones stay forgotten).
     * Returns true when something new was added.
     */
    fun syncFromHabitsAndTasks(db: SenseiDb = GuardApp.sensei): Boolean {
        val have = db.interests().map { it.label.lowercase() }.toMutableSet()
        val forgotten = db.forgotten()
        val found = LinkedHashMap<String, SenseiSource>()
        GuardApp.habits.habits().filter { !it.archived && !it.isAuto }.forEach { h ->
            InterestMiner.fromHabit(h.name, h.category == HabitCategory.LEARN)?.let { found.putIfAbsent(it, SenseiSource.HABIT) }
        }
        db.tasks().filter { !it.done }.forEach { t -> InterestMiner.fromTask(t.title)?.let { found.putIfAbsent(it, SenseiSource.TASK) } }
        var added = false
        for ((label, src) in found) {
            val key = label.lowercase()
            if (key in have || key in forgotten) continue
            db.upsertInterest(Interest(label = label, source = src)); have += key; added = true
        }
        return added
    }

    fun load(): SenseiState {
        val db = GuardApp.sensei
        if (runCatching { syncFromHabitsAndTasks(db) }.getOrDefault(false))
            Thread { Feeds.afterInterestsChanged(GuardApp.app) }.start()
        val interests = db.interests()
        return SenseiState(
            word = brain.todaysWord(recap(interests)),
            tasks = db.tasks(),
            picks = db.picks(),
            interests = interests,
            check = Feeds.report(GuardApp.app),
            facts = db.facts(),
            lastCall = db.calls(1).firstOrNull(),
            noVideos = interests.filter { it.channels.isEmpty() && StarterChannels.forLabel(it.label).isEmpty() },
            loaded = true,
        )
    }
}
