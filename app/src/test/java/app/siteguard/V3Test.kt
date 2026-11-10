package app.siteguard

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class V3Test {
    @Test fun categoryMatchesSubdomainsNotLookalikes() {
        assertTrue(Blocklists.containsHost("social", "www.instagram.com"))
        assertTrue(Blocklists.containsHost("social", "scontent.cdninstagram.com"))
        assertTrue(Blocklists.containsHost("adult", "de.pornhub.com"))
        assertFalse(Blocklists.containsHost("social", "notinstagram.com"))
        assertFalse(Blocklists.containsHost("social", "com"))
        assertFalse(Blocklists.containsHost("nope", "instagram.com"))
        // "co.uk"-style suffixes are never checked on their own
        assertTrue(Blocklists.containsHost("news", "www.bbc.co.uk"))
        assertFalse(Blocklists.containsHost("news", "other.co.uk"))
    }

    @Test fun categoryRuleUsesLists() {
        val r = Rule(target = Target.CATEGORY, value = "gambling", label = "Betting & gambling")
        assertTrue(r.matchesSite("stake.com/casino", "stake.com"))
        assertFalse(r.matchesSite("google.com", "google.com"))
        assertTrue(Blocklists.containsApp("social", "com.instagram.android"))
        assertTrue(Blocklists.containsApp("games", "some.unknown.game") { true })   // Play category = game
        assertFalse(Blocklists.containsApp("games", "some.unknown.tool") { false })
    }

    @Test fun parsesHostsFiles() {
        val text = """
            # comment
            127.0.0.1 localhost
            0.0.0.0 0.0.0.0
            0.0.0.0 ads.Example.com # trailing
            0.0.0.0 bad.site
            ::1 ip6-localhost
            not a line
        """.trimIndent()
        assertEquals(setOf("ads.example.com", "bad.site"), Blocklists.parseHosts(text))
    }

    @Test fun streaks() {
        val g = 3 * 3_600_000L
        fun d(vararg hours: Double) = hours.mapIndexed { i, h -> "d$i" to (h * 3_600_000).toLong() }
        // untracked, ok, ok, over, ok, ok, ok, today(ok)
        val r = Streaks.compute(d(0.0, 2.0, 1.0, 5.0, 2.0, 2.5, 3.0, 1.0), g)
        assertEquals(3, r.current); assertEquals(3, r.best); assertTrue(r.todayOk)
        val over = Streaks.compute(d(1.0, 1.0, 4.0), g)
        assertEquals(2, over.current); assertFalse(over.todayOk)
        val broken = Streaks.compute(d(1.0, 1.0, 1.0, 1.0, 0.0, 1.0), g) // untracked day breaks it
        assertEquals(0, broken.current); assertEquals(4, broken.best)
        assertEquals(0, Streaks.compute(emptyList(), g).current)
    }

    @Test fun weeklyReportRunsSundayEvening() {
        val wed = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 11, 10, 0, 0) }
        val run = Calendar.getInstance().apply { timeInMillis = Reports.nextRun(wed) }
        assertEquals(Calendar.SUNDAY, run.get(Calendar.DAY_OF_WEEK)); assertEquals(15, run.get(Calendar.DAY_OF_MONTH))
        assertEquals(20, run.get(Calendar.HOUR_OF_DAY))
        val sunLate = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 15, 21, 0, 0) }
        assertEquals(22, Calendar.getInstance().apply { timeInMillis = Reports.nextRun(sunLate) }.get(Calendar.DAY_OF_MONTH))
        val sunEarly = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 15, 9, 0, 0) }
        assertEquals(15, Calendar.getInstance().apply { timeInMillis = Reports.nextRun(sunEarly) }.get(Calendar.DAY_OF_MONTH))
    }

    @Test fun partnerPinIsHashed() {
        val h = Partner.hash("4821")
        assertEquals(64, h.length); assertNotEquals("4821", h)
        assertEquals(h, Partner.hash("4821")); assertNotEquals(h, Partner.hash("4822"))
    }
}
