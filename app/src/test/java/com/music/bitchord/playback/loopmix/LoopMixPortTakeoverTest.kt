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
}
