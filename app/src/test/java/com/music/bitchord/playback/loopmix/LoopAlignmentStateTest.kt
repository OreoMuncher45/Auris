package com.music.bitchord.playback.loopmix

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The handoff gate after buffering: the Merge Risk "loop mixes hand over misaligned after buffering". */
class LoopAlignmentStateTest {
    private val maxMs = 5.0

    @Test
    fun aConfirmedOffsetWithinTheLimitAllowsTheHandoff() {
        val state = LoopAlignmentState()
        state.record(0.001, confirmed = true)
        assertTrue(state.handoffAllowed(maxMs))
    }

    @Test
    fun aStallAfterConfirmationBlocksTheHandoffUntilMeasuredAgain() {
        val state = LoopAlignmentState()
        state.record(0.0005, confirmed = true)
        // The incoming player buffers before the handoff, then recovers.
        assertTrue(LoopAlignmentState.stallInvalidates(Player.STATE_BUFFERING, loopActive = true, handedOff = false))
        state.invalidate()
        assertFalse("the pre-stall offset must not be trusted", state.handoffAllowed(maxMs))
        // A fresh stall-free round confirms again.
        state.record(0.0008, confirmed = true)
        assertTrue(state.handoffAllowed(maxMs))
    }

    @Test
    fun anOffsetAboutToBeCorrectedNeverPassesTheGate() {
        val state = LoopAlignmentState()
        state.record(0.002, confirmed = false) // a correction follows this round
        assertFalse(state.handoffAllowed(maxMs))
    }

    @Test
    fun anUnmeasuredOrTooLargeOffsetBlocksTheHandoff() {
        val state = LoopAlignmentState()
        assertFalse(state.handoffAllowed(maxMs))
        state.record(0.008, confirmed = true)
        assertFalse(state.handoffAllowed(maxMs))
    }

    @Test
    fun onlyABufferingIncomingPlayerBeforeTheHandoffInvalidates() {
        assertFalse(LoopAlignmentState.stallInvalidates(Player.STATE_READY, loopActive = true, handedOff = false))
        assertFalse(LoopAlignmentState.stallInvalidates(Player.STATE_BUFFERING, loopActive = true, handedOff = true))
        assertFalse(LoopAlignmentState.stallInvalidates(Player.STATE_BUFFERING, loopActive = false, handedOff = false))
    }
}
