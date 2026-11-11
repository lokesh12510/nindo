package app.nindo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SenseiMemoryTest {
    @Test fun readsNotesEvenWithCodeFences() {
        val answer = """Sure! ```json
{"summary": "You talked about a long day and finishing the loops video.",
 "facts": ["Wants to get a Python job by March", "Sister Priya visits on weekends", "Wants to get a Python job by March"],
 "tasks": [{"title": "Call mom", "due": "tomorrow"}, {"title": "Gym", "due": "someday"}, "Buy notebook"],
 "interests": ["Python", "guitar"]}
```"""
        val n = SenseiMemory.parse(answer)!!
        assertEquals("You talked about a long day and finishing the loops video.", n.summary)
        assertEquals(listOf("Wants to get a Python job by March", "Sister Priya visits on weekends"), n.facts)
        assertEquals(listOf("Call mom" to "tomorrow", "Gym" to null, "Buy notebook" to null), n.tasks)
        assertEquals(listOf("Python", "guitar"), n.interests)
    }

    @Test fun readsWhatTheUserSaidIsDone() {
        val n = SenseiMemory.parse("""{"summary":"x","facts":[],"tasks":[],"interests":[],"done_tasks":[2, "1", 0, -3],"done_habits":["3."]}""")!!
        assertEquals(listOf(2, 1), n.doneTasks)
        assertEquals(listOf(3), n.doneHabits)
        val p = SenseiMemory.notesPrompt(emptyList(), emptyList(), listOf("Call mom", "Buy notebook"), listOf("Workout"))
        assertTrue(p.contains("1. Call mom") && p.contains("2. Buy notebook") && p.contains("1. Workout"))
    }

    @Test fun readsReminderCalls() {
        val n = SenseiMemory.parse("""{"summary":"x","reminders":[{"title":"Call mom","time":"19:00","day":"today"},
            {"title":"Gym","time":"7 am","day":"tomorrow"},{"title":"Bad","time":"later"},{"title":"Any","time":"21:30"}]}""")!!
        assertEquals(listOf(SenseiMemory.Reminder("Call mom", 19 * 60, "today"), SenseiMemory.Reminder("Gym", 7 * 60, "tomorrow"),
            SenseiMemory.Reminder("Any", 21 * 60 + 30, null)), n.reminders)
    }

    @Test fun badAnswersGiveNullOrEmpty() {
        assertNull(SenseiMemory.parse("I couldn't do that."))
        assertNull(SenseiMemory.parse("{not json"))
        val n = SenseiMemory.parse("""{"summary": ""}""")!!
        assertTrue(n.facts.isEmpty() && n.tasks.isEmpty() && n.interests.isEmpty())
    }

    @Test fun capsLists() {
        val n = SenseiMemory.parse("""{"summary":"x","facts":["a1","b2","c3","d4","e5","f6","g7"],"tasks":[],"interests":["a1","b2","c3","d4"]}""")!!
        assertEquals(5, n.facts.size)
        assertEquals(3, n.interests.size)
    }

    @Test fun duplicateFacts() {
        val known = listOf("Wants to get a Python job by March", "Has a sister named Priya")
        assertTrue(SenseiMemory.isKnown("wants to get a python job by march.", known))
        assertTrue(SenseiMemory.isKnown("Has a sister named Priya who visits on weekends", known))
        assertFalse(SenseiMemory.isKnown("Plays cricket on Sundays", known))
    }

    @Test fun transcriptAndNextCall() {
        val t = SenseiMemory.transcript(listOf(SenseiMemory.Line(true, "Hey!"), SenseiMemory.Line(false, "Hi Sensei"), SenseiMemory.Line(false, " ")))
        assertEquals("Sensei: Hey!\nYou: Hi Sensei", t)
        val next = SenseiMemory.forNextCall(listOf(Fact("1", "Learning Python", "2026-11-11")),
            listOf(CallLog("c", 0, 1, "You talked about loops.", t)))
        assertTrue(next.contains("- Learning Python"))
        assertTrue(next.contains("Last call: You talked about loops."))
        assertEquals("", SenseiMemory.forNextCall(emptyList(), emptyList()))
    }
}
