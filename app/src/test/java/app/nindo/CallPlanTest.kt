package app.nindo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallPlanTest {
    private val input = CallPlan.Input(openHabits = listOf("Workout", "Read"), streaks = listOf("Python" to 5),
        openTasks = listOf("Call mom"), interests = listOf("Python"))
    private val topics = CallPlan.topics(input)

    @Test fun topicsCoverHabitsStreaksTasksAndInterests() {
        assertEquals(4, topics.size)
        assertTrue(topics[0].contains("Workout"))
        assertTrue(topics[1].contains("5-day streak on Python"))
        assertTrue(topics[2].contains("Call mom"))
        assertTrue(topics[3].contains("learning Python"))
        assertEquals(listOf("what they want to get done tomorrow"), CallPlan.topics(CallPlan.Input()))
    }

    @Test fun movesOnEveryTwoRepliesThenOffersToWrapUp() {
        assertNull(CallPlan.note(1, topics, "it was fine"))
        assertTrue(CallPlan.note(2, topics, "ok")!!.contains("Workout"))
        assertNull(CallPlan.note(3, topics, "ok"))
        assertTrue(CallPlan.note(4, topics, "ok")!!.contains("streak"))
        assertTrue(CallPlan.note(8, topics, "ok")!!.contains("learning Python"))
        assertEquals(CallPlan.WRAP_NOTE, CallPlan.note(10, topics, "ok"))
        assertNull(CallPlan.note(11, topics, "ok"))
        assertEquals(CallPlan.WRAP_NOTE, CallPlan.note(13, topics, "ok"))
    }

    @Test fun goodbyeEndsTheCall() {
        assertEquals(CallPlan.END_NOTE, CallPlan.note(3, topics, "okay bye Sensei"))
        assertTrue(CallPlan.wantsToEnd("I think that's all for today"))
        assertFalse(CallPlan.wantsToEnd("I bought a new bicycle"))
        assertTrue(CallPlan.BYE.containsMatchIn("Sleep well, ninja. [bye]"))
        assertEquals("Sleep well, ninja. ", CallPlan.BYE.replace("Sleep well, ninja. [ Bye ]", ""))
    }
}
