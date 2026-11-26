package com.music.bitchord.ui.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BestPartHeardTest {
    @Test
    fun hearingATracksBestPartDoesNotHideItsPillTheNextTimeItPlays() {
        bestPartTrackChanged("best-a")
        markBestPartHeard("best-a")
        assertTrue(isBestPartHeard("best-a"))
        // A track with no best part of its own never writes the value.
        bestPartTrackChanged("best-b")
        bestPartTrackChanged("best-a")
        assertFalse(isBestPartHeard("best-a"))
    }

    @Test
    fun theSameTrackStaysHeardWhileItPlays() {
        bestPartTrackChanged("best-c")
        markBestPartHeard("best-c")
        bestPartTrackChanged("best-c")
        assertTrue(isBestPartHeard("best-c"))
    }

    @Test
    fun aLockedPartyHidesThePillWithoutMarkingItHeard() {
        bestPartTrackChanged("best-d")
        assertFalse(bestPartOffered(found = true, ahead = true, heard = isBestPartHeard("best-d"), mixing = false, locked = true))
        assertFalse(isBestPartHeard("best-d"))
    }

    @Test
    fun liftingTheLockOffersItAgainWhileStillAhead() {
        bestPartTrackChanged("best-e")
        val heard = isBestPartHeard("best-e")
        assertFalse(bestPartOffered(found = true, ahead = true, heard = heard, mixing = false, locked = true))
        assertTrue(bestPartOffered(found = true, ahead = true, heard = heard, mixing = false, locked = false))
        // Played through while locked: heard, and gone for the rest of the track.
        markBestPartHeard("best-e")
        assertFalse(bestPartOffered(found = true, ahead = true, heard = isBestPartHeard("best-e"), mixing = false, locked = false))
    }
}
