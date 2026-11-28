package com.music.bitchord.playback.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DacLevelFallbackTest {
    @Test
    fun aRefusedLevelIsTriedAgainASecondLaterNotOnEveryChunk() {
        val at = DacLevelFallback.retryAt(nowMs = 10_000L)
        assertEquals(11_000L, at)
        assertFalse(DacLevelFallback.mayRetry(at, nowMs = 10_500L))
        assertTrue(DacLevelFallback.mayRetry(at, nowMs = 11_000L))
    }
}
