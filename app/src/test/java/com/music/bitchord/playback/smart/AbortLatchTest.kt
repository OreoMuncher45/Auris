package com.music.bitchord.playback.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AbortLatchTest {

    @Test
    fun anAbortStaysLatchedAfterItsConditionClears() {
        var stopped = false
        val latch = AbortLatch { stopped }
        assertFalse(latch.poll())
        assertFalse(latch.tripped)

        stopped = true
        assertTrue(latch.poll())
        // The player hands the codec back: the condition clears, the abort does not.
        stopped = false
        assertTrue(latch.tripped)
        assertTrue(latch.poll())
    }

    @Test
    fun aTrippedLatchStopsAskingItsCondition() {
        var asked = 0
        val latch = AbortLatch { asked++; true }
        latch.poll()
        latch.poll()
        assertEquals(1, asked)
    }
}
