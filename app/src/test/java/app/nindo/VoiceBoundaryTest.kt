package app.nindo

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceBoundaryTest {
    private fun cut(s: String, first: Boolean) = SpeechChunker.boundary(s, first).let { if (it == 0) "" else s.substring(0, it) }

    @Test fun firstPieceCanEndAtACommaOnceItHasSomeWords() {
        assertEquals("Ha, the couch won this round,", cut("Ha, the couch won this round, happens to every", true))
        assertEquals("", cut("Ha, the couch", true))
    }

    @Test fun laterPiecesWaitForTheSentence() {
        assertEquals("", cut("Ha, the couch won this round, happens to every", false))
        assertEquals("Happens to every ninja.", cut("Happens to every ninja. We take it", false))
    }

    @Test fun sentenceEndBeatsCommaWhenBothAreThere() {
        assertEquals("Oof, one of those.", cut("Oof, one of those. Did it", true))
    }
}
