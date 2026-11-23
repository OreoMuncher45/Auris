package com.music.bitchord.playback.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VocalSpanTest {

    private val rate = 100.0

    @Test
    fun aDecodeThatStartsEarlySkipsItsPrerollBeforeTheCap() {
        // Asked for 10 s, the extractor started at 9.5 s; the model takes 5 s.
        val (from, count) = vocalSpan(size = 1_000, sampleRate = rate, actualStart = 9.5, requestedStart = 10.0, maxSeconds = 5.0)!!
        assertEquals(50, from)
        assertEquals(500, count)
    }

    @Test
    fun aDecodeThatStartsOnTimeIsReadFromItsFirstSample() {
        assertEquals(0 to 500, vocalSpan(1_000, rate, actualStart = 10.0, requestedStart = 10.0, maxSeconds = 5.0))
        assertEquals(0 to 300, vocalSpan(300, rate, actualStart = 10.0, requestedStart = 10.0, maxSeconds = 5.0))
    }

    @Test
    fun aDecodeThatIsAllPrerollHasNothingForTheModel() {
        assertNull(vocalSpan(40, rate, actualStart = 9.5, requestedStart = 10.0, maxSeconds = 5.0))
    }
}
