package app.nindo

import java.util.Calendar
import java.util.TimeZone

/** Clock times for Sensei's calls ("19:30", "7 pm", "today", "tomorrow"). Plain Kotlin, unit-tested. */
object CallTimes {
    private val TIME = Regex("""^\s*(\d{1,2})(?:[:.](\d{2}))?\s*([ap])?\.?\s*m?\.?\s*$""", RegexOption.IGNORE_CASE)

    /** Minutes after midnight, or null if it isn't a time. */
    fun parse(s: String): Int? {
        val m = TIME.find(s) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        val ampm = m.groupValues[3].lowercase()
        if (min > 59) return null
        when (ampm) {
            "a" -> { if (h !in 1..12) return null; if (h == 12) h = 0 }
            "p" -> { if (h !in 1..12) return null; if (h != 12) h += 12 }
            else -> if (h > 23) return null
        }
        return h * 60 + min
    }

    /**
     * When a call at [minutes] should ring. [day] "today", "tomorrow" or null (= the next time it comes round).
     * A time today that has already passed gives null, so a late reminder is never set for tomorrow by mistake.
     */
    fun at(nowMs: Long, minutes: Int, day: String?, tz: TimeZone = TimeZone.getDefault()): Long? {
        val c = Calendar.getInstance(tz).apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, minutes / 60); set(Calendar.MINUTE, minutes % 60); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        when (day) {
            "tomorrow" -> c.add(Calendar.DAY_OF_YEAR, 1)
            "today" -> if (c.timeInMillis <= nowMs) return null
            else -> if (c.timeInMillis <= nowMs) c.add(Calendar.DAY_OF_YEAR, 1)
        }
        return c.timeInMillis
    }

    /** "7:00 PM" style. */
    fun label(minutes: Int): String {
        val h = minutes / 60; val m = minutes % 60
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "%d:%02d %s".format(h12, m, if (h < 12) "AM" else "PM")
    }

    /** "today 7:00 PM" / "tomorrow 7:00 AM" / "Fri 7:00 PM". */
    fun whenLabel(nowMs: Long, atMs: Long, tz: TimeZone = TimeZone.getDefault()): String {
        val a = Calendar.getInstance(tz).apply { timeInMillis = atMs }
        val n = Calendar.getInstance(tz).apply { timeInMillis = nowMs }
        val dayDiff = ((startOfDay(a) - startOfDay(n)) / 86_400_000L).toInt()
        val t = label(a.get(Calendar.HOUR_OF_DAY) * 60 + a.get(Calendar.MINUTE))
        return when (dayDiff) {
            0 -> "today $t"; 1 -> "tomorrow $t"
            else -> arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")[a.get(Calendar.DAY_OF_WEEK) - 1] + " $t"
        }
    }

    private fun startOfDay(c: Calendar): Long = (c.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** "It's 9:05 PM on Wednesday" for the notes prompt, so "at 7" can be read as a time. */
    fun nowText(nowMs: Long, tz: TimeZone = TimeZone.getDefault()): String {
        val c = Calendar.getInstance(tz).apply { timeInMillis = nowMs }
        val day = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")[c.get(Calendar.DAY_OF_WEEK) - 1]
        return "It's ${label(c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE))} on $day."
    }
}
