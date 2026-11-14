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
 * The other half: whenever the engine's state doesn't fit the audio (Sing
 * raised from off, a new method, a seek), it is reset and warmed up silently
 * before it cuts, so it never cuts through cold filters.
 *
 * Driven through [VocalCutProcessor.process], the call [DspChain] makes, with
 * recording engines standing in for the real ones.
 */
class VocalCutProcessorIdleTest {

    private class RecordingEngine(override val needsWarmup: Boolean = true) : SingEngine {
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
    private val warmupFrames = sampleRate * VocalCutProcessor.WARMUP_MS / 1000
    private val blockFrames = 960 // one 20 ms Opus packet at 48 kHz
    private val engines = mutableListOf<RecordingEngine>()
    private var nextNeedsWarmup = true
    private val processor = VocalCutProcessor { RecordingEngine(nextNeedsWarmup).also { engines += it } }
        .apply { configure(sampleRate, 2) }
    private val engine get() = engines.last()

    private fun block() = AudioBlock(channelCount = 2).apply {
        setFrameCount(blockFrames)
        for (i in 0 until blockFrames * 2) samples[i] = ((i % 97) - 48) / 100f
    }

    private fun play(blocks: Int) = repeat(blocks) { processor.process(block()) }

    /** Frames run at zero cut from span [from] on, before the first real cut. */
    private fun warmupFrom(from: Int) =
        engine.spans.drop(from).takeWhile { it.second == 0f }.sumOf { it.first }

    private fun assertWarmedUp(from: Int) {
        val warm = warmupFrom(from)
        assertTrue("warm-up $warm frames, want >= $warmupFrames", warm >= warmupFrames)
        assertTrue("warm-up overshot by a block or more: $warm", warm < warmupFrames + blockFrames)
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
        play(10)
        processor.level = 0.8f
        play(20)

        assertEquals("one fresh start on activation", 1, engine.resets)
        assertWarmedUp(from = 0)
        assertTrue("cut not applied after warm-up", engine.spans.dropWhile { it.second == 0f }.all { it.second == 0.8f })
        assertTrue(engine.spans.any { it.second == 0.8f })
    }

    @Test
    fun `turning Sing off and on again warms up again`() {
        processor.level = 0.5f
        play(20)
        processor.level = 0f
        play(10)
        val mark = engine.spans.size
        processor.level = 0.5f
        play(20)

        assertEquals(2, engine.resets)
        assertWarmedUp(from = mark)
    }

    @Test
    fun `Sing turned off while the chain is bypassed still warms up next time`() {
        // With every stage idle the sink skips the chain, so process() never
        // sees the zero. The setter alone has to mark the engine stale.
        processor.level = 0.5f
        play(20)
        val mark = engine.spans.size
        processor.level = 0f // no process() calls while off
        processor.level = 0.5f
        play(20)

        assertEquals(2, engine.resets)
        assertWarmedUp(from = mark)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `a seek with Sing on warms up again`() {
        processor.level = 0.5f
        play(20)
        val mark = engine.spans.size
        processor.flush()
        play(20)

        assertTrue("engine not reset after the flush", engine.resets >= 2)
        assertWarmedUp(from = mark)
    }

    @Test
    fun `a new method with Sing on starts fresh and warms up`() {
        processor.level = 0.5f
        play(20)
        processor.setMethod(SingMethod.BAND_LIMITED)
        play(20)

        assertEquals("a new engine for the new method", 2, engines.size)
        assertEquals(1, engine.resets)
        assertWarmedUp(from = 0)
    }

    @Test
    fun `an engine with no state cuts at once`() {
        nextNeedsWarmup = false
        processor.setMethod(SingMethod.ORIGINAL)
        processor.level = 0.5f
        play(3)

        assertTrue("stateless engine warmed up", engine.spans.all { it.second == 0.5f })
        assertEquals(3, engine.spans.size)
    }

    @Test
    fun `Studio never runs the filter engine`() {
        processor.setMethod(SingMethod.NEURAL)
        processor.level = 1f
        play(50)

        assertTrue("filter ran under Studio", engines.all { it.spans.isEmpty() })
        assertEquals(0f, processor.effectiveLevel)
    }
}
