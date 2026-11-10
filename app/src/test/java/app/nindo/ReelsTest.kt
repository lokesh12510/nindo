package app.nindo

import org.junit.Assert.*
import org.junit.Test

class ReelsTest {
    @Test fun countsEachNewPageOnce() {
        val s = Reels.Session()
        var t = 1_000L
        fun scroll(i: Int) = s.onScroll(i, t).also { t += 100 }
        assertTrue(scroll(0))                              // first reel
        assertFalse(scroll(0)); assertFalse(scroll(0))     // mid-swipe events
        assertTrue(scroll(1))                              // swiped to reel 2
        assertTrue(scroll(2))
        assertFalse(scroll(1))                             // swiping back doesn't recount
        assertFalse(scroll(2))
        assertTrue(scroll(3))
    }

    @Test fun withoutIndexCountsSwipeBursts() {
        val s = Reels.Session(swipeQuietMs = 650)
        assertTrue(s.onScroll(-1, 0))
        assertFalse(s.onScroll(-1, 100)); assertFalse(s.onScroll(-1, 250))  // same swipe
        assertTrue(s.onScroll(-1, 2_000))                                    // next swipe
        assertFalse(s.onScroll(-1, 2_300))
    }

    @Test fun longPauseStartsNewSession() {
        val s = Reels.Session(gapMs = 60_000)
        assertTrue(s.onScroll(5, 0))
        assertFalse(s.onScroll(3, 1_000))
        assertTrue(s.onScroll(3, 120_000)) // a new visit to the reels screen
    }

    @Test fun brainStages() {
        assertEquals("🧠", Reels.stage(0, 0).emoji)
        assertEquals("🧠", Reels.stage(19, 0).emoji)
        assertEquals("😐", Reels.stage(20, 0).emoji)
        assertEquals("🫠", Reels.stage(99, 0).emoji)
        assertEquals("🧟", Reels.stage(100, 0).emoji)
        // with a cap of 50 the stages move twice as fast
        assertEquals("😵‍💫", Reels.stage(20, 50).emoji)
        assertEquals("🧟", Reels.stage(50, 50).emoji)
    }

    @Test fun milestones() {
        assertNull(Reels.milestone(24, 0))
        assertTrue(Reels.milestone(25, 0)!!.contains("25 reels"))
        assertTrue(Reels.milestone(90, 100)!!.contains("10 reels left"))
        assertTrue(Reels.milestone(100, 100)!!.contains("limit reached"))
        assertNull(Reels.milestone(0, 0))
    }
}
