package com.music.bitchord.playback.loopmix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoopMixPortPositionTest {

    @Test
    fun aReadingIsExtrapolatedFromItsOwnTime() {
        val port = LoopMixPort()
        assertNull(port.presentedSeconds(0L, playing = true))

        port.publishPosition(2_000_000L, 1_000_000_000L)

        assertEquals(2.5, port.presentedSeconds(1_500_000_000L, playing = true)!!, 1e-9)
        assertEquals(2.0, port.presentedSeconds(1_500_000_000L, playing = false)!!, 1e-9)
    }

    @Test
    fun aReadingFromAnEarlierArmIsNotUsed() {
        val port = LoopMixPort()
        port.armIncoming(LoopMixPort.InArm(handoverUs = 10_000_000L))
        port.publishPosition(2_000_000L, 1_000_000_000L)
        assertEquals(2.0, port.presentedSeconds(1_000_000_000L, playing = true)!!, 1e-9)

        port.disarm()
        port.armIncoming(LoopMixPort.InArm(handoverUs = 20_000_000L))
        assertNull(port.presentedSeconds(9_000_000_000L, playing = true))

        port.publishPosition(3_000_000L, 9_000_000_000L)
        assertEquals(3.0, port.presentedSeconds(9_000_000_000L, playing = true)!!, 1e-9)
    }
}
