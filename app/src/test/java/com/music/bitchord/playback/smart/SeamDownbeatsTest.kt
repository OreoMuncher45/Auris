package com.music.bitchord.playback.smart

import org.junit.Assert.assertEquals
import org.junit.Test

/** One downbeat per bar line where two model windows overlap. */
class SeamDownbeatsTest {

    @Test
    fun twoWindowsEstimatesOfOneBarLineAreOne() {
        // 120 bpm: beats every 0.5 s, bars every 2 s. The windows overlap
        // around 30 s, and each estimated the bar line there, a few
        // milliseconds apart, on opposite sides of the midpoint.
        val beats = (0 until 120).map { it * 0.5 }
        val tolerance = seamTolerance(beats)
        val downbeats = listOf(26.0, 28.0, 29.998, 30.003, 32.0, 34.0)

        assertEquals(listOf(26.0, 28.0, 29.998, 32.0, 34.0), collapseSeam(downbeats, tolerance))
    }

    @Test
    fun distinctBarLinesAreAllKept() {
        val beats = (0 until 40).map { it * 0.5 }
        val downbeats = listOf(0.0, 2.0, 4.0, 6.0)
        assertEquals(downbeats, collapseSeam(downbeats, seamTolerance(beats)))
    }

    @Test
    fun theToleranceIsHalfABeat() {
        assertEquals(0.25, seamTolerance((0 until 20).map { it * 0.5 }), 1e-9)
        // Too few beats to measure a beat: a fixed tenth of a second.
        assertEquals(0.1, seamTolerance(listOf(1.0)), 1e-9)
    }
}
