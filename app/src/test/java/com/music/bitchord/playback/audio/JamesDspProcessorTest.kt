package com.music.bitchord.playback.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JamesDspProcessorTest {

    @Test
    fun `immersive audio bypasses tone and space stages but keeps gain`() {
        val processor = JamesDspProcessor()
        processor.configure(48_000, 6)
        processor.update(
            JamesDspConfig(
                enabled = true,
                bassGainDb = 15f,
                stereoLevel = 80f,
                tubeLevel = 100f,
                reverbPreset = 3,
                preGainDb = 6f,
                limiterEnabled = false,
            ),
        )

        val block = AudioBlock(channelCount = 6, capacityFrames = 4)
        for (frame in 0 until 4) {
            for (channel in 0 until 6) {
                block.samples[frame * 6 + channel] = if (channel % 2 == 0) 0.25f else -0.25f
            }
        }
        block.reset(4)

        processor.process(block)

        // +6 dB is a factor of 10^(6/20) — 1.99526, not exactly 2. No bass,
        // widening, tube or reverb stage may touch an immersive channel field.
        val gain = Math.pow(10.0, 6.0 / 20.0).toFloat()
        for (frame in 0 until 4) {
            for (channel in 0 until 6) {
                val expected = if (channel % 2 == 0) 0.25f * gain else -0.25f * gain
                assertEquals(expected, block.samples[frame * 6 + channel], 1e-6f)
            }
        }
    }

    @Test
    fun `immersive limiter never exceeds full scale`() {
        val processor = JamesDspProcessor()
        processor.configure(48_000, 6)
        processor.update(
            JamesDspConfig(
                enabled = true,
                preGainDb = 15f,
                limiterEnabled = true,
            ),
        )

        val block = AudioBlock(channelCount = 6, capacityFrames = 2)
        for (index in 0 until block.samples.size) block.samples[index] = 0.9f
        block.reset(2)

        processor.process(block)

        for (index in 0 until block.sampleCount) {
            assertTrue(block.samples[index] <= 1f)
            assertTrue(block.samples[index] >= -1f)
        }
    }

    @Test
    fun `stereo width keeps zero off and clamps enabled values to the stable range`() {
        assertEquals(0f, JamesDspConfig.safeStereoLevel(0f), 0f)
        assertEquals(20f, JamesDspConfig.safeStereoLevel(10f), 0f)
        assertEquals(80f, JamesDspConfig.safeStereoLevel(90f), 0f)
        assertEquals(55f, JamesDspConfig.safeStereoLevel(55f), 0f)
    }
}
