package app.nindo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class QuickParseTest {
    private val ist = TimeZone.getTimeZone("Asia/Kolkata")
    /** Wed 11 Nov 2026, 15:20 IST. */
    private val now = Calendar.getInstance(ist).apply { clear(); set(2026, Calendar.NOVEMBER, 11, 15, 20) }.timeInMillis
    private fun call(s: String) = QuickParse.parse(s, now, ist) as QuickParse.Result.Call
    private fun label(c: QuickParse.Result.Call) = CallTimes.whenLabel(now, c.at, ist)

    @Test fun remindAtATime() {
        val c = call("Remind me at 7 to call mom")
        assertEquals("Call mom", c.title)
        assertEquals("today 7:00 PM", label(c)) // 7 am has passed, so the next 7 o'clock
        assertEquals("tomorrow 7:00 AM", label(call("remind me to go running tomorrow morning at 7")))
        assertEquals("Go running", call("remind me to go running tomorrow morning at 7").title)
        assertEquals("today 9:30 PM", label(call("Hey Sensei, remind me about the gym at 9:30 pm")))
        assertEquals("The gym", call("Hey Sensei, remind me about the gym at 9:30 pm").title)
    }

    @Test fun remindInSomeMinutes() {
        val c = call("remind me in 20 minutes to drink water")
        assertEquals("Drink water", c.title)
        assertEquals(now + 20 * 60_000L, c.at)
        assertEquals(now + 30 * 60_000L, call("call me in half an hour about the report").at)
        assertEquals(now + 2 * 3_600_000L, call("in two hours remind me to stretch").at)
    }

    @Test fun noTimeMeansATask() {
        assertEquals(QuickParse.Result.Task("Buy milk", "tomorrow"), QuickParse.parse("buy milk tomorrow", now, ist))
        assertEquals(QuickParse.Result.Task("Finish the report", null), QuickParse.parse("I need to finish the report.", now, ist))
        assertEquals(QuickParse.Result.Task("Pay rent", "today"), QuickParse.parse("add a task pay rent today", now, ist))
        assertNull(QuickParse.parse("remind me", now, ist))
        assertNull(QuickParse.parse("  ", now, ist))
    }

    @Test fun tonightMeansEvening() {
        val c = call("remind me tonight at 8 to read")
        assertEquals("Read", c.title)
        assertTrue(label(c) == "today 8:00 PM")
    }
}
