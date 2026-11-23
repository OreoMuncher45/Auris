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
        assertEquals(TransitionTier.DJ_ASSISTED, assessTransitionTier(track(0.9), track(0.1)).tier)
    }

    @Test
    fun aTrustedOpeningAtAnotherTempoIsNotBeatmatched() {
        // Trusted 100 BPM opening, 120 BPM song-wide: the stretch would go to 120.
        val next = track(0.9).copy(headBeats = BeatSide(emptyList(), emptyList(), 100.0, 0.9))
        assertEquals(TransitionTier.DJ_ASSISTED, assessTransitionTier(track(0.9), next).tier)
        // The same tempo, or its double, is the same grid.
        val same = track(0.9).copy(headBeats = BeatSide(emptyList(), emptyList(), 240.0, 0.9))
        assertEquals(TransitionTier.BEATMATCHED, assessTransitionTier(track(0.9), same).tier)
    }
}
