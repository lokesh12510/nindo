package app.nindo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InterestMinerTest {
    @Test fun learningVerbs() {
        assertEquals("Python", InterestMiner.fromHabit("Learn Python", false))
        assertEquals("Python", InterestMiner.fromHabit("Learn Python for data science", false))
        assertEquals("Guitar", InterestMiner.fromHabit("Practice guitar 20 min", false))
        assertEquals("Python", InterestMiner.fromHabit("Watch a Python video", false))
        assertEquals("English speaking", InterestMiner.fromHabit("Improve English speaking daily", false))
        assertEquals("Spanish", InterestMiner.fromHabit("Study Spanish - Duolingo", false))
        assertEquals("Swim", InterestMiner.fromHabit("Learn to swim", false))
    }

    @Test fun knownTopicWithoutVerb() {
        assertEquals("Python", InterestMiner.fromHabit("Python 30 min", false))
        assertEquals("Web development", InterestMiner.fromHabit("Web development project", false))
        assertNull(InterestMiner.fromHabit("Pythonista meetup", false))
    }

    @Test fun learningCategoryBareTopic() {
        assertEquals("Chess", InterestMiner.fromHabit("Chess", true))
        assertEquals("Drawing", InterestMiner.fromHabit("Drawing practice", true))
        assertNull(InterestMiner.fromHabit("Read 10 pages", true))
        assertNull(InterestMiner.fromHabit("Homework", true))
        assertNull(InterestMiner.fromHabit("Chess", false))
    }

    @Test fun choresAndOtherHabitsDontCount() {
        assertNull(InterestMiner.fromHabit("Drink 8 glasses of water", false))
        assertNull(InterestMiner.fromHabit("Workout", false))
        assertNull(InterestMiner.fromTask("Buy milk"))
        assertNull(InterestMiner.fromTask("Call mom at 7"))
        assertEquals("React hooks", InterestMiner.fromTask("Learn React hooks tomorrow"))
        assertNull(InterestMiner.fromTask("Learn something new"))
    }
}
