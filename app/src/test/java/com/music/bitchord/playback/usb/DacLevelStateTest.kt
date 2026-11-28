package com.music.bitchord.playback.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The writer's path while a DAC refuses level writes, through the same
 * [DacLevelState] ExclusiveUsbOutput writes and attenuates with: a fake
 * Feature Unit and a fake clock in place of the USB endpoint.
 */
class DacLevelStateTest {
    private var now = 10_000L
    private val writes = mutableListOf<Double>()
    private var accepts = false
    private val state = DacLevelState(setDb = { db -> writes += db; accepts }, nowMs = { now })

    /** What the writer does with one chunk of full-scale samples: attenuate, then send. */
    private fun sendChunk(): Float {
        val chunk = FloatArray(4) { 1f }
        state.attenuate(chunk, chunk.size)
        return chunk[0]
    }

    @Test
    fun twoDecreasesInARowBothReachTheChunksTheWriterSends() {
        // The DAC sits at -6 dB and refuses every write.
        assertEquals(1f, sendChunk(), 0f)
        assertFalse(state.write(level = 0.5f, targetDb = -18.0, dacAtDb = -6.0))
        assertEquals("12 dB down while -18 dB is refused", 0.2512f, sendChunk(), 1e-3f)

        // Half a second later, inside the retry spacing, the listener goes lower still.
        now += 500L
        assertFalse("the same refused level waits out the spacing", state.mayWrite(0.5f))
        assertTrue("a new level is written at once", state.mayWrite(0.3f))
        assertFalse(state.write(level = 0.3f, targetDb = -30.0, dacAtDb = -6.0))
        assertEquals(listOf(-18.0, -30.0), writes)
        assertEquals("24 dB down for the second decrease, not the first's 12", 0.0631f, sendChunk(), 1e-3f)
    }

    @Test
    fun aWriteThatLandsStopsTheAttenuation() {
        assertFalse(state.write(level = 0.5f, targetDb = -18.0, dacAtDb = -6.0))
        now += DacLevelFallback.RETRY_MS
        assertTrue(state.mayWrite(0.5f))
        accepts = true
        assertTrue(state.write(level = 0.5f, targetDb = -18.0, dacAtDb = -6.0))
        assertEquals("the DAC now does the attenuating", 1f, sendChunk(), 0f)
    }

    @Test
    fun aRefusedRaiseLeavesTheChunksAlone() {
        assertFalse(state.write(level = 0.9f, targetDb = -3.0, dacAtDb = -12.0))
        assertEquals(1f, sendChunk(), 0f)
    }

    @Test
    fun aChunkQueuedBeforeTheRefusalIsAttenuatedWhenItIsSent() {
        // Copied into the queue at gain 1 while the hardware level still did the attenuating...
        val queued = FloatArray(4) { 1f }
        // ...then the DAC refuses the next, lower level before the writer gets to it.
        assertFalse(state.write(level = 0.5f, targetDb = -18.0, dacAtDb = -6.0))
        state.attenuate(queued, queued.size)
        assertEquals("a queued chunk played at the DAC's louder level", 0.2512f, queued[0], 1e-3f)
        // A second decrease before the retry: the next queued chunk takes the new difference.
        val queuedNext = FloatArray(4) { 1f }
        now += 500L
        assertTrue(state.mayWrite(0.3f))
        assertFalse(state.write(level = 0.3f, targetDb = -30.0, dacAtDb = -6.0))
        state.attenuate(queuedNext, queuedNext.size)
        assertEquals(0.0631f, queuedNext[0], 1e-3f)
    }
}
