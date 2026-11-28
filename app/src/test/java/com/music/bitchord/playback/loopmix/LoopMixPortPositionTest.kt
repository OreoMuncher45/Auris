package com.music.bitchord.playback.loopmix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoopMixPortPositionTest {

    @Test
    fun aReadingIsExtrapolatedFromItsOwnTime() {
        val port = LoopMixPort()
        assertNull(port.presentedSeconds(0L, playing = true))

        port.publishPosition(2_000_000L, 1_000_000_000L, port.generation)

        assertEquals(2.5, port.presentedSeconds(1_500_000_000L, playing = true)!!, 1e-9)
        assertEquals(2.0, port.presentedSeconds(1_500_000_000L, playing = false)!!, 1e-9)
    }

    @Test
    fun aReadingFromAnEarlierArmIsNotUsed() {
        val port = LoopMixPort()
        port.armIncoming(LoopMixPort.InArm(handoverUs = 10_000_000L))
        port.publishPosition(2_000_000L, 1_000_000_000L, port.generation)
        assertEquals(2.0, port.presentedSeconds(1_000_000_000L, playing = true)!!, 1e-9)

        port.disarm()
        port.armIncoming(LoopMixPort.InArm(handoverUs = 20_000_000L))
        assertNull(port.presentedSeconds(9_000_000_000L, playing = true))

        port.publishPosition(3_000_000L, 9_000_000_000L, port.generation)
        assertEquals(3.0, port.presentedSeconds(9_000_000_000L, playing = true)!!, 1e-9)
    }

    @Test
    fun aReadingTakenBeforeAnArmIsNotRelabelled() {
        val port = LoopMixPort()
        port.armIncoming(LoopMixPort.InArm(handoverUs = 10_000_000L))
        // The sink reads the generation, then its clock; the controller arms in between.
        val takenUnder = port.generation
        port.disarm()
        port.armIncoming(LoopMixPort.InArm(handoverUs = 20_000_000L))
        port.publishPosition(2_000_000L, 1_000_000_000L, takenUnder)
        assertNull(port.presentedSeconds(1_000_000_000L, playing = true))
    }

    @Test
    fun theArmAndItsGenerationAreOneSnapshot() {
        val port = LoopMixPort()
        val before = port.armed
        port.armIncoming(LoopMixPort.InArm(handoverUs = 10_000_000L))
        val after = port.armed
        org.junit.Assert.assertNotSame(before, after)
        assertEquals(before.generation + 1, after.generation)
        assertEquals(10_000_000L, after.inn!!.handoverUs)
        assertNull(after.out)
        port.disarm()
        assertNull(port.armed.inn)
        assertEquals(after.generation + 1, port.generation)
    }

    @Test
    fun aShiftStaysWithTheArmThatAskedForIt() {
        val port = LoopMixPort()
        val a = LoopMixPort.InArm(handoverUs = 10_000_000L)
        port.armIncoming(a)
        port.requestShift(480)
        val b = LoopMixPort.InArm(handoverUs = 20_000_000L)
        port.armIncoming(b)
        port.requestShift(240)
        // A debit the sink was making for the old arm lands on the old arm.
        a.pendingShift.addAndGet(-480)
        assertEquals(0L, a.pendingShift.get())
        assertEquals(240L, b.pendingShift.get())
    }

    @Test
    fun noReadingIsProjectedThroughInsertedSilence() {
        val port = LoopMixPort()
        // Track time 5 s, read while the delegate plays a 40 ms silence.
        port.publishPosition(5_000_000L, 1_000_000_000L, port.generation, steadyUs = 0L)
        assertNull("10 ms later the track hasn't moved", port.presentedSeconds(1_010_000_000L, playing = true))
        assertEquals(5.0, port.presentedSeconds(1_000_000_000L, playing = true)!!, 1e-9)
    }

    @Test
    fun aReadingIsProjectedOnlyUpToTheNextShift() {
        val port = LoopMixPort()
        // A shift's boundary 20 ms after the reading.
        port.publishPosition(5_000_000L, 1_000_000_000L, port.generation, steadyUs = 20_000L)
        assertEquals(5.010, port.presentedSeconds(1_010_000_000L, playing = true)!!, 1e-9)
        assertNull(port.presentedSeconds(1_030_000_000L, playing = true))
    }

    @Test
    fun aReadingIsNotProjectedOntoTheShiftBoundaryItself() {
        val port = LoopMixPort()
        // A drop's boundary 20 ms after the reading: at that instant the drop applies.
        port.publishPosition(5_000_000L, 1_000_000_000L, port.generation, steadyUs = 20_000L)
        assertNull(port.presentedSeconds(1_020_000_000L, playing = true))
        assertEquals(5.019, port.presentedSeconds(1_019_000_000L, playing = true)!!, 1e-9)
    }
}
