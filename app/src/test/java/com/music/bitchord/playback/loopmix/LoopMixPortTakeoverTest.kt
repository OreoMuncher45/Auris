package com.music.bitchord.playback.loopmix

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopMixPortTakeoverTest {
    private fun armedPort() = LoopMixPort().apply {
        armOutgoing(LoopMixPort.OutArm(LoopMixRenderer(48_000), takeoverUs = 0L, sampleRate = 48_000, firstFrame = 0L))
    }

    @Test
    fun aTakeoverCommittedBeforeTheDisarmIsWhatTheDisarmReports() {
        val port = armedPort()
        assertTrue(port.commitTakeover(port.armed.generation))
        assertTrue("the song needs the recovery seek", port.disarm())
    }

    @Test
    fun aSinkThatReadTheArmBeforeTheDisarmCannotTakeOverAfterIt() {
        val port = armedPort()
        val read = port.armed.generation // the sink's snapshot, mid-buffer
        assertFalse(port.disarm())
        assertFalse(port.commitTakeover(read))
        assertFalse(port.tookOver)
    }

    @Test
    fun aSinkFinishingABufferForAReplacedArmCannotMarkTheNewOneOver() {
        val port = armedPort()
        val read = port.armed.generation // the sink's snapshot of arm A, mid-buffer
        port.armOutgoing(LoopMixPort.OutArm(LoopMixRenderer(48_000), takeoverUs = 0L, sampleRate = 48_000, firstFrame = 0L))
        assertFalse(port.interrupt(read))
        assertFalse(port.commitTakeover(read))
        assertFalse("arm B read as flushed", port.interrupted)
        assertFalse("arm B read as taken over", port.tookOver)
        // The sink's own arm is still called off.
        assertTrue(port.interrupt(port.armed.generation))
        assertTrue(port.interrupted)
    }

    @Test
    fun anInterruptionStandsUntilTheArmIsReplaced() {
        val port = armedPort()
        assertTrue(port.interrupt(port.armed.generation))
        assertTrue(port.interrupted)
        port.armOutgoing(LoopMixPort.OutArm(LoopMixRenderer(48_000), takeoverUs = 0L, sampleRate = 48_000, firstFrame = 0L))
        assertFalse(port.interrupted)
    }

    @Test
    fun aDisarmReportsTheTakeoverOfTheArmItRemovesNotAnEarlierOnes() {
        val port = armedPort()
        // Arm A commits its takeover.
        assertTrue(port.commitTakeover(port.armed.generation))
        // Arm B replaces it and is disarmed before any sink adopts it.
        port.armOutgoing(LoopMixPort.OutArm(LoopMixRenderer(48_000), takeoverUs = 0L, sampleRate = 48_000, firstFrame = 0L))
        assertFalse("arm B reads as taken over", port.tookOver)
        assertFalse("arm B never committed a takeover", port.disarm())
    }

    @Test
    fun aNewArmStartsUninterrupted() {
        val port = armedPort()
        assertTrue(port.interrupt(port.armed.generation))
        port.armOutgoing(LoopMixPort.OutArm(LoopMixRenderer(48_000), takeoverUs = 0L, sampleRate = 48_000, firstFrame = 0L))
        assertFalse(port.interrupted)
    }
}
