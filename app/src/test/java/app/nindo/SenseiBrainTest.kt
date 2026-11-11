package app.nindo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SenseiBrainTest {
    private val py = Interest(id = "py", label = "Python", keywords = listOf("loops"), created = "2026-11-01")
    private val gt = Interest(id = "gt", label = "Guitar", created = "2026-11-01")
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    @Test fun reelCapComesFirst() {
        val w = RuleBrain.todaysWord(DayRecap(doneYesterday = 3, totalYesterday = 3, reelCapBroken = true, day = "2026-11-11"))
        assertTrue(w, w.contains("reel", ignoreCase = true))
    }

    @Test fun perfectDayWithStreakNamesTheHabit() {
        val w = RuleBrain.todaysWord(DayRecap(doneYesterday = 2, totalYesterday = 2, topStreak = "Reading" to 5, day = "2026-11-11"))
        assertTrue(w, w.contains("Reading") && w.contains("5"))
    }

    @Test fun missedHabitIsNamed() {
        val w = RuleBrain.todaysWord(DayRecap(doneYesterday = 1, totalYesterday = 2, missedYesterday = listOf("Workout"), day = "2026-11-12"))
        assertTrue(w, w.contains("Workout"))
    }

    @Test fun nothingKnownStillSaysSomething() {
        assertTrue(RuleBrain.todaysWord(DayRecap(day = "2026-11-11")).isNotBlank())
    }

    @Test fun noEmojisInAnyWord() {
        val recaps = listOf(
            DayRecap(reelCapBroken = true), DayRecap(doneYesterday = 1, totalYesterday = 1),
            DayRecap(missedYesterday = listOf("X"), totalYesterday = 1), DayRecap(topStreak = "X" to 4),
            DayRecap(interest = "Python"), DayRecap(pendingToday = 2), DayRecap(),
        )
        for (r in recaps) for (d in listOf("a", "b", "c", "d")) {
            val w = RuleBrain.todaysWord(r.copy(day = d))
            assertTrue(w, w.codePoints().noneMatch { Character.getType(it) == Character.OTHER_SYMBOL.toInt() || it >= 0x1F000 })
        }
    }

    @Test fun picksAreCappedAndMatchedFirst() {
        val c = listOf(
            Candidate(PickKind.VIDEO, "py", "Cooking pasta", "u1", publishedMs = now),
            Candidate(PickKind.VIDEO, "py", "Python loops in 10 minutes", "u2", publishedMs = now - 5 * day),
            Candidate(PickKind.VIDEO, "py", "Python for beginners", "u3", publishedMs = now - 2 * day),
            Candidate(PickKind.NEWS, "py", "Python 3.15 released", "n1", publishedMs = now),
            Candidate(PickKind.NEWS, "py", "Python news", "n2", publishedMs = now - day),
        )
        val out = RuleBrain.rankPicks(c, listOf(py), emptySet(), now)
        assertEquals(listOf("n1", "u2", "u3"), out.map { it.url })
    }

    @Test fun picksSpreadAcrossInterestsAndSkipShown() {
        val c = listOf(
            Candidate(PickKind.VIDEO, "py", "Python loops", "a", publishedMs = now),
            Candidate(PickKind.VIDEO, "py", "Python loops again", "b", publishedMs = now),
            Candidate(PickKind.VIDEO, "gt", "Guitar chords", "c", publishedMs = now - 3 * day),
            Candidate(PickKind.VIDEO, "gone", "Unknown interest", "d", publishedMs = now),
        )
        val out = RuleBrain.rankPicks(c, listOf(py, gt), alreadyShown = setOf("a"), nowMs = now)
        assertEquals(setOf("b", "c"), out.map { it.url }.toSet())
    }
}
