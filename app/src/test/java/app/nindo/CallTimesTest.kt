package app.nindo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class CallTimesTest {
    private val ist = TimeZone.getTimeZone("Asia/Kolkata")
    /** Wed 11 Nov 2026, 21:05 IST. */
    private val now = Calendar.getInstance(ist).apply { clear(); set(2026, Calendar.NOVEMBER, 11, 21, 5) }.timeInMillis

    @Test fun parsesTimes() {
        assertEquals(19 * 60, CallTimes.parse("19:00"))
        assertEquals(19 * 60 + 30, CallTimes.parse("7:30 pm"))
        assertEquals(19 * 60, CallTimes.parse("7 PM"))
        assertEquals(7 * 60 + 5, CallTimes.parse("07:05"))
        assertEquals(0, CallTimes.parse("12 am"))
        assertEquals(12 * 60, CallTimes.parse("12 p.m."))
        assertNull(CallTimes.parse("25:00"))
        assertNull(CallTimes.parse("soon"))
    }

    @Test fun whenToRing() {
        val tomorrow7 = CallTimes.at(now, 7 * 60, "tomorrow", ist)!!
        assertEquals("tomorrow 7:00 AM", CallTimes.whenLabel(now, tomorrow7, ist))
        assertEquals("today 9:30 PM", CallTimes.whenLabel(now, CallTimes.at(now, 21 * 60 + 30, "today", ist)!!, ist))
        // 7 PM today has passed: no reminder, rather than a wrong one tomorrow.
        assertNull(CallTimes.at(now, 19 * 60, "today", ist))
        // No day given: the next 7 PM, which is tomorrow.
        assertEquals("tomorrow 7:00 PM", CallTimes.whenLabel(now, CallTimes.at(now, 19 * 60, null, ist)!!, ist))
    }

    @Test fun labels() {
        assertEquals("9:30 PM", CallTimes.label(21 * 60 + 30))
        assertEquals("12:00 AM", CallTimes.label(0))
        assertEquals("It's 9:05 PM on Wednesday.", CallTimes.nowText(now, ist))
    }
}
