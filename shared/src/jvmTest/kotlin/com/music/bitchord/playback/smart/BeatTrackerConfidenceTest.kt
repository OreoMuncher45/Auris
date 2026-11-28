package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Grid confidence, which the whole transition policy gates on. This
 * intentionally differs from upstream BitChord: an irregular grid with
 * decisive peaks scored 0.6 there, above the beat-match bar, and was
 * phase-locked.
 */
class BeatTrackerConfidenceTest {

    private fun beatsFrom(gaps: List<Double>): List<Double> = gaps.runningFold(10.0) { at, gap -> at + gap }

    private val decisive = List(11) { 10.0 }

    @Test
    fun anIrregularGridWithDecisivePeaksFallsBelowTheBeatMatchBar() {
        // No gap within 10% of the median (0.8 s): nothing about it is a steady beat.
        val irregular = beatsFrom(listOf(0.3, 0.7, 1.1, 0.4, 0.9, 1.5, 0.6, 1.3, 0.35, 1.0))
        val confidence = BeatTracker.gridConfidence(irregular, decisive)
        // Upstream: 0.35 + 0.4 * 0 + 0.25 * ~1 = 0.6.
        assertTrue(confidence < MIN_BEATMATCH_CONFIDENCE, "irregular grid scored $confidence")
    }

    @Test
    fun aRegularGridScoresAsUpstreamDoes() {
        val regular = beatsFrom(List(10) { 0.5 })
        assertEquals(0.95, BeatTracker.gridConfidence(regular, decisive), 1e-9)
    }

    @Test
    fun aGridWithAQuarterOfItsGapsOffIsNotBeatmatched() {
        // Two holes in eight gaps: a grid this broken puts anchors on the wrong beats.
        val holed = beatsFrom(listOf(0.5, 0.5, 1.0, 0.5, 0.5, 0.5, 1.0, 0.5))
        assertTrue(BeatTracker.gridConfidence(holed, decisive) < MIN_BEATMATCH_CONFIDENCE)
    }
}
