package com.music.bitchord.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

class SeekOffsetTest {
    @Test
    fun aSkipIsMeasuredFromThePositionGivenAndKeptWithinTheSong() {
        // During a loop mix the decoder sits at the loop start (say 180 s) while
        // the listener hears 196 s: a 10 s skip back from what they hear is 186 s.
        assertEquals(186_000L, SeekOffset.target(196_000L, -10_000L, 240_000L))
        assertEquals(0L, SeekOffset.target(4_000L, -10_000L, 240_000L))
        assertEquals(240_000L, SeekOffset.target(235_000L, 15_000L, 240_000L))
        assertEquals(250_000L, SeekOffset.target(235_000L, 15_000L, C.TIME_UNSET))
    }
}
