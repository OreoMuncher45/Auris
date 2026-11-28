package com.music.bitchord.playback.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class ShiftTimelineTest {
    @Test
    fun noShiftsReportsTheDelegatePosition() {
        val t = ShiftTimeline()
        assertEquals(1_000L, t.trackPositionUs(1_000L))
    }

    @Test
    fun dropIsHeardOnlyOnceTheDelegateReachesIt() {
        val t = ShiftTimeline()
        // 30 ms dropped where the delegate's timeline is at 5 s; 300 ms is still buffered ahead.
        t.drop(boundaryUs = 5_000_000L, durationUs = 30_000L)
        assertEquals(-30_000L, t.writtenUs)
        assertEquals(4_700_000L, t.trackPositionUs(4_700_000L))
        assertEquals(5_030_000L, t.trackPositionUs(5_000_000L))
        assertEquals(5_130_000L, t.trackPositionUs(5_100_000L))
    }

    @Test
    fun silenceHoldsTheTrackPositionWhileItPlays() {
        val t = ShiftTimeline()
        t.insertSilence(boundaryUs = 2_000_000L, durationUs = 20_000L)
        t.insertSilence(boundaryUs = 2_020_000L, durationUs = 20_000L)
        assertEquals(40_000L, t.writtenUs)
        assertEquals(1_990_000L, t.trackPositionUs(1_990_000L))
        assertEquals(2_000_000L, t.trackPositionUs(2_010_000L))
        assertEquals(2_000_000L, t.trackPositionUs(2_039_000L))
        assertEquals(2_010_000L, t.trackPositionUs(2_050_000L))
    }

    @Test
    fun dropSpreadOverBuffersIsOneShift() {
        val t = ShiftTimeline()
        t.drop(1_000_000L, 10_000L)
        t.drop(1_000_000L, 15_000L)
        assertEquals(-25_000L, t.writtenUs)
        assertEquals(1_025_000L, t.trackPositionUs(1_000_000L))
    }

    @Test
    fun overflowAppliesTheOldestShiftEarly() {
        val t = ShiftTimeline(capacity = 2)
        t.drop(1_000_000L, 1_000L)
        t.drop(2_000_000L, 1_000L)
        t.drop(3_000_000L, 1_000L)
        assertEquals(500_000L + 1_000L, t.trackPositionUs(500_000L))
        assertEquals(3_000_000L + 3_000L, t.trackPositionUs(3_000_000L))
    }

    @Test
    fun resetForgetsEverything() {
        val t = ShiftTimeline()
        t.drop(1_000L, 500L)
        t.reset()
        assertEquals(0L, t.writtenUs)
        assertEquals(2_000L, t.trackPositionUs(2_000L))
    }

    @Test
    fun aDropSpreadOverBuffersMergesDespiteMicrosecondRounding() {
        val t = ShiftTimeline(capacity = 2)
        // Each input buffer's boundary is rounded on its own: a microsecond apart.
        t.drop(1_000_000L, 10_000L)
        t.drop(1_000_001L, 10_000L)
        t.drop(999_999L, 10_000L)
        assertEquals(-30_000L, t.writtenUs)
        // One shift, so nothing was pushed out early.
        assertEquals(500_000L, t.trackPositionUs(500_000L))
        assertEquals(1_030_000L, t.trackPositionUs(1_000_000L))
    }

    @Test
    fun at192kHzADropOneWrittenFrameLaterIsASeparateShift() {
        val t = ShiftTimeline()
        t.setSampleRate(192_000)
        // A 1 ms drop at 0, one frame (5.2 µs) written, then another 1 ms drop.
        t.drop(0L, 1_000L)
        t.drop(5L, 1_000L)
        // Separate shifts: the first is heard from 0, the second only from 5 µs.
        assertEquals(1_000L, t.trackPositionUs(0L))
        assertEquals(2_005L, t.trackPositionUs(5L))
    }

    @Test
    fun theSteadySpanEndsAtTheNextShiftAndIsZeroInsideSilence() {
        val t = ShiftTimeline()
        assertEquals(Long.MAX_VALUE, t.steadyUs(1_000_000L))
        t.insertSilence(boundaryUs = 2_000_000L, durationUs = 40_000L)
        t.trackPositionUs(1_980_000L)
        assertEquals(20_000L, t.steadyUs(1_980_000L))
        t.trackPositionUs(2_010_000L)
        assertEquals(0L, t.steadyUs(2_010_000L))
        // Past the silence: nothing ahead.
        t.trackPositionUs(2_050_000L)
        assertEquals(Long.MAX_VALUE, t.steadyUs(2_050_000L))
    }
}
