package app.nindo

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

// ===================================================================== model

enum class HabitKind(val label: String) { CHECK("Yes / no"), COUNT("Count"), TIMER("Timer"), CHECKLIST("Checklist"), AVOID("Avoid") }

/** How often a habit repeats. */
enum class Freq { DAILY, DAYS, PER_PERIOD, INTERVAL }
enum class Period(val label: String) { WEEK("week"), MONTH("month") }
/** How a habit reminds you. */
enum class Remind(val label: String) { NONE("Don't remind"), NOTIFY("Notification"), VOICE("Voice"), POPUP("On-screen popup") }

/** Everything about a habit beyond the basics, saved as one JSON column. */
data class HabitCfg(
    val description: String = "",
    val notes: String = "",
    val freq: Freq = Freq.DAILY,
    /** PER_PERIOD: this many days per [period]. */
    val perN: Int = 3,
    val period: Period = Period.WEEK,
    /** INTERVAL: every this many days (2 = alternate days), counted from the start date. */
    val interval: Int = 2,
    /** First and last day, yyyy-MM-dd. Null start = the day it was created; null end = no end. */
    val start: String? = null,
    val end: String? = null,
    val remind: Remind = Remind.NONE,
    /** Minutes after midnight. */
    val remindMin: Int = 9 * 60,
    /** CHECKLIST: the items (max 30). */
    val items: List<String> = emptyList(),
    /** TIMER: remind every this many minutes (0 = the older stopwatch timer). */
    val every: Int = 0,
    /** TIMER: only between these times (minutes after midnight), when [window] is on; otherwise all day. */
    val window: Boolean = true,
    val winStart: Int = 9 * 60,
    val winEnd: Int = 21 * 60,
) {
    fun toJson(): String = org.json.JSONObject().apply {
        put("description", description); put("notes", notes); put("freq", freq.name); put("perN", perN); put("period", period.name)
        put("interval", interval); start?.let { put("start", it) }; end?.let { put("end", it) }
        put("remind", remind.name); put("remindMin", remindMin); put("items", org.json.JSONArray(items))
        put("every", every); put("window", window); put("winStart", winStart); put("winEnd", winEnd)
    }.toString()

    companion object {
        fun parse(s: String?): HabitCfg = runCatching {
            val o = org.json.JSONObject(s ?: return HabitCfg())
            val items = o.optJSONArray("items")
            HabitCfg(
                o.optString("description"), o.optString("notes"),
                runCatching { Freq.valueOf(o.optString("freq")) }.getOrDefault(Freq.DAILY),
                o.optInt("perN", 3).coerceIn(1, 31), runCatching { Period.valueOf(o.optString("period")) }.getOrDefault(Period.WEEK),
                o.optInt("interval", 2).coerceIn(2, 60),
                o.optString("start").takeIf { it.isNotBlank() }, o.optString("end").takeIf { it.isNotBlank() },
                runCatching { Remind.valueOf(o.optString("remind")) }.getOrDefault(Remind.NONE), o.optInt("remindMin", 540),
                if (items == null) emptyList() else (0 until items.length()).map { items.getString(it) },
                o.optInt("every", 0).coerceIn(0, 720), o.optBoolean("window", true),
                o.optInt("winStart", 540).coerceIn(0, 1439), o.optInt("winEnd", 1260).coerceIn(1, 1440),
            )
        }.getOrDefault(HabitCfg())
    }
}

/** Avoid-habits Nindō can judge on its own from the data it already tracks. */
/** Habits Nindō ticks itself. SENSEI_PICK: watching the day's video pick in the Sensei pass. */
enum class AutoSource { NONE, SCREEN_GOAL, REEL_CAP, SENSEI_PICK }

/** What a habit is about. Decides its icon and colour (see HabitsUi). */
enum class HabitCategory(val label: String) {
    HEALTH("Health"), FITNESS("Fitness"), MIND("Mind"), LEARN("Learning"), WORK("Productivity"), SLEEP("Sleep"),
    FOOD("Nutrition"), WATER("Hydration"), DIGITAL("Digital"), MONEY("Finance"), SOCIAL("Social"), QUIT("Quit");

    companion object {
        /** Habits saved by the first 5.0 build stored an emoji here. */
        private val legacy = mapOf("💧" to WATER, "📖" to LEARN, "🏋️" to FITNESS, "🧘" to MIND, "🐍" to LEARN, "💻" to LEARN,
            "🌅" to SLEEP, "🛏️" to SLEEP, "🏃" to FITNESS, "🥗" to FOOD, "🍔" to FOOD, "🚭" to QUIT, "📵" to DIGITAL,
            "📱" to DIGITAL, "🎞️" to DIGITAL, "🙏" to MIND, "🎸" to LEARN, "✍️" to LEARN, "🧹" to WORK, "🎯" to WORK)
        fun parse(s: String?): HabitCategory = entries.firstOrNull { it.name == s } ?: legacy[s] ?: WORK
    }
}

data class Habit(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val category: HabitCategory = HabitCategory.WORK,
    val kind: HabitKind = HabitKind.CHECK,
    /** Count: times a day. Timer: minutes a day. 1 for yes/no and avoid. */
    val target: Int = 1,
    val unit: String = "",
    /** Bit 0 = Monday … bit 6 = Sunday. */
    val days: Int = ALL_DAYS,
    val auto: AutoSource = AutoSource.NONE,
    val created: String = today(),
    val archived: Boolean = false,
    val sort: Int = 0,
    val cfg: HabitCfg = HabitCfg(),
) {
    val startDay: String get() = cfg.start ?: created

    /** Whether the habit can be done on [day]. For "N days per period" habits that's every day in range. */
    fun scheduledOn(day: String): Boolean {
        if (day < startDay) return false
        if (cfg.end != null && day > cfg.end) return false
        return when (cfg.freq) {
            Freq.DAILY, Freq.PER_PERIOD -> true
            Freq.DAYS -> (days and (1 shl Days.weekday(day))) != 0
            Freq.INTERVAL -> Days.between(startDay, day) % cfg.interval == 0
        }
    }
    val isAuto get() = auto != AutoSource.NONE
    /** A repeating timer: a reminder every few minutes within the day's hours, each one done or missed. */
    val isCycle get() = kind == HabitKind.TIMER && cfg.every > 0
    /** Reminder times today for a repeating timer, in minutes after midnight. */
    val cycleTimes: List<Int> get() {
        if (!isCycle) return emptyList()
        val s = if (cfg.window) cfg.winStart else 0
        val e = if (cfg.window) cfg.winEnd else 24 * 60 - 1
        return generateSequence(s + cfg.every) { it + cfg.every }.takeWhile { it <= e }.take(200).toList()
    }
    /** Checklist habits count items; repeating timers count the cycles you need; the rest use the target. */
    val goal: Int get() = when {
        kind == HabitKind.CHECKLIST -> cfg.items.size.coerceAtLeast(1)
        isCycle -> target.coerceIn(1, cycleTimes.size.coerceAtLeast(1))
        else -> target
    }

    companion object {
        const val ALL_DAYS = 0b1111111
    }
}

/** Date helpers on yyyy-MM-dd strings (which also sort correctly as text). */
object Days {
    private fun fmt() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    fun cal(d: String): Calendar = Calendar.getInstance().apply { time = fmt().parse(d)!! }
    fun startMs(d: String): Long = cal(d).timeInMillis
    /** Monday = 0 … Sunday = 6. */
    fun weekday(d: String): Int = (cal(d).get(Calendar.DAY_OF_WEEK) + 5) % 7
    fun shift(d: String, n: Int): String = fmt().format(cal(d).apply { add(Calendar.DAY_OF_YEAR, n) }.time)
    fun range(from: String, to: String, max: Int = 800): List<String> {
        val out = ArrayList<String>()
        var d = from
        while (d <= to && out.size < max) { out.add(d); d = shift(d, 1) }
        return out
    }
    val LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")
    /** Whole days from [a] to [b]. */
    fun between(a: String, b: String): Int = Math.round((startMs(b) - startMs(a)) / 86_400_000.0).toInt()
    /** First and last day of the week (Monday first) or month containing [d]. */
    fun periodOf(d: String, p: Period): Pair<String, String> = when (p) {
        Period.WEEK -> { val s = shift(d, -weekday(d)); s to shift(s, 6) }
        Period.MONTH -> {
            val c = cal(d)
            val first = d.substring(0, 8) + "01"
            first to shift(first, c.getActualMaximum(Calendar.DAY_OF_MONTH) - 1)
        }
    }
}

// ================================================================== storage

/** Habits, their daily values and finished days' XP. Kept apart from the tracker database. */
class HabitDb(ctx: Context) : SQLiteOpenHelper(ctx, "habits.db", null, 2) {
    private val prefs = ctx.getSharedPreferences("habits", Context.MODE_PRIVATE)

    init { setWriteAheadLoggingEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE habits(id TEXT PRIMARY KEY, name TEXT NOT NULL, emoji TEXT, kind TEXT, target INTEGER, unit TEXT, " +
            "days INTEGER, color INTEGER, auto TEXT, created TEXT, archived INTEGER DEFAULT 0, sort INTEGER DEFAULT 0, cfg TEXT)")
        db.execSQL("CREATE TABLE habit_log(day TEXT NOT NULL, habit TEXT NOT NULL, value INTEGER NOT NULL, PRIMARY KEY(day, habit))")
        db.execSQL("CREATE TABLE xp_day(day TEXT PRIMARY KEY, xp INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE habits ADD COLUMN cfg TEXT")
    }

    /** Screens reload when the tracker's write counter moves, so habit writes bump it too. */
    private fun changed() { GuardApp.db.writes.incrementAndGet(); HabitBus.rev.value++ }

    /** First run: Nindō's own goals become habits, dated back a month so existing streaks carry over. */
    fun seedIfNeeded() {
        if (prefs.getBoolean("seeded", false)) return
        val back = Days.shift(today(), -29)
        upsert(Habit(name = "Screen time under goal", category = HabitCategory.DIGITAL, kind = HabitKind.AVOID,
            auto = AutoSource.SCREEN_GOAL, created = back, sort = 0))
        upsert(Habit(name = "Reels under limit", category = HabitCategory.QUIT, kind = HabitKind.AVOID,
            auto = AutoSource.REEL_CAP, created = back, sort = 1))
        prefs.edit().putBoolean("seeded", true).putString("missions_since", today()).apply()
    }

    /** Missions only exist from the day Phase 2 was installed. */
    val missionsSince: String get() = prefs.getString("missions_since", null) ?: today()

    fun habits(includeArchived: Boolean = false): List<Habit> =
        readableDatabase.rawQuery("SELECT id,name,emoji,kind,target,unit,days,color,auto,created,archived,sort,cfg FROM habits" +
            (if (includeArchived) "" else " WHERE archived=0") + " ORDER BY sort, created", null).use { c ->
            buildList<Habit> {
                while (c.moveToNext()) add(Habit(
                    id = c.getString(0), name = c.getString(1), category = HabitCategory.parse(c.getString(2)),
                    kind = runCatching { HabitKind.valueOf(c.getString(3)) }.getOrDefault(HabitKind.CHECK)
                        // "Avoid" is now only used by Nindō's own tracked habits; yours are yes / no.
                        .let { k -> if (k == HabitKind.AVOID && (c.getString(8) ?: "NONE") == "NONE") HabitKind.CHECK else k },
                    target = c.getInt(4).coerceAtLeast(1), unit = c.getString(5) ?: "", days = c.getInt(6),
                    auto = runCatching { AutoSource.valueOf(c.getString(8)) }.getOrDefault(AutoSource.NONE),
                    created = c.getString(9) ?: today(), archived = c.getInt(10) == 1, sort = c.getInt(11),
                    cfg = HabitCfg.parse(c.getString(12)),
                ))
            }
        }

    fun upsert(h: Habit) {
        val sort = if (h.sort == 0 && habits(true).none { it.id == h.id }) (habits(true).maxOfOrNull { it.sort } ?: 0) + 1 else h.sort
        writableDatabase.insertWithOnConflict("habits", null, ContentValues().apply {
            // The "emoji" column holds the category.
            put("id", h.id); put("name", h.name); put("emoji", h.category.name); put("kind", h.kind.name); put("target", h.target)
            put("unit", h.unit); put("days", h.days); put("color", 0); put("auto", h.auto.name); put("created", h.created)
            put("archived", if (h.archived) 1 else 0); put("sort", sort); put("cfg", h.cfg.toJson())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        changed()
        runCatching { Reminders.scheduleAll(GuardApp.app) }
    }

    fun habit(id: String): Habit? = habits(true).firstOrNull { it.id == id }

    /** Removes the habit and its history. XP already earned on finished days stays. */
    fun delete(id: String) {
        writableDatabase.delete("habits", "id=?", arrayOf(id))
        writableDatabase.delete("habit_log", "habit=?", arrayOf(id))
        changed()
        runCatching { Reminders.cancel(GuardApp.app, id) }
    }

    /** Marks a habit fully done today: the target for counts and timers, every item for checklists. */
    fun complete(habit: Habit) {
        if (habit.isCycle) { answerCycle(habit.id, true); return }
        val v = when (habit.kind) {
            HabitKind.CHECKLIST -> (1 shl habit.goal) - 1
            HabitKind.CHECK -> 1
            else -> maxOf(habit.target, value(today(), habit.id))
        }
        if (habit.kind != HabitKind.AVOID) setValue(today(), habit.id, v)
    }

    /** Repeating timers keep the day's missed cycles beside the done count, under "<id>#miss". */
    fun addMiss(day: String, habit: String) = add(day, "$habit#miss", 1)

    /** Repeating timer: (done, missed) cycles over the range. */
    fun cycleTotals(habit: String, from: String, to: String): Pair<Int, Int> {
        val l = logs(from, to).values
        return l.sumOf { it[habit] ?: 0 } to l.sumOf { it["$habit#miss"] ?: 0 }
    }

    // The cycle reminder that's waiting for an answer: "day|minute".
    fun pendingCycle(habit: String): Pair<String, Int>? = prefs.getString("cycle_$habit", null)?.split("|")
        ?.takeIf { it.size == 2 }?.let { it[0] to (it[1].toIntOrNull() ?: 0) }
    fun setPendingCycle(habit: String, day: String?, min: Int = 0) {
        prefs.edit().apply { if (day == null) remove("cycle_$habit") else putString("cycle_$habit", "$day|$min") }.apply()
    }

    /** Answers the waiting cycle reminder, if any: done adds one done, otherwise one missed. */
    fun answerCycle(habit: String, done: Boolean) {
        val p = pendingCycle(habit)
        setPendingCycle(habit, null)
        // A reminder left unanswered from an earlier day was missed.
        if (p != null && p.first != today()) addMiss(p.first, habit)
        if (done) add(today(), habit, 1) else addMiss(today(), habit)
    }

    /** Checklist: ticks or unticks item [i] today (the day's value is a bitmask). */
    fun toggleItem(habit: String, i: Int) = setValue(today(), habit, value(today(), habit) xor (1 shl i))

    fun value(day: String, habit: String): Int =
        readableDatabase.rawQuery("SELECT value FROM habit_log WHERE day=? AND habit=?", arrayOf(day, habit)).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun setValue(day: String, habit: String, v: Int) {
        if (v <= 0) writableDatabase.delete("habit_log", "day=? AND habit=?", arrayOf(day, habit))
        else writableDatabase.insertWithOnConflict("habit_log", null, ContentValues().apply {
            put("day", day); put("habit", habit); put("value", v)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        changed()
    }

    fun add(day: String, habit: String, delta: Int) = setValue(day, habit, (value(day, habit) + delta).coerceAtLeast(0))

    // Live timers for timer habits: the start time is kept, so a timer keeps running while the app is closed.
    fun timerStart(habit: String): Long = prefs.getLong("timer_$habit", 0L)
    fun startTimer(habit: String) { prefs.edit().putLong("timer_$habit", System.currentTimeMillis()).apply(); changed() }
    /** Stops the timer and adds the minutes it ran (at least 1). */
    fun stopTimer(habit: String) {
        val start = timerStart(habit)
        prefs.edit().remove("timer_$habit").apply()
        if (start > 0) {
            val min = ((System.currentTimeMillis() - start + 30_000) / 60_000).toInt().coerceAtLeast(1)
            add(today(), habit, min)
        } else changed()
    }

    /** day → habit id → value. */
    fun logs(from: String, to: String): Map<String, Map<String, Int>> {
        val out = HashMap<String, HashMap<String, Int>>()
        readableDatabase.rawQuery("SELECT day, habit, value FROM habit_log WHERE day>=? AND day<=?", arrayOf(from, to)).use { c ->
            while (c.moveToNext()) out.getOrPut(c.getString(0)) { HashMap() }[c.getString(1)] = c.getInt(2)
        }
        return out
    }

    fun xpBanked(): Int = readableDatabase.rawQuery("SELECT SUM(xp) FROM xp_day", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    fun lastBankedDay(): String? = readableDatabase.rawQuery("SELECT MAX(day) FROM xp_day", null).use { if (it.moveToFirst()) it.getString(0) else null }
    fun bank(days: Map<String, Int>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            days.forEach { (d, xp) -> db.insertWithOnConflict("xp_day", null, ContentValues().apply { put("day", d); put("xp", xp) }, SQLiteDatabase.CONFLICT_REPLACE) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun xpByDay(from: String, to: String): Map<String, Int> =
        readableDatabase.rawQuery("SELECT day, xp FROM xp_day WHERE day>=? AND day<=?", arrayOf(from, to)).use { c ->
            buildMap<String, Int> { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) }
        }
}

// ================================================================ XP & ranks

object Xp {
    const val HABIT = 10
    const val AUTO = 15
    const val PERFECT = 25
    const val MISSION = 20
}

object Ranks {
    data class Rank(val index: Int, val name: String, val minXp: Int)
    val all = listOf(
        Rank(0, "Academy", 0), Rank(1, "Genin", 300), Rank(2, "Chūnin", 1200),
        Rank(3, "Jōnin", 3500), Rank(4, "Kage", 8000),
    )
    fun of(xp: Int): Rank = all.last { xp >= it.minXp }
    fun next(xp: Int): Rank? = all.firstOrNull { it.minXp > xp }
    fun level(xp: Int): Int = xp / 100 + 1
    /** 0..1 progress towards the next rank. */
    fun progress(xp: Int): Float {
        val cur = of(xp); val nxt = next(xp) ?: return 1f
        return (xp - cur.minXp).toFloat() / (nxt.minXp - cur.minXp)
    }
}

// ================================================================== engine

enum class Mark { DONE, MISSED, PENDING, OFF }

/** One habit on one day. [pending]: judged when the day ends (avoid habits), still on track. */
data class HabitDay(
    val habit: Habit, val value: Int, val done: Boolean, val pending: Boolean, val failed: Boolean, val progress: Float,
    /** Auto habits: what was used so far and the limit (ms for screen time, reels for the reel cap). */
    val used: Long = 0, val limit: Long = 0,
    /** Timer habits: when the running timer started, or 0. */
    val timerStart: Long = 0,
    /** Repeating timers: missed cycles, cycles in the day, and the next reminder (minutes after midnight, -1 = none left). */
    val missed: Int = 0, val cycles: Int = 0, val nextMin: Int = -1,
) {
    val mark get() = when { done -> Mark.DONE; pending -> Mark.PENDING; else -> Mark.MISSED }
}

data class HabitStreak(val current: Int = 0, val best: Int = 0, val freezes: Int = 0)

enum class MissionType { HABITS, PERFECT, SCREEN, REELS, FOCUS, SENSEI }

data class Mission(val type: MissionType, val title: String, val progress: Float, val done: Boolean, val pending: Boolean, val failed: Boolean)

data class HabitSnapshot(
    val today: List<HabitDay> = emptyList(),
    val notToday: List<Habit> = emptyList(),
    val streaks: Map<String, HabitStreak> = emptyMap(),
    /** Last 7 days, oldest first, per habit id. */
    val week: Map<String, List<Mark>> = emptyMap(),
    val missions: List<Mission> = emptyList(),
    val xp: Int = 0,
    val xpToday: Int = 0,
    val xpPendingToday: Int = 0,
    /** Last 7 days' XP, oldest first (today included). */
    val xpWeek: List<Int> = emptyList(),
    val weekRate: Float = 0f,
) {
    val doneToday get() = today.count { it.done }
    val countToday get() = today.size
}

object HabitEngine {
    private class Ctx(
        val goalMs: Long, val reelCap: Int,
        val stats: Map<String, DayStat>,
        val logs: Map<String, Map<String, Int>>,
        /** Days with a Sensei video pick, and days one was watched. */
        val pickDays: Set<String> = emptySet(),
        val watchedDays: Set<String> = emptySet(),
    )

    /** A manual habit's own result for one day, ignoring the schedule. */
    private fun doneOn(h: Habit, day: String, c: Ctx): Boolean {
        val v = c.logs[day]?.get(h.id) ?: 0
        return when (h.kind) {
            HabitKind.CHECK -> v >= 1
            HabitKind.CHECKLIST -> Integer.bitCount(v) >= h.goal
            else -> v >= h.target
        }
    }

    private fun judge(h: Habit, day: String, isToday: Boolean, c: Ctx): HabitDay? {
        if (day < minOf(h.created, h.startDay) || !h.scheduledOn(day)) return null
        val v = c.logs[day]?.get(h.id) ?: 0
        val st = c.stats[day]
        // "N days per week/month": a day you skip only counts as missed on the period's last day, if the quota wasn't met.
        if (h.cfg.freq == Freq.PER_PERIOD && !h.isAuto && h.kind != HabitKind.AVOID && !doneOn(h, day, c)) {
            val (ps, pe) = Days.periodOf(day, h.cfg.period)
            val doneDays = Days.range(maxOf(ps, h.startDay), minOf(pe, today())).count { it != day && doneOn(h, it, c) }
            if (doneDays >= h.cfg.perN) return null
            if (!isToday && day != pe) return null
        }
        return when {
            h.auto == AutoSource.SCREEN_GOAL -> {
                val ms = st?.ms ?: 0L
                val failed = ms > c.goalMs
                HabitDay(h, 0, done = !isToday && ms in 1..c.goalMs, pending = isToday && !failed, failed = failed,
                    progress = (ms.toFloat() / c.goalMs.coerceAtLeast(1)).coerceIn(0f, 1f), used = ms, limit = c.goalMs)
            }
            h.auto == AutoSource.REEL_CAP -> {
                val reels = st?.reels ?: 0
                val tracked = (st?.ms ?: 0L) > 0
                val failed = reels > c.reelCap
                HabitDay(h, reels, done = !isToday && tracked && !failed, pending = isToday && !failed, failed = failed,
                    progress = (reels.toFloat() / c.reelCap.coerceAtLeast(1)).coerceIn(0f, 1f), used = reels.toLong(), limit = c.reelCap.toLong())
            }
            h.kind == HabitKind.AVOID -> {
                val slipped = v >= 1
                HabitDay(h, v, done = !isToday && !slipped, pending = isToday && !slipped, failed = slipped, progress = if (slipped) 1f else 0f)
            }
            h.kind == HabitKind.CHECK -> HabitDay(h, v, done = v >= 1, pending = false, failed = false, progress = if (v >= 1) 1f else 0f)
            h.isCycle -> {
                val times = h.cycleTimes
                val miss = c.logs[day]?.get("${h.id}#miss") ?: 0
                val now = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
                HabitDay(h, v, done = v >= h.goal, pending = false, failed = false, progress = (v.toFloat() / h.goal).coerceIn(0f, 1f),
                    missed = miss, cycles = times.size, nextMin = if (isToday) times.firstOrNull { it > now } ?: -1 else -1)
            }
            h.kind == HabitKind.CHECKLIST -> {
                val n = Integer.bitCount(v)
                HabitDay(h, n, done = n >= h.goal, pending = false, failed = false, progress = (n.toFloat() / h.goal).coerceIn(0f, 1f))
            }
            else -> HabitDay(h, v, done = v >= h.target, pending = false, failed = false, progress = (v.toFloat() / h.target).coerceIn(0f, 1f),
                timerStart = if (isToday && h.kind == HabitKind.TIMER) GuardApp.habits.timerStart(h.id) else 0L)
        }
    }

    private fun missionsFor(day: String, isToday: Boolean, judged: List<HabitDay>, c: Ctx, focusMs: Long): List<Mission> {
        val pool = mutableListOf(MissionType.PERFECT, MissionType.SCREEN, MissionType.REELS, MissionType.FOCUS)
        if (judged.isEmpty()) pool.remove(MissionType.PERFECT)
        pool.shuffle(java.util.Random(day.hashCode().toLong()))
        // A day with a Sensei video pick always has "Watch your Sensei pick" as a mission.
        val picked = (if (judged.isNotEmpty()) listOf(MissionType.HABITS) else emptyList()) +
            (if (day in c.pickDays) listOf(MissionType.SENSEI) else emptyList()) + pool
        val st = c.stats[day]
        val ms = st?.ms ?: 0L
        val reels = st?.reels ?: 0
        val done = judged.count { it.done }
        return picked.take(3).map { t ->
            when (t) {
                MissionType.HABITS -> {
                    val n = minOf(3, judged.size)
                    Mission(t, "Complete $n habit${if (n > 1) "s" else ""}", (done.toFloat() / n).coerceAtMost(1f), done >= n, false, false)
                }
                MissionType.PERFECT -> {
                    val failed = judged.any { it.failed }
                    val all = judged.all { it.done || it.pending }
                    Mission(t, "Perfect day", done.toFloat() / judged.size,
                        done = !isToday && judged.all { it.done }, pending = isToday && all && !failed, failed = failed)
                }
                MissionType.SCREEN -> {
                    val failed = ms > c.goalMs
                    Mission(t, "Under ${fmtMinutes((c.goalMs / 60_000).toInt())} screen time",
                        (ms.toFloat() / c.goalMs.coerceAtLeast(1)).coerceIn(0f, 1f),
                        done = !isToday && ms in 1..c.goalMs, pending = isToday && !failed, failed = failed)
                }
                MissionType.REELS -> {
                    val cap = (c.reelCap / 2).coerceAtLeast(1)
                    val failed = reels > cap
                    Mission(t, "$cap reels or fewer", (reels.toFloat() / cap).coerceIn(0f, 1f),
                        done = !isToday && ms > 0 && !failed, pending = isToday && !failed, failed = failed)
                }
                MissionType.FOCUS -> Mission(t, "20-min focus session", (focusMs / 1_200_000f).coerceIn(0f, 1f),
                    done = focusMs >= 20 * 60_000L, pending = false, failed = false)
                MissionType.SENSEI -> {
                    val w = day in c.watchedDays
                    Mission(t, "Watch your Sensei pick", if (w) 1f else 0f, done = w, pending = false, failed = false)
                }
            }
        }
    }

    /** XP a day earns: [final] counts only what's settled; otherwise also what would settle at midnight. */
    private fun dayXp(judged: List<HabitDay>, missions: List<Mission>, settledOnly: Boolean): Int {
        var xp = 0
        judged.forEach { if (it.done || (!settledOnly && it.pending)) xp += if (it.habit.isAuto) Xp.AUTO else Xp.HABIT }
        val perfect = judged.isNotEmpty() && judged.all { it.done || (!settledOnly && it.pending) }
        if (perfect) xp += Xp.PERFECT
        missions.forEach { if (it.done || (!settledOnly && it.pending)) xp += Xp.MISSION }
        return xp
    }

    private fun focusOn(day: String): Long {
        val start = Days.startMs(day)
        return GuardApp.db.focusMs(start, Days.startMs(Days.shift(day, 1)))
    }

    private fun ctx(from: String, to: String = today()): Ctx {
        val store = GuardApp.store
        val (picks, watched) = runCatching { GuardApp.sensei.videoDays(from, to) }.getOrDefault(emptySet<String>() to emptySet())
        return Ctx(store.screenGoalMin * 60_000L, store.reelCap.coerceAtLeast(1), GuardApp.db.dayStats(from, to), GuardApp.habits.logs(from, to),
            picks, watched)
    }

    /** The last snapshot loaded, for places outside the UI (pause screen, block popup, reminders). */
    @Volatile var last: HabitSnapshot? = null

    fun lastOrLoad(): HabitSnapshot? = last ?: runCatching { load() }.getOrNull()

    /** Cached snapshot without blocking the caller; starts a load in the background if there's none yet. */
    fun peek(): HabitSnapshot? = last ?: run { Thread { runCatching { load() } }.start(); null }

    /** A random unfinished habit for today, for the pause screen and the block popup. */
    fun pendingPick(): HabitDay? = peek()?.today?.filter { !it.done && !it.failed && !it.pending && it.habit.kind != HabitKind.AVOID }?.randomOrNull()

    /** Per day in the range: (habits done, habits scheduled). Today counts only what's done so far. */
    fun history(from: String, to: String): Map<String, Pair<Int, Int>> {
        val habits = GuardApp.habits.habits()
        val c = ctx(from, to)
        val t = today()
        return Days.range(from, minOf(to, t)).associateWith { d ->
            val j = habits.mapNotNull { judge(it, d, d == t, c) }
            j.count { it.done } to j.size
        }
    }

    /** Every habit's result on one day. */
    fun dayHabits(day: String): List<HabitDay> {
        val c = ctx(day, day)
        return GuardApp.habits.habits().mapNotNull { judge(it, day, day == today(), c) }
    }

    /** One habit's marks for each day in the range (missing = not scheduled). */
    fun marks(h: Habit, from: String, to: String): Map<String, Mark> {
        val c = ctx(from, to)
        val t = today()
        return Days.range(from, minOf(to, t)).mapNotNull { d -> judge(h, d, d == t, c)?.let { d to it.mark } }.toMap()
    }

    /** Last [days] days per habit: (habit, done, scheduled). */
    fun rates(days: Int = 30): List<Triple<Habit, Int, Int>> {
        val t = today()
        val from = Days.shift(t, -(days - 1))
        val c = ctx(from, t)
        return GuardApp.habits.habits().map { h ->
            // Today only counts once it's done, so an unfinished morning doesn't pull the rate down.
            val j = Days.range(from, t).mapNotNull { d -> judge(h, d, d == t, c)?.takeIf { d != t || it.done } }
            Triple(h, j.count { it.done }, j.size)
        }
    }

    fun load(): HabitSnapshot {
        val hdb = GuardApp.habits
        val habits = hdb.habits()
        val t = today()
        val from = Days.shift(t, -180)
        val c = ctx(from, t)
        val missionsSince = hdb.missionsSince

        // Bank every finished day not banked yet (from the earliest habit or mission day).
        val earliest = (habits.map { it.created } + missionsSince).minOrNull() ?: t
        val startBank = hdb.lastBankedDay()?.let { Days.shift(it, 1) } ?: earliest
        val yesterday = Days.shift(t, -1)
        if (startBank <= yesterday) {
            val banked = HashMap<String, Int>()
            Days.range(maxOf(startBank, from), yesterday).forEach { d ->
                val j = habits.mapNotNull { judge(it, d, false, c) }
                val m = if (d >= missionsSince) missionsFor(d, false, j, c, focusOn(d)) else emptyList()
                banked[d] = dayXp(j, m, settledOnly = true)
            }
            hdb.bank(banked)
        }

        val judgedToday = habits.mapNotNull { judge(it, t, true, c) }
        val missions = missionsFor(t, true, judgedToday, c, focusOn(t))
        val xpToday = dayXp(judgedToday, missions, settledOnly = true)
        val xpPending = dayXp(judgedToday, missions, settledOnly = false) - xpToday

        // Streaks: scheduled days in a row, with one freeze earned per 7-day run (max 2).
        val streaks = habits.associate { h ->
            var cur = 0; var best = 0; var freezes = 0; var run7 = 0
            for (d in Days.range(maxOf(minOf(h.created, h.startDay), from), t)) {
                val j = judge(h, d, d == t, c) ?: continue
                when {
                    j.done -> { cur++; run7++; best = maxOf(best, cur); if (run7 == 7) { run7 = 0; freezes = minOf(2, freezes + 1) } }
                    d == t -> {} // today isn't over: it can't break the streak yet
                    freezes > 0 -> { freezes--; run7 = 0 }
                    else -> { cur = 0; run7 = 0 }
                }
            }
            h.id to HabitStreak(cur, best, freezes)
        }

        val last7 = Days.range(Days.shift(t, -6), t)
        val week = habits.associate { h -> h.id to last7.map { d -> judge(h, d, d == t, c)?.mark ?: Mark.OFF } }
        val marks = week.values.flatten().filter { it == Mark.DONE || it == Mark.MISSED }
        val rate = if (marks.isEmpty()) 0f else marks.count { it == Mark.DONE }.toFloat() / marks.size
        val xpDays = hdb.xpByDay(last7.first(), yesterday)

        return HabitSnapshot(
            today = judgedToday,
            notToday = habits.filter { h -> judgedToday.none { it.habit.id == h.id } },
            streaks = streaks,
            week = week,
            missions = missions,
            xp = hdb.xpBanked() + xpToday,
            xpToday = xpToday,
            xpPendingToday = xpPending,
            xpWeek = last7.map { if (it == t) xpToday else xpDays[it] ?: 0 },
            weekRate = rate,
        ).also { last = it }
    }
}
