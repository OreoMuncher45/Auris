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
}
