package app.nindo

/*
 * Keeps a call moving like a good catch-up with a friend: Sensei picks up a few topics from your
 * day (open habits, streaks, open tasks, what you're learning, something from last time), spends
 * a couple of replies on each, then asks if you're good to wrap up. If you are, Sensei says bye
 * and ends its reply with [bye], and the app hangs up.
 *
 * Gemma gets this as a short private note added to what you said, so it never reads it out.
 * Plain Kotlin, unit-tested.
 */
object CallPlan {
    data class Input(
        val openHabits: List<String> = emptyList(),
        /** (habit, days), longest first. */
        val streaks: List<Pair<String, Int>> = emptyList(),
        val openTasks: List<String> = emptyList(),
        val interests: List<String> = emptyList(),
        val fromLastCall: List<String> = emptyList(),
    )

    /** Replies Sensei spends on one topic before moving on. */
    const val PER_TOPIC = 2

    fun topics(i: Input): List<String> {
        val t = mutableListOf<String>()
        if (i.openHabits.isNotEmpty())
            t += "the habits still open today (${i.openHabits.take(4).joinToString(", ")}): ask how they're going and nudge one of them"
        i.streaks.firstOrNull { it.second >= 2 }?.let { (name, days) ->
            t += "their $days-day streak on $name: cheer it and help them keep it going"
        }
        if (i.openTasks.isNotEmpty())
            t += "their open tasks (${i.openTasks.take(4).joinToString(", ")}): ask which are done and which one is next"
        i.interests.firstOrNull()?.let { t += "how learning $it is going" }
        i.fromLastCall.firstOrNull()?.let { t += "something from your last call: $it" }
        if (t.isEmpty()) t += "what they want to get done tomorrow"
        return t.take(5)
    }

    private val END_WORDS = Regex(
        """\b(bye|goodbye|good night|gotta go|got to go|hang up|end the call|cut the call|that's all|that is all|talk (to you )?later|nothing else|i'm done|im done|let's wrap up|lets wrap up)\b""",
        RegexOption.IGNORE_CASE)

    fun wantsToEnd(said: String) = END_WORDS.containsMatchIn(said)

    /** The token Sensei puts at the end of its goodbye. */
    val BYE = Regex("""\[\s*bye\s*]""", RegexOption.IGNORE_CASE)

    const val END_NOTE = "(Private note, don't read it out: they want to end the call. Say a short, warm goodbye and end your reply with [bye].)"
    const val WRAP_NOTE = "(Private note, don't read it out: you've covered the main things. Ask casually if there's anything else, " +
        "or if they're good to wrap up for today. If they say yes, say a short goodbye and end with [bye].)"

    /**
     * The private note for the user's [turn]-th reply (1 = their answer to Sensei's hello), or null.
     * Replies 2-3 go to topic 1, 4-5 to topic 2, and so on; after the last topic Sensei offers to
     * wrap up, and again every 3 replies if they keep talking.
     */
    fun note(turn: Int, topics: List<String>, said: String): String? {
        if (wantsToEnd(said)) return END_NOTE
        if (turn < 2) return null
        val step = turn - 2
        val i = step / PER_TOPIC
        if (i < topics.size) return if (step % PER_TOPIC == 0) "(Private note, don't read it out: when it fits, move on to ${topics[i]}.)" else null
        val after = step - topics.size * PER_TOPIC
        return if (after % 3 == 0) WRAP_NOTE else null
    }
}
