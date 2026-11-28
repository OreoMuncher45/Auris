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

    @Test
    fun aRefusedLowerLevelIsMadeUpOnTheSamplesUntilItLands() {
        // The DAC is still at -6 dB, the listener asked for -18 dB: 12 dB down on every chunk sent meanwhile.
        assertEquals(0.2512f, DacLevelFallback.pendingAttenuation(targetDb = -18.0, dacAtDb = -6.0), 1e-3f)
    }

    @Test
    fun aRefusedRaiseOrAnUnknownDacLevelNeedsNoAttenuation() {
        assertEquals(1f, DacLevelFallback.pendingAttenuation(targetDb = -6.0, dacAtDb = -18.0), 0f)
        assertEquals(1f, DacLevelFallback.pendingAttenuation(targetDb = -6.0, dacAtDb = null), 0f)
    }
}
