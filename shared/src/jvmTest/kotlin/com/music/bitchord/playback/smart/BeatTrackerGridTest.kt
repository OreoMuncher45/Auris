package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two questions a beat grid has to answer honestly: how fast is it, and how much can it be
 * trusted?
 *
 * The first is the octave problem. The model's beat head and its downbeat head are predicted
 * independently, so a grid can land on the right beats at the wrong *rate* — every other beat of
 * a 140 BPM track, or twice as many beats as a 90 BPM track has. Both put every bar line in the
 * wrong place, and a blend anchored to it lands off the beat.
 *
 * The second is confidence. It gates every beat-matched transition in the app, so a grid that
 * wanders must not score like a metronome, and a grid that was never really found must not score
 * at all.
 */
class BeatTrackerGridTest {

    /** Beats every [interval] seconds, [count] of them. */
    private fun beats(interval: Double, count: Int): List<Double> =
        List(count) { index -> index * interval }

    /** The downbeats of a grid whose bars are every [bars] beats. */
    private fun downbeats(interval: Double, count: Int, bars: Int): List<Double> =
        List(count) { index -> index * bars * interval }

    /**
     * A grid that is right: the downbeats are four beats apart and nothing is changed.
     */
    @Test
    fun `a correct grid is left alone`() {
        val grid = beats(0.5, 32)
        val bars = downbeats(0.5, 8, bars = 4)

        val meter = BeatTracker.reconcileMeter(grid, bars, bpm = 120.0)

        assertFalse(meter.corrected)
        assertEquals(120.0, meter.bpm, 0.001)
        assertEquals(grid, meter.beats)
        assertEquals(bars, meter.downbeats)
    }

    /**
     * Half the true rate: the beat head found every other beat of a 140 BPM track, so it reports
     * 70. The downbeats — the real bars — are two reported beats apart, which is what gives the
     * rate away.
     */
    @Test
    fun `a half-time grid is put back together`() {
        val reported = beats(60.0 / 70.0, 24)
        val bars = downbeats(60.0 / 70.0, 6, bars = 2)

        val meter = BeatTracker.reconcileMeter(reported, bars, bpm = 70.0)

        assertTrue(meter.corrected)
        assertEquals(140.0, meter.bpm, 0.001)
        // The missing beats are back, between the ones that were found.
        assertEquals(reported.size * 2 - 1, meter.beats.size)
        assertEquals(140.0, 60.0 / (meter.beats[1] - meter.beats[0]), 0.01)
        // The bars are still the real bars.
        assertEquals(bars, meter.downbeats)
    }

    /**
     * Double the true rate: the beat head found a beat between every pair of real ones, so it
     * reports 180 for a 90 BPM track, and the downbeats are eight reported beats apart.
     */
    @Test
    fun `a double-time grid is halved`() {
        val reported = beats(60.0 / 180.0, 64)
        val bars = downbeats(60.0 / 180.0, 8, bars = 8)

        val meter = BeatTracker.reconcileMeter(reported, bars, bpm = 180.0)

        assertTrue(meter.corrected)
        assertEquals(90.0, meter.bpm, 0.001)
        assertEquals(reported.size / 2, meter.beats.size)
        // Every bar is still on a beat of the grid that is left.
        for (bar in meter.downbeats) {
            val nearest = meter.beats.minByOrNull { kotlin.math.abs(it - bar) }
            assertTrue(nearest != null && kotlin.math.abs(nearest - bar) < 0.01)
        }
    }

    /**
     * Two beats between downbeats in the ordinary tempo range is a metre of its own — a track in
     * 2/4 — and not a halved grid. Nothing is done to it.
     */
    @Test
    fun `a two-beat meter in the ordinary range is left alone`() {
        val grid = beats(0.5, 32)
        val bars = downbeats(0.5, 16, bars = 2)

        val meter = BeatTracker.reconcileMeter(grid, bars, bpm = 120.0)

        assertFalse(meter.corrected)
        assertEquals(120.0, meter.bpm, 0.001)
        assertEquals(grid, meter.beats)
    }

    /** A downbeat spacing that is not a whole number of beats is not a metre this rule can read. */
    @Test
    fun `an unreadable downbeat spacing is left alone`() {
        val grid = beats(0.5, 32)
        val bars = listOf(0.0, 1.75, 3.5, 5.25, 7.0)

        val meter = BeatTracker.reconcileMeter(grid, bars, bpm = 120.0)

        assertFalse(meter.corrected)
        assertEquals(120.0, meter.bpm, 0.001)
    }

    /** A correction that would put the tempo outside the plausible range is not made. */
    @Test
    fun `a correction into an impossible tempo is refused`() {
        // Half-time at 115 BPM would correct to 230, which is not a tempo anybody plays.
        val reported = beats(60.0 / 115.0, 24)
        val bars = downbeats(60.0 / 115.0, 6, bars = 2)

        val meter = BeatTracker.reconcileMeter(reported, bars, bpm = 115.0)

        assertFalse(meter.corrected)
        assertEquals(115.0, meter.bpm, 0.001)
    }

    /** A steady grid is trusted; a wandering one is not, however decisive its peaks look. */
    @Test
    fun `confidence falls as the spacing wanders`() {
        val steady = beats(0.5, 32)
        val wandering = List(32) { index -> index * 0.5 + if (index % 2 == 0) 0.06 else -0.06 }
        val decisive = List(32) { 4.0 }

        val steadyConfidence = BeatTracker.gridConfidence(steady, decisive)
        val wanderingConfidence = BeatTracker.gridConfidence(wandering, decisive)

        assertTrue(steadyConfidence > 0.8, "a metronome scored ${steadyConfidence}")
        assertTrue(
            wanderingConfidence < steadyConfidence - 0.1,
            "a wandering grid scored $wanderingConfidence against $steadyConfidence",
        )
    }

    /** Decisive peaks cannot rescue a grid that does not hold together. */
    @Test
    fun `decisive peaks do not carry a wandering grid`() {
        val wandering = List(32) { index -> index * 0.5 + if (index % 2 == 0) 0.12 else -0.12 }
        val decisive = List(32) { 8.0 }

        val confidence = BeatTracker.gridConfidence(wandering, decisive)

        assertTrue(confidence < 0.55, "a wandering grid with strong peaks scored $confidence")
    }

    /** Too few beats is no grid at all. */
    @Test
    fun `a grid with too few beats has no confidence`() {
        assertEquals(0.0, BeatTracker.gridConfidence(beats(0.5, 4), List(4) { 4.0 }), 0.0001)
        assertEquals(0.0, BeatTracker.gridConfidence(emptyList(), emptyList()), 0.0001)
    }
}
