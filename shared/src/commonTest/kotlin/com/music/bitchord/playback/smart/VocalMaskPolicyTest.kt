package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the policy reads off a vocal mask, and what it does with one that is missing.
 *
 * A mask is a model's estimate on a coarse grid, and the estimate flickers. The
 * policy's threshold is a single number, so without smoothing a sung phrase
 * that sits either side of it is read as a run of unrelated instants — and a
 * clash is decided by whichever frame happened to land highest.
 */
class VocalMaskPolicyTest {

    /** A track whose energy curve is one sample every half second. */
    private fun track(mask: List<Double>, id: String = "a"): TrackAnalysis {
        val energy = mask.indices.map { EnergySample(it * 0.5, 1.0) }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = id,
            duration = mask.size * 0.5,
            bpm = 120.0,
            beatInterval = 0.5,
            beatConfidence = 0.9,
            energyCurve = energy,
            vocalActivityMask = mask,
        )
    }

    /** The same phrase, estimated: every other frame a little under the threshold. */
    private fun flickering(count: Int): List<Double> =
        List(count) { index -> if (index % 2 == 0) 0.58 else 0.64 }

    @Test
    fun smoothingTakesTheFlickerOutOfAPhrase() {
        val raw = flickering(21)
        val smoothed = smoothVocalMask(raw)

        assertEquals(raw.size, smoothed.size)
        // Every sample of a phrase that straddles the threshold now reads as
        // singing, because that is what it is.
        assertTrue(
            smoothed.all { it >= VOCAL_ACTIVE_THRESHOLD },
            "the phrase still flickers: $smoothed",
        )
    }

    @Test
    fun smoothingKeepsTheEdgeWhereItWas() {
        // Ten quiet samples, then ten sung ones.
        val raw = List(10) { 0.1 } + List(10) { 0.9 }
        val smoothed = smoothVocalMask(raw)

        assertEquals(raw.size, smoothed.size)
        // The middle of the edge is the edge: the sample at the boundary is the
        // halfway point, not somewhere else.
        val boundary = smoothed[10]
        assertTrue(boundary in 0.4..0.6, "the edge moved: $boundary")
        assertTrue(smoothed[0] < 0.3, "the quiet side moved: ${smoothed[0]}")
        assertTrue(smoothed.last() > 0.7, "the sung side moved: ${smoothed.last()}")
    }

    @Test
    fun aMaskTooShortToSmoothIsLeftAlone() {
        val raw = listOf(0.1, 0.9)
        assertEquals(raw, smoothVocalMask(raw))
        assertEquals(emptyList(), smoothVocalMask(emptyList()))
    }

    @Test
    fun aFlickeringPhraseIsReadAsSinging() {
        val analysis = track(flickering(40))

        val activity = vocalActivityBetween(analysis, 0.0, 20.0)

        assertTrue(activity != null && activity >= VOCAL_ACTIVE_THRESHOLD, "read as $activity")
        assertTrue(isVocalClash(activity, activity))
    }

    @Test
    fun aQuietTrackIsStillQuiet() {
        val analysis = track(List(40) { 0.1 })

        val activity = vocalActivityBetween(analysis, 0.0, 20.0)

        assertTrue(activity != null && activity < VOCAL_ACTIVE_THRESHOLD, "read as $activity")
        assertTrue(!isVocalClash(activity, activity))
    }

    @Test
    fun noMaskIsNoEvidenceRatherThanNoVocals() {
        val analysis = track(emptyList())

        assertNull(vocalActivityBetween(analysis, 0.0, 20.0))
        // And a clash needs evidence on both sides: one side missing is not a
        // clash, and never a reason to act on the other.
        assertTrue(!isVocalClash(null, 0.9))
        assertTrue(!isVocalClash(0.9, null))
    }

    @Test
    fun aMaskOfADifferentLengthThanTheCurveIsNotRead() {
        val analysis = track(flickering(40)).copy(
            energyCurve = List(20) { EnergySample(it * 0.5, 1.0) },
        )

        assertNull(vocalActivityBetween(analysis, 0.0, 10.0))
    }
}
