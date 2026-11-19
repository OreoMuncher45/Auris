package com.music.bitchord.playback

import com.music.bitchord.playback.audio.AudioBlock
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sing is off for almost every listener, and the processor sits in every
 * player's DSP chain for every sample of every song. Off has to mean the
 * engine never runs: it once tracked its filters and voice gate through the
 * whole song "in case" the slider moved, which cost ~14% of a core during all
 * playback (perf ticket 03).
 *
 * Driven through [VocalCutProcessor.process], the call [DspChain] makes, with
 * a recording engine standing in for the real three.
 */
class VocalCutProcessorIdleTest {

    private class RecordingEngine : SingEngine {
        val spans = mutableListOf<Pair<Int, Float>>() // frames, cut
        var resets = 0
        override fun configure(sampleRate: Int) {}
        override fun processSpan(samples: FloatArray, sampleCount: Int, cut: Float) {
            spans += (sampleCount / 2) to cut
        }
        override fun processFrame(left: Float, right: Float, cut: Float): FloatArray = floatArrayOf(left, right)
        override fun reset() { resets++ }
    }

    private val sampleRate = 48_000
    private val engine = RecordingEngine()
    private val processor = VocalCutProcessor { engine }.apply { configure(sampleRate, 2) }

    private fun block(frames: Int = 960) = AudioBlock(channelCount = 2).apply {
        setFrameCount(frames)
        for (i in 0 until frames * 2) samples[i] = ((i % 97) - 48) / 100f
    }

    @Test
    fun `Sing off never runs the engine and leaves the block untouched`() {
        processor.level = 0f
        repeat(500) { // ~10 s of Opus-sized blocks
            val b = block()
            val before = b.samples.copyOf()
            processor.process(b)
            assertArrayEquals(before, b.samples, 0f)
        }
        assertTrue("engine ran while Sing was off: ${engine.spans.size} spans", engine.spans.isEmpty())
    }

    @Test
    fun `raising Sing from off resets, warms up silently, then cuts`() {
        processor.level = 0f
        repeat(10) { processor.process(block()) }
        val resetsBefore = engine.resets

        processor.level = 0.8f
        repeat(20) { processor.process(block()) }

        assertEquals("one fresh start on activation", resetsBefore + 1, engine.resets)
        val warmFrames = engine.spans.takeWhile { it.second == 0f }.sumOf { it.first }
        val warmupTarget = sampleRate * VocalCutProcessor.WARMUP_MS / 1000
        assertTrue("warm-up $warmFrames frames, want >= $warmupTarget", warmFrames >= warmupTarget)
        assertTrue("warm-up overshot by a block or more: $warmFrames", warmFrames < warmupTarget + 960)
        assertTrue("cut never applied after warm-up", engine.spans.dropWhile { it.second == 0f }.all { it.second == 0.8f })
        assertTrue(engine.spans.any { it.second == 0.8f })
    }

    @Test
    fun `turning Sing off and on again warms up again`() {
        processor.level = 0.5f
        repeat(20) { processor.process(block()) }
        processor.level = 0f
        repeat(10) { processor.process(block()) }
        val spansWhileOff = engine.spans.size
        processor.level = 0.5f
        processor.process(block())

        assertEquals(2, engine.resets)
        assertEquals("first block after re-enable is warm-up", 0f, engine.spans[spansWhileOff].second)
    }
}
