package com.music.bitchord.playback

import com.music.bitchord.playback.audio.AudioBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

class LoudnessProcessorTest {

    private val rate = 48_000

    // ---- The cached path ---------------------------------------------------

    /**
     * A known track opens at the right level rather than gliding to it. The
     * measurement was done on a previous play of the same recording, so there
     * is nothing to converge on and a ramp would only make a track whose gain
     * is already known open at the wrong volume.
     */
    @Test
    fun `a cached gain applies from the first frame`() {
        val processor = newProcessor()
        processor.prime("track", cachedGainDb = -6f)

        val block = constantBlock(1f)
        processor.process(block)

        assertEquals(10f.pow(-6f / 20f), block.samples[0], 1e-5f)
    }

    /** And it is not re-derived: a cached track is never metered. */
    @Test
    fun `a cached track is not measured again`() {
        val processor = newProcessor()
        processor.prime("track", cachedGainDb = -6f)
        feedSine(processor, dbfs(-23.0), seconds = 5.0)

        assertNull(processor.snapshot.measuredLufs)
        assertEquals(0.0, processor.snapshot.measuredSeconds, 0.0)
        assertTrue(processor.snapshot.fromCache)
    }

    // ---- Surviving a quality upgrade ---------------------------------------

    /**
     * The upgrade-swap contract, and the reason this feature needed one.
     *
     * A mid-track upgrade to JioSaavn or an addon re-primes the same track and
     * re-prepares the item under it. Neither may disturb the gain: the
     * listener already hears a short break in the audio at the swap, and a
     * level that jumped across it would turn an improvement in quality into an
     * obvious glitch.
     */
    @Test
    fun `re-priming the same track leaves the gain where it was`() {
        val processor = newProcessor()
        processor.prime("track", cachedGainDb = -6f)
        processor.process(constantBlock(1f))

        // What an upgrade does: same track, and the store may by now hold a
        // refined figure. Neither is allowed to move the level mid-song.
        processor.prime("track", cachedGainDb = -11f)
        val block = constantBlock(1f)
        processor.process(block)

        assertEquals(10f.pow(-6f / 20f), block.samples[0], 1e-5f)
    }

    /**
     * Nor may the sink's own lifecycle. Media3 resets a sink when a renderer
     * is disabled and re-enabled, which is exactly what the re-prepare behind
     * a quality swap causes.
     */
    @Test
    fun `a sink reset does not forget the track's gain`() {
        val processor = newProcessor()
        processor.prime("track", cachedGainDb = -6f)
        processor.process(constantBlock(1f))

        processor.reset()
        processor.configure(rate, 2)

        val block = constantBlock(1f)
        processor.process(block)
        assertEquals(10f.pow(-6f / 20f), block.samples[0], 1e-5f)
    }

    /** A genuinely different track is a clean slate, though. */
    @Test
    fun `a different track starts again`() {
        val processor = newProcessor()
        processor.prime("first", cachedGainDb = -6f)
        processor.process(constantBlock(1f))

        processor.prime("second", cachedGainDb = null)
        val block = constantBlock(1f)
        processor.process(block)

        assertEquals(1f, block.samples[0], 1e-6f)
        assertEquals("second", processor.snapshot.trackKey)
    }

    /** And a full teardown forgets everything. */
    @Test
    fun `forget clears the track`() {
        val processor = newProcessor()
        processor.prime("track", cachedGainDb = -6f)
        processor.process(constantBlock(1f))

        processor.forget()

        assertNull(processor.snapshot.trackKey)
        val block = constantBlock(1f)
        processor.process(block)
        assertEquals(1f, block.samples[0], 1e-6f)
    }

    // ---- Measuring ---------------------------------------------------------

    /** A loud master is brought down to the target. */
    @Test
    fun `a loud track is attenuated towards the target`() {
        val processor = newProcessor(targetLufs = -14f)
        processor.prime("loud", cachedGainDb = null)
        feedSine(processor, dbfs(-8.0), seconds = 5.0)

        // -8 LUFS wants -6 dB to reach -14, and attenuation can never clip so
        // the peak limit does not come into it.
        assertEquals(-6f, processor.snapshot.appliedGainDb, 0.2f)
    }

    /** A quiet one is brought up. */
    @Test
    fun `a quiet track is boosted towards the target`() {
        val processor = newProcessor(targetLufs = -14f)
        processor.prime("quiet", cachedGainDb = null)
        feedSine(processor, dbfs(-20.0), seconds = 5.0)

        assertEquals(6f, processor.snapshot.appliedGainDb, 0.2f)
    }

    /**
     * But never so far that it clips.
     *
     * A very quiet recording that nonetheless contains a full-scale transient
     * is the case that matters: the loudness figure asks for a large boost,
     * and applying it would drive that transient past full scale, where
     * [com.music.bitchord.playback.audio.PcmBoundary] clamps it into a
     * flat-topped sample. The peak limit is what keeps the loudest moment a
     * decibel inside full scale instead.
     */
    @Test
    fun `the gain is capped so the loudest sample cannot clip`() {
        val processor = newProcessor(targetLufs = -14f)
        processor.prime("peaky", cachedGainDb = null)

        // A quiet sine carrying one half-scale spike. The loudness figure
        // alone would ask for the full +12 dB the processor allows.
        feedSine(processor, dbfs(-35.0), seconds = 5.0, spike = 0.5f)

        val gain = 10f.pow(processor.snapshot.appliedGainDb / 20f)
        assertTrue(
            "gain ${processor.snapshot.appliedGainDb} dB would drive 0.5 past the ceiling",
            0.5f * gain <= LoudnessProcessor.PEAK_CEILING + 1e-4f,
        )
        assertEquals(LoudnessProcessor.PEAK_CEILING / 0.5f, gain, 0.02f)
    }

    /** Whatever a track measures, it is not moved further than the bounds allow. */
    @Test
    fun `the gain stays inside its bounds`() {
        val processor = newProcessor(targetLufs = -14f)
        processor.prime("verySoft", cachedGainDb = null)
        feedSine(processor, dbfs(-45.0), seconds = 5.0)

        assertTrue(processor.snapshot.appliedGainDb <= LoudnessProcessor.MAX_GAIN_DB + 1e-3f)
        assertTrue(processor.snapshot.appliedGainDb >= LoudnessProcessor.MIN_GAIN_DB - 1e-3f)
    }

    /** The target is a setting, and moving it moves where tracks land. */
    @Test
    fun `a different target lands the track somewhere else`() {
        val quieter = newProcessor(targetLufs = -20f)
        quieter.prime("t", cachedGainDb = null)
        feedSine(quieter, dbfs(-14.0), seconds = 5.0)

        assertEquals(-6f, quieter.snapshot.appliedGainDb, 0.2f)
    }

    // ---- Off ---------------------------------------------------------------

    /**
     * Switched off, samples are passed through untouched — the same bytes,
     * not merely the same to within a rounding error. This is what lets
     * bit-perfect mode and a plain disabled toggle mean the same thing at the
     * sample level.
     */
    @Test
    fun `disabled leaves samples exactly alone`() {
        val processor = newProcessor()
        processor.enabled = false
        processor.prime("track", cachedGainDb = -6f)

        val block = constantBlock(0.3f)
        processor.process(block)

        assertEquals(0.3f, block.samples[0], 0f)
    }

    /**
     * Turning it off mid-track releases the gain rather than stepping it. A
     * six-decibel jump on a toggle would be the loudest thing the setting ever
     * did.
     */
    @Test
    fun `switching off glides back to unity rather than snapping`() {
        val processor = newProcessor()
        processor.prime("track", cachedGainDb = -6f)
        processor.process(constantBlock(1f))

        processor.enabled = false
        val block = constantBlock(1f)
        processor.process(block)

        val first = block.samples[0]
        val last = block.samples[block.sampleCount - 1]
        assertNotEquals("the gain should still be coming off, not already gone", 1f, first)
        assertTrue("it should be heading towards unity", last > first)
    }

    // ---- Helpers -----------------------------------------------------------

    private fun newProcessor(targetLufs: Float = -14f) = LoudnessProcessor().apply {
        this.targetLufs = targetLufs
        configure(rate, 2)
    }

    private fun dbfs(db: Double): Double = 10.0.pow(db / 20.0)

    private fun constantBlock(value: Float, frames: Int = 1024): AudioBlock =
        AudioBlock(channelCount = 2, capacityFrames = frames).apply {
            samples.fill(value)
            reset(frames)
        }

    /**
     * Drives the processor the way the sink does — one [AudioBlock] at a time,
     * reused — so the block-counted recompute cadence is exercised rather than
     * bypassed.
     *
     * [spike] plants a single sample of that magnitude, for the peak limit.
     */
    private fun feedSine(
        processor: LoudnessProcessor,
        amplitude: Double,
        seconds: Double,
        hz: Double = 1000.0,
        spike: Float? = null,
        chunkFrames: Int = 4096,
    ) {
        val block = AudioBlock(channelCount = 2, capacityFrames = chunkFrames)
        var frame = 0L
        var remaining = (rate * seconds).toInt()
        var spiked = false
        while (remaining > 0) {
            val frames = minOf(remaining, chunkFrames)
            var i = 0
            for (f in 0 until frames) {
                val value = (amplitude * sin(2.0 * PI * hz * (frame + f) / rate)).toFloat()
                block.samples[i++] = value
                block.samples[i++] = value
            }
            if (spike != null && !spiked) {
                block.samples[0] = spike
                block.samples[1] = spike
                spiked = true
            }
            block.reset(frames)
            processor.process(block)
            frame += frames
            remaining -= frames
        }
    }
}
