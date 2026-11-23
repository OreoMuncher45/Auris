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
}
