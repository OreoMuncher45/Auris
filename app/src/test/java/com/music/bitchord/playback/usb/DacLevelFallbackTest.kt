package com.music.bitchord.playback.usb

import org.junit.Assert.assertEquals
import org.junit.Test

class DacLevelFallbackTest {
    @Test
    fun aFailedLevelWritePutsTheDacBackToItsBaseBeforeSoftwareGain() {
        val writes = mutableListOf<Double>()
        val outcome = DacLevelFallback.afterFailedWrite(baseDb = -6.0) { writes += it; true }
        assertEquals(DacLevelFallback.Outcome.SOFTWARE_FROM_BASE, outcome)
        assertEquals(listOf(-6.0), writes)
    }

    @Test
    fun aDacThatTakesNoWriteKeepsTheLevelPending() {
        val outcome = DacLevelFallback.afterFailedWrite(baseDb = -6.0) { false }
        assertEquals(DacLevelFallback.Outcome.RETRY_HARDWARE, outcome)
    }
}
