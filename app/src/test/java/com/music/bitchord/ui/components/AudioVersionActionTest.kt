package com.music.bitchord.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioVersionActionTest {

    private fun offered(
        fromPlayer: Boolean = true,
        inParty: Boolean = false,
        audio: Boolean = true,
        alternate: Boolean = false,
        switching: Boolean = false,
        current: Boolean = true,
    ) = shouldOfferAudioVersionToggle(
        fromPlayer = fromPlayer,
        inParty = inParty,
        isAudioVersion = audio,
        hasAlternateVersion = alternate,
        switching = switching,
        isCurrentSong = current,
    )

    @Test
    fun everyCurrentAudioTrackOffersVideoWithoutAPrecomputedMatch() {
        assertTrue(offered(audio = true, alternate = false))
    }

    @Test
    fun videoToAudioStillRequiresAKnownWayBack() {
        assertFalse(offered(audio = false, alternate = false))
        assertTrue(offered(audio = false, alternate = true))
    }

    @Test
    fun onlyTheAvailableCurrentPlayerActionIsOffered() {
        assertFalse(offered(fromPlayer = false))
        assertFalse(offered(current = false))
        assertFalse(offered(switching = true))
        assertFalse(offered(inParty = true))
    }
}
