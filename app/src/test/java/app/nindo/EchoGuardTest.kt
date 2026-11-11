package app.nindo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoGuardTest {
    private val said = "Ha, the couch won this round. Happens to every ninja, we take it back tomorrow. How was your Python practice?"

    @Test fun senseisOwnVoiceIsCaught() {
        assertTrue(EchoGuard.isEcho("the couch won this round happens to every ninja", said))
        // Speech recognition of a speaker is rarely perfect.
        assertTrue(EchoGuard.isEcho("ha the couch won this round happens to any ninja we take it back", said))
    }

    @Test fun yourAnswersGetThrough() {
        assertFalse(EchoGuard.isEcho("Python practice was good today", said))
        assertFalse(EchoGuard.isEcho("yeah", said))
        assertFalse(EchoGuard.isEcho("I did forty minutes of loops and it finally clicked", said))
        assertFalse(EchoGuard.isEcho("tomorrow I will take it easy", said))
    }
}
