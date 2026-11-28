package com.music.bitchord.playback.smart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a source-backed track's analysis is stored, and when a borrowed head is read. */
class SourceResultRulesTest {

    private val source = "bitchord://source?s=addon1&t=track9"
    private val match = "bitchord://watch?v=abcdefghijk&automix_opus=1"

    @Test
    fun aMatchArrivingDuringAFallbackPassKeepsItsResultOutOfTheStore() {
        // The pass read the source's own audio as a fallback; a match was
        // recorded while it ran, so the fallback marker is gone and the
        // settled address is the match.
        assertFalse(persistableSourceResult(persist = true, analysed = source, settled = match, transientFallback = false))
    }

    @Test
    fun aFallbackResultDuringBackoffIsNotStored() {
        assertFalse(persistableSourceResult(persist = true, analysed = source, settled = null, transientFallback = true))
    }

    @Test
    fun aConfirmedNoMatchIsStillStored() {
        // A search that answered "no match" settles on the source's own audio,
        // which is what was analysed.
        assertTrue(persistableSourceResult(persist = true, analysed = source, settled = source, transientFallback = false))
    }

    @Test
    fun theMatchsOwnResultIsStoredAndAPassThatMayNotBeIsNot() {
        assertTrue(persistableSourceResult(persist = true, analysed = match, settled = match, transientFallback = false))
        assertFalse(persistableSourceResult(persist = false, analysed = match, settled = match, transientFallback = false))
        // A YouTube track: nothing to settle, stored as before.
        assertTrue(persistableSourceResult(persist = true, analysed = match, settled = null, transientFallback = false))
    }

    @Test
    fun aBorrowedHeadWaitsForTheTracksDuration() {
        // The next item's duration is not known yet (it reads as 0).
        assertFalse(borrowedHeadAllowed(analysisUri = match, playingUri = source, durationSeconds = 0.0))
        assertFalse(borrowedHeadAllowed(analysisUri = match, playingUri = source, durationSeconds = Double.NaN))
        // Known: the head pass runs, and checks the copy's length against it.
        assertTrue(borrowedHeadAllowed(analysisUri = match, playingUri = source, durationSeconds = 200.0))
    }

    @Test
    fun theTracksOwnAudioNeedsNoDurationForItsHead() {
        assertTrue(borrowedHeadAllowed(analysisUri = match, playingUri = match, durationSeconds = 0.0))
    }
}
