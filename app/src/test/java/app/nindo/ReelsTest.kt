package app.nindo

import org.junit.Assert.*
import org.junit.Test

class ReelsTest {
    @Test fun countsEachNewPageOnce() {
        val s = Reels.Session()
        var t = 1_000L
        fun scroll(i: Int) = s.onScroll(i, t).also { t += 100 }
        assertFalse(scroll(0))                             // the reel you start on is the baseline
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
        assertFalse(s.onScroll(5, 0))
        assertTrue(s.onScroll(6, 500))
        assertFalse(s.onScroll(5, 1_000))
        assertFalse(s.onScroll(3, 120_000)) // a new visit: new baseline
        assertTrue(s.onScroll(4, 121_000))
    }

    @Test fun restartedNumberingKeepsCounting() {
        // Left reels at page 40, came back: the app numbers from 0 again.
        val s = Reels.Session()
        assertFalse(s.onScroll(40, 0)); assertTrue(s.onScroll(41, 100))
        assertFalse(s.onScroll(0, 5_000))
        assertTrue(s.onScroll(1, 5_500)); assertTrue(s.onScroll(2, 6_000))
    }

    @Test fun scrollDistanceCountsWholeSwipes() {
        val s = Reels.Session()
        val h = 2000
        var t = 0L
        fun d(px: Int) = s.onScroll(-1, t, px, h).also { t += 16 }
        assertFalse(d(100))                                  // start of swipe 1: baseline
        assertFalse(d(500)); assertTrue(d(500))              // past halfway: reel 1 counted
        assertFalse(d(900))                                  // finishing the same swipe
        assertFalse(d(-1500)); assertFalse(d(-500))          // swiped back
        assertFalse(d(2000))                                 // forward to an already-seen reel
        repeat(10) { assertTrue(d(2000)) }                   // ten new reels
    }

    @Test fun flashEveryTen() {
        assertFalse(Reels.shouldFlash(0)); assertFalse(Reels.shouldFlash(9))
        assertTrue(Reels.shouldFlash(10)); assertTrue(Reels.shouldFlash(20)); assertFalse(Reels.shouldFlash(21))
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
