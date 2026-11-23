package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals

class TransitionTierTest {
    private fun track(confidence: Double, head: BeatSide? = null) = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY,
        duration = 200.0,
        bpm = 120.0,
        beatInterval = 0.5,
        beatConfidence = confidence,
        headBeats = head,
    )

    private fun head(confidence: Double) = BeatSide(emptyList(), emptyList(), 120.0, confidence)

    @Test
    fun theIncomingSongIsTrustedByItsOpeningGrid() {
        // A shaky song overall whose opening the model measured cleanly can be beat-matched in on it.
        assertEquals(TransitionTier.BEATMATCHED, assessTransitionTier(track(0.9), track(0.1, head(0.9))).tier)
        // And a song trusted overall whose opening is not, cannot.
        assertEquals(TransitionTier.DJ_ASSISTED, assessTransitionTier(track(0.9), track(0.9, head(0.1))).tier)
    }

    @Test
    fun withoutAnOpeningGridTheWholeSongsTrustDecides() {
        assertEquals(TransitionTier.BEATMATCHED, assessTransitionTier(track(0.9), track(0.9)).tier)
    }
}
