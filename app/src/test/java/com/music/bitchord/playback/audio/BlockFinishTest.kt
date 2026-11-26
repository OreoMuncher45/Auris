package com.music.bitchord.playback.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BlockFinishTest {
    private fun block(vararg samples: Float) = AudioBlock(channelCount = 2, capacityFrames = samples.size / 2).apply {
        reset(samples.size / 2)
        samples.copyInto(this.samples)
    }

    @Test
    fun withoutBitPerfectTheChainRunsAndNoVolumeIsApplied() {
        val b = block(0.5f, -0.5f)
        var ran = 0
        BlockFinish.apply(b, skipDsp = false, userGain = 0.25f) { ran++ }
        assertEquals(1, ran)
        assertArrayEquals(floatArrayOf(0.5f, -0.5f), b.samples, 0f)
    }

    @Test
    fun underBitPerfectTheChainIsSkippedAndTheListenersVolumeApplied() {
        // A loop mix's generated block under bit-perfect, the mixer out of the path:
        // the volume the decoded blocks get, not full level, and no EQ or Atmos.
        val b = block(0.5f, -0.5f)
        var ran = 0
        BlockFinish.apply(b, skipDsp = true, userGain = 0.25f) { ran++ }
        assertEquals(0, ran)
        assertArrayEquals(floatArrayOf(0.125f, -0.125f), b.samples, 1e-7f)
    }

    @Test
    fun underBitPerfectAtFullVolumeTheSamplesAreUntouched() {
        val b = block(0.5f, -0.5f)
        BlockFinish.apply(b, skipDsp = true, userGain = 1f) { error("no chain under bit-perfect") }
        assertArrayEquals(floatArrayOf(0.5f, -0.5f), b.samples, 0f)
    }
}
