package app.siteguard

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EngineTest {
    private fun site(u: String) = UrlUtil.normalise(u)!!
    private val MON = 0; private val SAT = 5; private val SUN = 6

    @Test fun domainAndKeywordMatching() {
        val d = Rule(target = Target.DOMAIN, value = "youtube.com")
        site("https://m.youtube.com/watch?v=1").let { assertTrue(d.matchesSite(it.first, it.second)) }
        site("notyoutube.com/x").let { assertFalse(d.matchesSite(it.first, it.second)) }
        val k = Rule(target = Target.KEYWORD, value = "casino")
        site("example.com/best-CASINO").let { assertTrue(k.matchesSite(it.first, it.second)) }
        assertNull(UrlUtil.normalise("how to cook rice"))
    }

    @Test fun noLimitsMeansFullBlock() {
        val r = Rule(target = Target.APP, value = "x")
        assertTrue(r.conditionsMet(600, MON, null))
        assertTrue(r.limitHit(0, 0))
    }

    @Test fun timeAndOpenLimits() {
        val r = Rule(target = Target.APP, value = "x", limitMinutes = 30, launchLimit = 10)
        assertFalse(r.limitHit(29 * 60_000L, 10))   // 10th open still allowed
        assertTrue(r.limitHit(30 * 60_000L, 1))     // time used up
        assertTrue(r.limitHit(0, 11))               // 11th open blocked
    }

    @Test fun overnightScheduleUsesStartDay() {
        // 22:00–07:00 on weekdays only.
        val r = Rule(target = Target.APP, value = "x", scheduleOn = true, startMin = 22 * 60, endMin = 7 * 60, days = 0b0011111)
        assertTrue(r.conditionsMet(23 * 60, MON, null))
        assertFalse(r.conditionsMet(12 * 60, MON, null))
        assertFalse(r.conditionsMet(23 * 60, SAT, null))   // Saturday night not covered
        assertTrue(r.conditionsMet(2 * 60, SAT, null))     // early Saturday = Friday night
        assertFalse(r.conditionsMet(2 * 60, MON, null))    // early Monday = Sunday night
    }

    @Test fun placeConditions() {
        val at = Rule(target = Target.APP, value = "x", placeId = "p", placeInside = true)
        val away = at.copy(placeInside = false)
        assertTrue(at.conditionsMet(0, SUN, true)); assertFalse(at.conditionsMet(0, SUN, false))
        assertTrue(away.conditionsMet(0, SUN, false)); assertFalse(away.conditionsMet(0, SUN, true))
        assertFalse(at.conditionsMet(0, SUN, null))         // unknown location: rule not in force
    }

    @Test fun jsonRoundTripAndV1Migration() {
        val r = Rule(target = Target.APP, value = "com.instagram.android", label = "Instagram", limitMinutes = 20, placeId = "p1")
        assertEquals(r, Rule.fromJson(JSONObject(r.toJson().toString())))
        val v1 = JSONObject("""{"id":"a","pattern":"reddit.com","matchType":"DOMAIN","mode":"DAILY_LIMIT","limitMinutes":15,"enabled":true}""")
        val m = Rule.fromV1(v1)
        assertEquals(Target.DOMAIN, m.target); assertEquals("reddit.com", m.value); assertEquals(15, m.limitMinutes); assertFalse(m.scheduleOn)
    }
}
