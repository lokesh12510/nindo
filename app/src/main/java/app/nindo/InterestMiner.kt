package app.nindo

/**
 * Finds what you're learning from your own habit and task names, so Sensei can pick updates and
 * videos for it without you adding it by hand. Rules only (no AI yet): "Learn Python",
 * "Practice guitar 20 min", "Watch a Python video", "Improve English speaking" and Learning
 * habits named after a topic. Plain Kotlin, unit-tested.
 */
object InterestMiner {
    private val LEAD = Regex(
        """\b(?:learn(?:ing)?|stud(?:y|ying)|practi[cs](?:e|ing)|improve|master|revise|revising|get better at|work on|watch|explore)\s+(.+)""",
        RegexOption.IGNORE_CASE)

    /** Cuts the topic at the first word that starts a time, place or reason. */
    private val CUT = Regex(
        """(?:\s+(?:for|daily|every|each|today|tonight|tomorrow|at|by|before|after|during|with|from|until|till|per|x)\b.*$)|(?:\s*[-:(,;|/].*$)|(?:\s+\d.*$)""",
        RegexOption.IGNORE_CASE)

    private val LEADING = setOf("a", "an", "the", "some", "my", "more", "new", "to", "how", "about", "on", "one", "1", "daily", "basic")
    private val TRAILING = setOf("video", "videos", "course", "courses", "lesson", "lessons", "class", "classes", "tutorial", "tutorials",
        "basics", "practice", "practise", "session", "sessions", "chapter", "chapters", "skills", "skill", "daily", "min", "mins", "minutes",
        "hour", "hours", "hr", "hrs", "time")

    /** Habits that are about learning but name no topic of their own. */
    private val GENERIC = setOf("read", "reading", "book", "books", "pages", "journal", "journaling", "homework", "study", "studying",
        "learn", "learning", "revise", "revision", "notes", "practice", "school", "college", "class", "lecture", "lectures", "exam",
        "exams", "something", "anything", "it", "this", "that", "new", "stuff", "things", "project", "projects",
        "assignment", "assignments", "report", "work", "task", "tasks")

    /** A topic from a habit. [learning]: the habit is in the Learning category, so a bare topic name counts too. */
    fun fromHabit(name: String, learning: Boolean): String? =
        lead(name) ?: StarterChannels.topicIn(name) ?: if (learning) clean(name) else null

    /** A topic from a task. Only clear learning tasks count ("Learn …", "Practice …"), not chores. */
    fun fromTask(title: String): String? = lead(title)

    private fun lead(s: String): String? = LEAD.find(s)?.groupValues?.get(1)?.let(::clean)

    fun clean(raw: String): String? {
        var words = (" " + raw).replace(CUT, "").trim().trimEnd('.', '!', '?').split(Regex("\\s+")).filter { it.isNotEmpty() }
        while (words.isNotEmpty() && words.first().lowercase() in LEADING) words = words.drop(1)
        while (words.isNotEmpty() && words.last().lowercase() in TRAILING) words = words.dropLast(1)
        if (words.isEmpty() || words.size > 3) return null
        val topic = words.joinToString(" ")
        if (topic.length !in 2..32 || topic.lowercase() in GENERIC || words.all { it.lowercase() in GENERIC }) return null
        if (topic.any { !it.isLetterOrDigit() && it != ' ' && it != '+' && it != '#' && it != '.' && it != '\'' }) return null
        return topic.replaceFirstChar { it.uppercase() }
    }
}
