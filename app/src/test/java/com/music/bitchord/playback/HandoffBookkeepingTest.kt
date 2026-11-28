package com.music.bitchord.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffBookkeepingTest {
    @Test
    fun anOrdinaryHandoffBooksTheIncomingTrack() {
        assertTrue(HandoffBookkeeping.booksTrackChange(late = false))
    }

    @Test
    fun aLateHandoffLeavesTheNaturalAdvanceAsTheOneBooking() {
        assertFalse(HandoffBookkeeping.booksTrackChange(late = true))
    }

    @Test
    fun aLateHandoffGoesOnlyToAWorkingStandbyWhilePlaybackRuns() {
        assertTrue(HandoffBookkeeping.lateHandoffAllowed(standbyFailed = false, standbyIdle = false, advancedPlayerPlaying = true))
    }

    @Test
    fun aFailedOrIdleStandbyLeavesTheNaturalAdvance() {
        assertFalse(HandoffBookkeeping.lateHandoffAllowed(standbyFailed = true, standbyIdle = false, advancedPlayerPlaying = true))
        assertFalse(HandoffBookkeeping.lateHandoffAllowed(standbyFailed = false, standbyIdle = true, advancedPlayerPlaying = true))
    }

    @Test
    fun anAdvanceTheSleepTimerPausedIsNotHandedToAPlayingStandby() {
        assertFalse(HandoffBookkeeping.lateHandoffAllowed(standbyFailed = false, standbyIdle = false, advancedPlayerPlaying = false))
    }

    @Test
    fun aRejectedAdvanceKeepsThePlayerItsSkipWasPostedOn() {
        // Strict mode blocked the track the outgoing player advanced to: its
        // posted skip must act on the session, so no handoff.
        assertFalse(HandoffBookkeeping.lateHandoffAllowed(
            standbyFailed = false, standbyIdle = false, advancedPlayerPlaying = true, advanceRejected = true,
        ))
    }
}
