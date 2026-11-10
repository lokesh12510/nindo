package app.nindo

/** Reels/Shorts knowledge and pure counting logic (unit-tested). */
object Reels {
    /** Apps with a Reels/Shorts-style viewer. */
    val DEFAULT_APPS = setOf(
        "com.instagram.android", "com.google.android.youtube", "com.facebook.katana", "com.facebook.lite",
        "com.snapchat.android", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
        "in.mohalla.video", "in.mohalla.sharechat", "com.eterno.shortvideos",
    )

    /** Apps that are nothing but short videos: "close reels only" blocks the whole app. */
    val SHORT_VIDEO_ONLY = setOf(
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "in.mohalla.video", "com.eterno.shortvideos",
    )

    /**
     * View IDs of reel players seen in these apps. Apps rename views in updates, so
     * detection also falls back to "full-screen vertical pager with full-screen pages".
     */
    val KNOWN_IDS: Map<String, Set<String>> = mapOf(
        "com.instagram.android" to setOf("clips_viewer_view_pager", "clips_viewer_container", "clips_video_container", "clips_swipe_refresh_container"),
        "com.google.android.youtube" to setOf("reel_recycler", "reel_player_page_container", "reel_watch_player", "reel_watch_fragment_root"),
        "com.facebook.katana" to setOf("reels_viewer", "reels_viewer_pager", "fb_shorts_viewer"),
        "com.snapchat.android" to setOf("spotlight_view_pager", "spotlight_container"),
    )

    data class Stage(val emoji: String, val label: String, val color: Long)

    private val STAGES = listOf(
        Stage("🧠", "Fresh brain", 0xFF16A34A),
        Stage("😐", "Getting numb", 0xFF65A30D),
        Stage("😵‍💫", "Dizzy", 0xFFD97706),
        Stage("🥴", "Brain fog", 0xFFEA580C),
        Stage("🫠", "Melting", 0xFFDC2626),
        Stage("🧟", "Brain rot", 0xFF7C3AED),
    )

    /** Stage by share of the cap (or of 100 reels when there's no cap). */
    fun stage(count: Int, cap: Int): Stage {
        val ref = if (cap > 0) cap else 100
        if (count >= ref) return STAGES.last()
        val i = (count * 5) / ref // 0..4
        return STAGES[i.coerceIn(0, 4)]
    }

    /** Popup text for milestones; null when this count isn't one. */
    fun milestone(count: Int, cap: Int): String? = when {
        cap > 0 && count == cap -> "🧟 Reel limit reached: $count today"
        cap > 0 && count == cap - 10 && cap > 20 -> "⏳ 10 reels left today"
        count > 0 && count % 25 == 0 -> "${stage(count, cap).emoji} $count reels today · about ${count * 20 / 60} min"
        else -> null
    }

    /**
     * One viewing session in a reel player. Counts a reel when the pager reaches a page
     * further than any seen before (swiping back doesn't recount). When the app gives no
     * page index, a swipe is a burst of scroll events after a quiet gap.
     */
    class Session(private val gapMs: Long = 5 * 60_000L, private val swipeQuietMs: Long = 650) {
        private var maxIndex = -1
        private var lastEvent = NONE
        private var lastRaw = NONE

        fun reset() { maxIndex = -1; lastEvent = NONE; lastRaw = NONE }

        /** @return true when this scroll event means a new reel. */
        fun onScroll(index: Int, now: Long): Boolean {
            if (lastEvent != NONE && now - lastEvent > gapMs) reset()
            lastEvent = now
            if (index >= 0) {
                if (index > maxIndex) { maxIndex = index; return true }
                return false
            }
            val quiet = lastRaw == NONE || now - lastRaw > swipeQuietMs
            lastRaw = now
            return quiet
        }

        private companion object { const val NONE = Long.MIN_VALUE }
    }
}
