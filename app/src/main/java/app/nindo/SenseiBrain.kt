package app.nindo

/*
 * Sensei's "brain": everything that would later come from the on-device model.
 * Phase 2B uses RuleBrain (rules and templates); Phase 2C adds a Gemma-backed brain behind the
 * same interface. No Android imports here, so it can be unit-tested on the JVM.
 */

/** What happened yesterday and what's waiting today, for Today's word. */
data class DayRecap(
    /** Habits done / scheduled yesterday (0 / 0 when nothing was scheduled). */
    val doneYesterday: Int = 0,
    val totalYesterday: Int = 0,
    /** Names of habits missed yesterday. */
    val missedYesterday: List<String> = emptyList(),
    /** The longest running streak right now, as (habit name, days). */
    val topStreak: Pair<String, Int>? = null,
    /** True when yesterday's reel cap was broken. */
    val reelCapBroken: Boolean = false,
    /** Habits still open today. */
    val pendingToday: Int = 0,
    /** The newest interest the user follows, e.g. "Python". */
    val interest: String? = null,
    /** yyyy-MM-dd, used to vary the wording from day to day. */
    val day: String = "",
)

/** A news item or video found in the feeds, before Sensei picks from them. */
data class Candidate(
    val kind: PickKind,
    val interestId: String,
    val title: String,
    val url: String,
    val videoId: String? = null,
    val publishedMs: Long = 0,
)

enum class PickKind { NEWS, VIDEO }

interface SenseiBrain {
    /** One line of motivation for today. */
    fun todaysWord(r: DayRecap): String

    /** Why a pick is shown, in one short line. */
    fun reasonFor(interest: Interest, c: Candidate): String

    /** Today's picks: at most [maxNews] news items and [maxVideos] videos, best first. */
    fun rankPicks(candidates: List<Candidate>, interests: List<Interest>, alreadyShown: Set<String>, nowMs: Long,
                  maxNews: Int = 1, maxVideos: Int = 2): List<Candidate>
}

object RuleBrain : SenseiBrain {

    override fun todaysWord(r: DayRecap): String {
        val v = (r.day.hashCode() and 0x7fffffff)
        fun pick(vararg s: String) = s[v % s.size]
        val perfect = r.totalYesterday > 0 && r.doneYesterday == r.totalYesterday
        val streak = r.topStreak?.takeIf { it.second >= 3 }
        return when {
            r.reelCapBroken -> pick(
                "Yesterday the reels won. Today you decide first, then you scroll.",
                "The reel cap broke yesterday. One clean day puts you back on your path.",
            )
            perfect && streak != null -> pick(
                "Every habit done yesterday, and ${streak.first} is at ${streak.second} days. Don't break the chain now.",
                "${streak.second} days of ${streak.first}. A ninja doesn't stop when it's going well.",
            )
            perfect -> pick(
                "You finished everything yesterday. Do it again and it becomes who you are.",
                "A perfect day yesterday. Show up the same way today.",
            )
            r.missedYesterday.isNotEmpty() -> {
                val m = r.missedYesterday.first()
                pick(
                    "$m slipped yesterday. Start today with it, before the day gets loud.",
                    "Missing once is a mistake. Missing twice is a habit. Do $m today.",
                )
            }
            streak != null -> pick(
                "${streak.first}: ${streak.second} days in a row. Keep the streak alive today.",
                "${streak.second} days strong on ${streak.first}. One more today.",
            )
            r.interest != null -> pick(
                "You said you want to get better at ${r.interest}. Ten focused minutes today counts.",
                "Small steps on ${r.interest} every day beat one big plan you never start.",
            )
            r.pendingToday > 0 -> pick(
                "${r.pendingToday} ${if (r.pendingToday == 1) "habit is" else "habits are"} waiting. Start with the easiest one.",
                "Never give up, keep moving forward. Begin with one habit now.",
            )
            else -> pick(
                "Never give up, stay committed, keep moving forward. That is your nindō.",
                "Choose one thing that matters today and finish it.",
            )
        }
    }

    override fun reasonFor(interest: Interest, c: Candidate): String = when (c.kind) {
        PickKind.VIDEO -> "You want to learn ${interest.label}"
        PickKind.NEWS -> "New in ${interest.label}"
    }

    override fun rankPicks(candidates: List<Candidate>, interests: List<Interest>, alreadyShown: Set<String>, nowMs: Long,
                           maxNews: Int, maxVideos: Int): List<Candidate> {
        val byId = interests.associateBy { it.id }
        fun score(c: Candidate): Double {
            val i = byId[c.interestId] ?: return -1.0
            val title = c.title.lowercase()
            val words = (i.keywords + i.label).map { it.lowercase().trim() }.filter { it.isNotEmpty() }.distinct()
            val match = words.count { title.contains(it) }
            val ageDays = if (c.publishedMs <= 0) 30.0 else ((nowMs - c.publishedMs).coerceAtLeast(0) / 86_400_000.0)
            // Matches count most; newer is better, fading over about two weeks.
            return match * 2.0 + 1.0 / (1.0 + ageDays / 3.0)
        }
        val fresh = candidates.filter { it.url !in alreadyShown && byId.containsKey(it.interestId) }
            .distinctBy { it.url }
            .sortedByDescending { score(it) }
        // Spread across interests: one of each before a second from the same interest.
        fun take(kind: PickKind, n: Int): List<Candidate> {
            val pool = fresh.filter { it.kind == kind }.toMutableList()
            val out = mutableListOf<Candidate>()
            val used = mutableSetOf<String>()
            while (out.size < n && pool.isNotEmpty()) {
                val next = pool.firstOrNull { it.interestId !in used } ?: pool.first()
                out += next; pool.remove(next); used += next.interestId
            }
            return out
        }
        return take(PickKind.NEWS, maxNews) + take(PickKind.VIDEO, maxVideos)
    }
}
