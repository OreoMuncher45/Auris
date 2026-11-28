package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepFadeTest {
    @Test
    fun aTimerSetBeforeTheHandoffDropsTheBlend() {
        // The outgoing track is still the session: it is the song meant.
        assertTrue(SleepFade.dropsBlend(sleepAfterTrack = true, handedOff = false))
    }

    @Test
    fun aTimerSetAfterTheHandoffLetsTheBlendFinish() {
        // The incoming track is the session now, and "this song" itself.
        assertFalse(SleepFade.dropsBlend(sleepAfterTrack = true, handedOff = true))
    }

    @Test
    fun noTimerLeavesTheBlendAlone() {
        assertFalse(SleepFade.dropsBlend(sleepAfterTrack = false, handedOff = false))
        assertFalse(SleepFade.dropsBlend(sleepAfterTrack = false, handedOff = true))
    }

    @Test
    fun theFadeOutKeepsTheTransitionsSpanWhenTheTrackHasRoom() {
        assertEquals(6_000L, SleepFade.span(fadeMs = 6_000L, timeLeftMs = 9_000L))
    }

    @Test
    fun theFadeOutShrinksToReachSilenceByTheTracksEnd() {
        // A blend dropped 3 s from the end: a 6 s fade would be cut by the end
        // halfway down.
        assertEquals(3_000L, SleepFade.span(fadeMs = 6_000L, timeLeftMs = 3_000L))
    }

    @Test
    fun theFadeOutNeverHasAZeroOrNegativeSpan() {
        assertEquals(1L, SleepFade.span(fadeMs = 6_000L, timeLeftMs = 0L))
        assertEquals(1L, SleepFade.span(fadeMs = 6_000L, timeLeftMs = -250L))
    }
}
