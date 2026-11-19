package com.music.bitchord

import com.music.bitchord.playback.UpgradeFadeAlignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The quality-upgrade blend joins two different files of the same song, and
 * its positions only line up if every caller agrees on the direction of the
 * measured shift: target time = source time + shift. These tests pin that
 * convention and the resume-latency correction that keeps a seek from
 * re-entering the blend a fixed number of milliseconds behind.
 */
class UpgradeFadeAlignmentTest {

    @Test
    fun `incoming position is the source position plus the measured shift`() {
        assertEquals(10_250L, UpgradeFadeAlignment.incomingPositionMs(10_000L, 250L))
        // A cut whose opening is longer sits later in its own file.
        assertEquals(8_500L, UpgradeFadeAlignment.incomingPositionMs(10_000L, -1_500L))
    }

    @Test
    fun `incoming position never runs off either end of the recording`() {
        assertEquals(0L, UpgradeFadeAlignment.incomingPositionMs(200L, -5_000L))
        assertEquals(180_000L, UpgradeFadeAlignment.incomingPositionMs(179_500L, 3_000L, 180_000L))
    }

    @Test
    fun `drift is positive when the incoming copy is behind`() {
        assertEquals(120L, UpgradeFadeAlignment.driftMs(12_000L, 12_130L, 250L))
        assertEquals(-80L, UpgradeFadeAlignment.driftMs(12_000L, 12_330L, 250L))
        assertEquals(0L, UpgradeFadeAlignment.driftMs(12_000L, 12_250L, 250L))
    }

    @Test
    fun `correction aims the previous resume latency ahead`() {
        // Nothing known yet: aim straight at the live position.
        assertEquals(12_250L, UpgradeFadeAlignment.correctionPositionMs(12_000L, 250L, 0L))
        // A measured 90ms resume: aim that far ahead so the seek lands level.
        assertEquals(12_340L, UpgradeFadeAlignment.correctionPositionMs(12_000L, 250L, 90L))
        // An overshoot must not aim backwards.
        assertEquals(12_250L, UpgradeFadeAlignment.correctionPositionMs(12_000L, 250L, -90L))
        // And a wild reading is capped rather than trusted.
        assertEquals(
            12_650L,
            UpgradeFadeAlignment.correctionPositionMs(
                12_000L,
                250L,
                10_000L,
            ),
        )
    }

    @Test
    fun `accumulated residual converges on a fixed resume latency`() {
        val latency = 90L
        val trace = simulate(latency, startDriftMs = -300L)

        assertTrue("did not settle: $trace", trace.last().absoluteValue <= UpgradeFadeAlignment.FINE_DRIFT_MS)
        assertTrue("took too many corrections: $trace", trace.size <= UpgradeFadeAlignment.MAX_CORRECTIONS)
    }

    @Test
    fun `converges when the resume latency varies between seeks`() {
        val trace = simulate(listOf(90L, 120L, 75L, 75L), startDriftMs = 180L)

        assertTrue("did not settle under jitter: $trace", trace.last().absoluteValue <= UpgradeFadeAlignment.FINE_DRIFT_MS)
        assertTrue("took too many corrections: $trace", trace.size <= UpgradeFadeAlignment.MAX_CORRECTIONS)
    }

    /**
     * Models what [com.music.bitchord.playback.PlaybackService]'s alignment
     * loop does: measure, aim with the accumulated latency, let the seek take
     * its resume time, then measure again. Returns the drift after each seek.
     */
    private fun simulate(resumeLatencyMs: Long, startDriftMs: Long): List<Long> =
        simulate(List(UpgradeFadeAlignment.MAX_CORRECTIONS) { resumeLatencyMs }, startDriftMs)

    private fun simulate(resumeLatencies: List<Long>, startDriftMs: Long): List<Long> {
        var live = 60_000L
        var standby = live + startDriftMs
        var accumulatedLatency = 0L
        val trace = ArrayList<Long>()
        for (resumeLatency in resumeLatencies) {
            val drift = UpgradeFadeAlignment.driftMs(live, standby, 0L)
            if (drift.absoluteValue <= UpgradeFadeAlignment.FINE_DRIFT_MS) break
            val target = UpgradeFadeAlignment.correctionPositionMs(live, 0L, accumulatedLatency)
            live += resumeLatency
            standby = target
            val residual = UpgradeFadeAlignment.driftMs(live, standby, 0L)
            trace += residual
            accumulatedLatency = (accumulatedLatency + residual)
                .coerceIn(0L, UpgradeFadeAlignment.MAX_RESUME_LATENCY_MS)
            if (residual.absoluteValue <= UpgradeFadeAlignment.FINE_DRIFT_MS) break
        }
        return trace.ifEmpty { listOf(UpgradeFadeAlignment.driftMs(live, standby, 0L)) }
    }

    private val Long.absoluteValue: Long
        get() = if (this < 0) -this else this
}
