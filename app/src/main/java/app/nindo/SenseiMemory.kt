package app.nindo

import org.json.JSONArray
import org.json.JSONObject

/*
 * Sensei's memory, written after each call. Gemma reads the call and writes short notes:
 * a summary (shown in Insights), facts about you to bring up in later calls, and tasks and
 * interests you mentioned (added to the Sensei screen). Everything stays on the phone, and
 * every note can be deleted.
 *
 * The prompt and the parser are plain Kotlin so they're unit-tested without a phone.
 */

data class CallLog(
    val id: String,
    val started: Long,
    val ended: Long,
    val summary: String,
    /** "You: …" / "Sensei: …" lines. */
    val transcript: String,
)

data class Fact(val id: String, val text: String, val created: String, val callId: String? = null)

object SenseiMemory {

    data class Line(val sensei: Boolean, val text: String)

    data class Notes(
        val summary: String,
        val facts: List<String>,
        /** (title, "today" / "tomorrow" / null). */
        val tasks: List<Pair<String, String?>>,
        val interests: List<String>,
        /** Numbers (1-based) from the open-task list that the user said are done. */
        val doneTasks: List<Int> = emptyList(),
        /** Numbers (1-based) from today's open habits that the user said they did. */
        val doneHabits: List<Int> = emptyList(),
        /** "Remind me at 7 to call mom": calls Sensei makes to you. */
        val reminders: List<Reminder> = emptyList(),
    )

    /** [minutes] after midnight; [day] "today", "tomorrow" or null (the next time it comes round). */
    data class Reminder(val title: String, val minutes: Int, val day: String?)

    fun transcript(lines: List<Line>): String =
        lines.filter { it.text.isNotBlank() }.joinToString("\n") { (if (it.sensei) "Sensei: " else "You: ") + it.text.trim() }

    /** Instructions for writing the notes. Low temperature; JSON only. */
    fun notesPrompt(knownFacts: List<String>, interests: List<String>, openTasks: List<String> = emptyList(),
                    openHabits: List<String> = emptyList(), now: String? = null): String = buildString {
        appendLine("You write short private notes after a voice call between Sensei, a friendly coach, and the user.")
        appendLine("Read the call and answer with JSON only, nothing else, in exactly this shape:")
        appendLine("""{"summary": "...", "facts": ["..."], "tasks": [{"title": "...", "due": "today"}], "interests": ["..."], "done_tasks": [1], "done_habits": [1], "reminders": [{"title": "...", "time": "19:00", "day": "today"}]}""")
        appendLine("Rules:")
        appendLine("- summary: one or two plain sentences about what the user talked about and how they felt, written to them (\"You talked about...\").")
        appendLine("- facts: lasting things about the user that would help a friend in later calls: goals, plans, people they mentioned, likes, " +
            "what's hard for them right now. Each under 12 words. Only what the USER said, never what Sensei said. At most 5.")
        appendLine("- tasks: only things the user clearly said they want to do or be reminded of. due is \"today\", \"tomorrow\" or \"none\". At most 3.")
        appendLine("- interests: topics the user wants to learn or follow, one to three words each, like \"Python\" or \"guitar\". At most 3.")
        appendLine("- tasks must be NEW things. Don't repeat the open tasks listed below.")
        appendLine("- done_tasks / done_habits: the numbers from the lists below that the user clearly said they already finished. " +
            "Only when they said so; otherwise empty.")
        appendLine("- reminders: only when the user asked to be reminded, or asked Sensei to call them, at a clock time. " +
            "time is 24-hour HH:MM; day is \"today\" or \"tomorrow\". \"In an hour\" means now plus an hour. At most 3.")
        if (now != null) appendLine(now)
        appendLine("- Use empty lists when nothing fits. Never invent anything.")
        appendLine("- Leave out anything about self-harm, and leave out health or money details unless the user asked Sensei to remember them.")
        if (knownFacts.isNotEmpty()) appendLine("Already known (don't repeat these): ${knownFacts.take(30).joinToString("; ")}.")
        if (interests.isNotEmpty()) appendLine("Interests already known: ${interests.joinToString(", ")}.")
        appendLine("Open tasks:" + if (openTasks.isEmpty()) " none" else "")
        openTasks.forEachIndexed { i, t -> appendLine("${i + 1}. $t") }
        appendLine("Habits still open today:" + if (openHabits.isEmpty()) " none" else "")
        openHabits.forEachIndexed { i, h -> appendLine("${i + 1}. $h") }
    }

    /** Reads Gemma's answer. Tolerates text or code fences around the JSON; returns null if there's no usable JSON. */
    fun parse(answer: String): Notes? {
        val start = answer.indexOf('{')
        val end = answer.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val o = runCatching { JSONObject(answer.substring(start, end + 1)) }.getOrNull() ?: return null
        fun strings(a: JSONArray?, max: Int, maxLen: Int): List<String> =
            if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optString(it, "").trim().takeIf { s -> s.length in 2..maxLen } }
                .distinctBy { it.lowercase() }.take(max)
        val tasks = o.optJSONArray("tasks")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val t = a.optJSONObject(i) ?: return@mapNotNull a.optString(i, "").trim().takeIf { it.length in 3..120 }?.let { it to null }
                val title = t.optString("title", "").trim()
                if (title.length !in 3..120) return@mapNotNull null
                title to when (t.optString("due", "").lowercase().trim()) { "today" -> "today"; "tomorrow" -> "tomorrow"; else -> null }
            }.distinctBy { it.first.lowercase() }.take(3)
        }.orEmpty()
        return Notes(
            summary = o.optString("summary", "").trim().take(400),
            facts = strings(o.optJSONArray("facts"), 5, 120),
            tasks = tasks,
            interests = strings(o.optJSONArray("interests"), 3, 32),
            doneTasks = numbers(o.optJSONArray("done_tasks")),
            doneHabits = numbers(o.optJSONArray("done_habits")),
            reminders = o.optJSONArray("reminders")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val r = a.optJSONObject(i) ?: return@mapNotNull null
                    val title = r.optString("title", "").trim().takeIf { it.length in 2..120 } ?: return@mapNotNull null
                    val min = CallTimes.parse(r.optString("time", "")) ?: return@mapNotNull null
                    val day = when (r.optString("day", "").lowercase().trim()) { "today" -> "today"; "tomorrow" -> "tomorrow"; else -> null }
                    Reminder(title, min, day)
                }.take(3)
            }.orEmpty(),
        )
    }

    /** 1-based numbers, also when written as strings ("2"). */
    private fun numbers(a: JSONArray?): List<Int> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { i ->
            a.optInt(i, -1).takeIf { it > 0 } ?: a.optString(i, "").trim().trimEnd('.').toIntOrNull()?.takeIf { it > 0 }
        }.distinct().take(20)

    /** True when [fact] says the same as one we already have (same words, or one contains the other). */
    fun isKnown(fact: String, known: List<String>): Boolean {
        val f = norm(fact)
        if (f.isEmpty()) return true
        return known.any { k -> val n = norm(k); n == f || (n.length > 12 && f.contains(n)) || (f.length > 12 && n.contains(f)) }
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), "").replace(Regex("\\s+"), " ").trim()

    /** The part of Sensei's instructions that carries memory into the next call. */
    fun forNextCall(facts: List<Fact>, calls: List<CallLog>): String = buildString {
        if (facts.isEmpty() && calls.isEmpty()) return@buildString
        appendLine("What you remember from earlier calls (bring it up only when it fits, like a friend would; never list it):")
        facts.take(15).forEach { appendLine("- ${it.text}") }
        calls.filter { it.summary.isNotBlank() }.take(2).forEachIndexed { i, c ->
            appendLine("- ${if (i == 0) "Last call" else "The call before"}: ${c.summary}")
        }
    }
}
