package app.nindo

import java.util.Calendar
import java.util.TimeZone

/**
 * Reads one spoken line from the "Tell Sensei" widget without waiting for the AI to load:
 * "remind me at 7 to call mom" -> a reminder call at the next 7 o'clock; "remind me in 20 minutes
 * to stretch" -> a call in 20 minutes; "buy milk tomorrow" -> a task for tomorrow.
 * Plain Kotlin, unit-tested.
 */
object QuickParse {
    sealed class Result {
        data class Call(val title: String, val at: Long) : Result()
        data class Task(val title: String, val due: String?) : Result()
    }

    private val NUM = mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "ten" to 10,
        "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty" to 40, "forty five" to 45, "half an" to 30, "half a" to 30)
    private val IN = Regex("""\bin\s+(\d+|half an?|forty five|an?|one|two|three|four|five|ten|fifteen|twenty|thirty|forty)\s+(minutes?|mins?|hours?|hrs?)\b""",
        RegexOption.IGNORE_CASE)
    private val AT = Regex("""\b(?:at|by|around)\s+(\d{1,2}(?:[:.]\d{2})?\s*(?:a\.?m\.?|p\.?m\.?)?|noon|midnight)(?![\w])""", RegexOption.IGNORE_CASE)
    private val BARE = Regex("""\b(\d{1,2}(?:[:.]\d{2})?\s*(?:a\.?m\.?|p\.?m\.?)|\d{1,2}:\d{2})(?![\w])""", RegexOption.IGNORE_CASE)
    private val DAY = Regex("""\b(today|tonight|tomorrow(?:\s+(?:morning|evening|night|afternoon))?|this\s+(?:morning|evening|afternoon))\b""",
        RegexOption.IGNORE_CASE)
    private val PM_HINT = Regex("""\b(tonight|evening|night|afternoon)\b""", RegexOption.IGNORE_CASE)
    private val AM_HINT = Regex("""\bmorning\b""", RegexOption.IGNORE_CASE)
    private val LEAD = Regex(
        """^(?:hey\s+sensei[,\s]*|sensei[,\s]+|ok(?:ay)?[,\s]+|please\s+)*(?:(?:can you\s+|could you\s+)?(?:remind me|call me|ring me|give me a call|set (?:a |an )?(?:reminder|alarm)|reminder|add (?:a )?task|new task|i need to|i have to|i must|i should|don't let me forget)(?:\s+(?:to|about|that|for|i need to|i have to))?\s+)?""",
        RegexOption.IGNORE_CASE)

    fun parse(text: String, nowMs: Long, tz: TimeZone = TimeZone.getDefault()): Result? {
        val raw = text.trim().trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")
        if (raw.isEmpty()) return null
        var rest = raw
        var at: Long? = null

        IN.find(rest)?.let { m ->
            val word = m.groupValues[1].lowercase()
            val hours = m.groupValues[2].lowercase().startsWith("h")
            // "half an hour" is 30 minutes.
            val ms = if (word.startsWith("half")) (if (hours) 30 else 0) * 60_000L
                else ((NUM[word] ?: word.toIntOrNull() ?: return@let) * if (hours) 3_600_000L else 60_000L)
            if (ms <= 0) return@let
            at = nowMs + ms
            rest = rest.removeRange(m.range)
        }
        val dayMatch = DAY.find(rest)
        val dayWord = dayMatch?.value?.lowercase()
        val day = when {
            dayWord == null -> null
            dayWord.startsWith("tomorrow") -> "tomorrow"
            else -> "today"
        }
        if (at == null) {
            val tm = AT.find(rest) ?: BARE.find(rest)
            if (tm != null) {
                val spoken = tm.groupValues[1].lowercase()
                val minutes = when (spoken) { "noon" -> 720; "midnight" -> 0; else -> CallTimes.parse(spoken) }
                if (minutes != null) {
                    at = resolve(nowMs, minutes, spoken, day, raw, tz)
                    rest = rest.removeRange(tm.range)
                }
            }
        }
        if (dayMatch != null) rest = DAY.replace(rest, "")
        val title = clean(rest)
        if (title.length < 2) return null
        val due = at
        return if (due != null) Result.Call(title, due) else Result.Task(title, day)
    }

    /** "7" without am/pm: the next 7 o'clock that fits (evening words mean pm, morning means am). */
    private fun resolve(nowMs: Long, minutes: Int, spoken: String, day: String?, all: String, tz: TimeZone): Long? {
        val hasAmPm = spoken.contains('a') || spoken.contains('p') || spoken == "noon" || spoken == "midnight" || minutes / 60 > 12
        val options = if (hasAmPm) listOf(minutes)
            else if (PM_HINT.containsMatchIn(all)) listOf((minutes % 720) + 720)
            else if (AM_HINT.containsMatchIn(all)) listOf(minutes % 720)
            else listOf(minutes % 720, (minutes % 720) + 720)
        val times = options.mapNotNull { CallTimes.at(nowMs, it, day, tz) }
        return times.minOrNull() ?: if (day == "today") CallTimes.at(nowMs, options.first(), "tomorrow", tz) else null
    }

    private fun clean(s: String): String {
        var t = s.replace(Regex("\\s+"), " ").trim().trim(',', ' ')
        t = ("$t ").replace(LEAD, "").trim()
        t = t.replace(Regex("""^(to|about|that|for)\s+""", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("""\s+(please|thanks|thank you)$""", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("""\s+(at|by|on|in|around)$""", RegexOption.IGNORE_CASE), "").trim(',', ' ', '.')
        // "remind me to call my mom" -> "Call my mom"
        return t.replaceFirstChar { it.uppercase() }
    }

    /** Short text for the confirmation card. */
    fun describe(r: Result, nowMs: Long): String = when (r) {
        is Result.Call -> "Sensei will call you ${CallTimes.whenLabel(nowMs, r.at)}"
        is Result.Task -> "New task" + when (r.due) { "today" -> " for today"; "tomorrow" -> " for tomorrow"; else -> "" }
    }

    @Suppress("unused") private fun cal(ms: Long, tz: TimeZone) = Calendar.getInstance(tz).apply { timeInMillis = ms }
}
