package com.music.bitchord.playback.loopmix

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopClockWaitTest {
    @Test
    fun anArmThatNeverGetsAClockGivesUpAfterTheTimeout() {
        val wait = LoopClockWait(timeoutMs = 3_000L)
        wait.start(nowMs = 10_000L)
        assertFalse(wait.expired(null, nowMs = 12_000L))
        assertTrue(wait.expired(null, nowMs = 13_001L))
    }

    @Test
    fun eachReadingRestartsTheWait() {
        val wait = LoopClockWait(timeoutMs = 3_000L)
        wait.start(nowMs = 0L)
        assertFalse(wait.expired(-0.5, nowMs = 2_900L))
        assertFalse(wait.expired(null, nowMs = 5_800L))
        assertTrue(wait.expired(null, nowMs = 5_901L))
    }
}
