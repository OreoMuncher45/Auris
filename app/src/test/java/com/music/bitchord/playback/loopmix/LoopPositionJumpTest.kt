package com.music.bitchord.playback.loopmix

import org.junit.Assert.assertEquals
import org.junit.Test

class LoopPositionJumpTest {
    private fun classify(previous: Long, previousIncoming: Boolean, position: Long, incoming: Boolean) =
        LoopPositionJump.classify(previous, previousIncoming, position, incoming, wrapMs = 500L)

    @Test
    fun aJumpBackOnTheSameDeckIsAWrap() {
        assertEquals(LoopPositionJump.WRAP, classify(172_000L, false, 164_000L, false))
    }

    @Test
    fun theHandoffOntoTheIncomingSongIsADeckChangeNotAWrap() {
        // 170 s on the outgoing song, 15 s on the incoming one.
        assertEquals(LoopPositionJump.DECK_CHANGE, classify(170_000L, false, 15_000L, true))
    }

    @Test
    fun aWrapOnTheIncomingDeckIsMeasuredAgainstItsOwnReading() {
        assertEquals(LoopPositionJump.NONE, classify(15_000L, true, 15_200L, true))
        assertEquals(LoopPositionJump.WRAP, classify(23_000L, true, 15_000L, true))
    }

    @Test
    fun noBaselineMeansNoJump() {
        assertEquals(LoopPositionJump.NONE, classify(-1L, false, 10_000L, true))
    }
}
