package com.music.bitchord

import com.music.bitchord.playback.audio.AudioBlock
import com.music.bitchord.playback.audio.SimulatedAtmosProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatedAtmosProcessorTest {

    private fun block(vararg framePairs: Pair<Float, Float>): AudioBlock {
        val b = AudioBlock(channelCount = 2, capacityFrames = framePairs.size)
        framePairs.forEachIndexed { i, (l, r) ->
            b.samples[i * 2] = l
            b.samples[i * 2 + 1] = r
        }
        b.setFrameCount(framePairs.size)
        return b
    }

    @Test
    fun `disabled leaves the block untouched`() {
        val p = SimulatedAtmosProcessor()
        p.configure(48000, 2)
        val b = block(0.5f to 0.4f, -0.2f to 0.3f, 0.1f to -0.6f)
        val before = b.samples.copyOf()
        p.process(b)
        assertArrayEquals(before, b.samples, 0.0f)
    }

    @Test
    fun `enabled changes a stereo block`() {
        val p = SimulatedAtmosProcessor()
        p.configure(48000, 2)
        p.enabled = true
        // A strongly centred signal: the mid/side effect has the least to do
        // here, and it must still move the samples.
        val b = block(0.3f to 0.3f, 0.7f to 0.7f, -0.4f to -0.4f)
        val before = b.samples.copyOf()
        p.process(b)
        assertTrue("widening must alter the samples", !b.samples.contentEquals(before))
    }

    @Test
    fun `enabled widens an off-centre image`() {
        val p = SimulatedAtmosProcessor()
        p.configure(48000, 2)
        p.enabled = true
        // Feed a moving side component and confirm the side-to-mid ratio grows.
        val frames = 64
        val b = AudioBlock(channelCount = 2, capacityFrames = frames)
        for (i in 0 until frames) {
            val s = kotlin.math.sin(i * 0.3).toFloat() * 0.4f
            b.samples[i * 2] = 0.5f + s
            b.samples[i * 2 + 1] = 0.5f - s
        }
        b.setFrameCount(frames)
        val sideBefore = (0 until frames).sumOf {
            val d = (b.samples[it * 2] - b.samples[it * 2 + 1]).toDouble()
            Math.abs(d)
        }
        p.process(b)
        val sideAfter = (0 until frames).sumOf {
            val d = (b.samples[it * 2] - b.samples[it * 2 + 1]).toDouble()
            Math.abs(d)
        }
        assertTrue("side energy should grow ($sideBefore -> $sideAfter)", sideAfter > sideBefore)
    }
}
