package com.music.bitchord.playback.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * The meter is checked against EBU Tech 3341's own conformance signals rather
 * than against itself.
 *
 * That matters more here than it usually would: an approximately-right
 * loudness meter produces approximately-right gains, and nothing about the
 * result *sounds* broken — tracks would simply be levelled to the wrong place,
 * consistently, with no symptom anyone could report. A reference figure is the
 * only thing that catches a K-weighting derived wrongly for the sample rate,
 * or a gate applied to the wrong quantity.
 */
class LoudnessMeterTest {

    /**
     * EBU Tech 3341 test 1: a 1 kHz sine at -23 dBFS in both channels of a
     * stereo pair integrates to -23.0 LUFS, to within a tenth of a unit.
     *
     * This single assertion exercises nearly all of the measurement — both
     * K-weighting stages, the 400 ms blocks, the overlap, the channel sum and
     * the -0.691 offset. Getting any of them wrong moves the answer by more
     * than the tolerance.
     */
    @Test
    fun `stereo 1 kHz sine at -23 dBFS measures -23 LUFS`() {
        val meter = LoudnessMeter()
        meter.configure(48_000, 2)
        feedSine(meter, rate = 48_000, hz = 1000.0, amplitude = dbfs(-23.0), seconds = 5.0)

        val measured = assertNotNull("meter produced no figure", meter.integratedLufs).let { meter.integratedLufs!! }
        assertEquals(-23.0, measured, 0.1)
    }

    /**
     * The same signal at 44.1 kHz, which is what a lossless source actually
     * serves and what the spec's published coefficient table does *not* cover.
     * Deriving the filter for the live rate is the only reason this passes;
     * reusing the 48 kHz constants shifts both corner frequencies and the
     * answer with them.
     */
    @Test
    fun `K-weighting is derived for the stream's own rate`() {
        val meter = LoudnessMeter()
        meter.configure(44_100, 2)
        feedSine(meter, rate = 44_100, hz = 1000.0, amplitude = dbfs(-23.0), seconds = 5.0)

        assertEquals(-23.0, meter.integratedLufs!!, 0.1)
    }

    /** Halving the amplitude is -6 dB, and the meter should say exactly that. */
    @Test
    fun `a 6 dB quieter signal measures 6 LU lower`() {
        val loud = LoudnessMeter().apply { configure(48_000, 2) }
        val quiet = LoudnessMeter().apply { configure(48_000, 2) }
        feedSine(loud, 48_000, 1000.0, dbfs(-20.0), 5.0)
        feedSine(quiet, 48_000, 1000.0, dbfs(-26.0), 5.0)

        assertEquals(6.0, loud.integratedLufs!! - quiet.integratedLufs!!, 0.05)
    }

    /**
     * Silence never produces a figure, however much of it is heard. Without
     * the absolute gate a silent lead-in would drag a track's measured
     * loudness down and earn it a boost it does not need.
     */
    @Test
    fun `silence stays below the absolute gate`() {
        val meter = LoudnessMeter()
        meter.configure(48_000, 2)
        meter.process(FloatArray(48_000 * 2), 48_000)

        assertNull(meter.integratedLufs)
    }

    /**
     * The relative gate is what stops a quiet passage pulling the figure down.
     * A track that is loud for four seconds and near-silent for four measures
     * essentially as its loud half, not as the average of the two.
     */
    @Test
    fun `the relative gate discards a quiet passage`() {
        val meter = LoudnessMeter()
        meter.configure(48_000, 2)
        feedSine(meter, 48_000, 1000.0, dbfs(-23.0), 4.0)
        feedSine(meter, 48_000, 1000.0, dbfs(-50.0), 4.0)

        // Ungated, the mean power of the two halves would land near -26 LUFS.
        assertEquals(-23.0, meter.integratedLufs!!, 0.3)
    }

    /** Peak is the raw sample maximum, untouched by the K-weighting. */
    @Test
    fun `peak tracks the loudest raw sample`() {
        val meter = LoudnessMeter()
        meter.configure(48_000, 2)
        val block = FloatArray(2048)
        block[100] = 0.75f
        block[101] = -0.9f
        meter.process(block, 1024)

        assertEquals(0.9f, meter.peak, 1e-6f)
    }

    /**
     * The upgrade-swap contract, at the level the meter owns it.
     *
     * A mid-track upgrade to JioSaavn or an addon reconfigures the sink under
     * a playing track, usually at a different rate. Everything measured before
     * the swap still describes the same recording, so reconfiguring must not
     * throw it away — if it did, the gain would restart from nothing halfway
     * through a song and step the level as it converged again.
     */
    @Test
    fun `reconfiguring for a new rate keeps what was already measured`() {
        val meter = LoudnessMeter()
        meter.configure(48_000, 2)
        feedSine(meter, 48_000, 1000.0, dbfs(-23.0), 5.0)
        val before = meter.integratedLufs!!
        val blocksBefore = meter.measuredBlocks
        val peakBefore = meter.peak

        // 48 kHz Opus giving way to 44.1 kHz FLAC, which is the common shape
        // of a real upgrade.
        meter.configure(44_100, 2)

        assertEquals(before, meter.integratedLufs!!, 1e-9)
        assertEquals(blocksBefore, meter.measuredBlocks)
        assertEquals(peakBefore, meter.peak, 1e-9f)
    }

    /** A seek is the same recording, so the measurement behind it still counts. */
    @Test
    fun `flushing for a seek keeps the measurement`() {
        val meter = LoudnessMeter()
        meter.configure(48_000, 2)
        feedSine(meter, 48_000, 1000.0, dbfs(-23.0), 5.0)
        val before = meter.integratedLufs!!

        meter.flushTransient()

        assertEquals(before, meter.integratedLufs!!, 1e-9)
    }

    /** A different track is a clean slate. */
    @Test
    fun `resetting forgets the track`() {
        val meter = LoudnessMeter()
        meter.configure(48_000, 2)
        feedSine(meter, 48_000, 1000.0, dbfs(-23.0), 5.0)

        meter.reset()

        assertNull(meter.integratedLufs)
        assertEquals(0, meter.measuredBlocks)
        assertEquals(0f, meter.peak, 0f)
    }

    /** Nothing is offered before there is enough audio to base it on. */
    @Test
    fun `no figure is offered from a fraction of a second`() {
        val meter = LoudnessMeter()
        meter.configure(48_000, 2)
        feedSine(meter, 48_000, 1000.0, dbfs(-23.0), 0.5)

        assertNull(meter.integratedLufs)
    }

    /** Mono is measured on its own terms rather than being assumed stereo. */
    @Test
    fun `mono is measured without a phantom second channel`() {
        val meter = LoudnessMeter()
        meter.configure(48_000, 1)
        feedSine(meter, 48_000, 1000.0, dbfs(-23.0), 5.0, channels = 1)

        // One channel carries half the power two identical ones do, so the
        // same waveform reads 3.01 LU lower. That it does is the check: a
        // meter that summed a channel it did not have would read -23.
        assertEquals(-26.01, meter.integratedLufs!!, 0.1)
    }

    /** History stops growing, and the meter keeps working once it has. */
    @Test
    fun `block history is bounded`() {
        val meter = LoudnessMeter()
        meter.configure(8_000, 2)
        // Past MAX_BLOCKS at this rate, cheaply: 6000 blocks is 600 seconds.
        feedSine(meter, 8_000, 1000.0, dbfs(-23.0), 620.0)

        assertTrue(meter.measuredBlocks <= LoudnessMeter.MAX_BLOCKS)
        assertEquals(-23.0, meter.integratedLufs!!, 0.3)
    }

    // ---- Helpers -----------------------------------------------------------

    /** Peak amplitude of a sine at [db] dBFS, the convention EBU 3341 uses. */
    private fun dbfs(db: Double): Double = 10.0.pow(db / 20.0)

    /**
     * Feeds a continuous sine, in chunks, so the meter is exercised the way
     * the sink drives it rather than with one enormous array.
     */
    private fun feedSine(
        meter: LoudnessMeter,
        rate: Int,
        hz: Double,
        amplitude: Double,
        seconds: Double,
        channels: Int = 2,
        chunkFrames: Int = 4096,
    ) {
        val totalFrames = (rate * seconds).toInt()
        val buffer = FloatArray(chunkFrames * channels)
        var frame = 0L
        var remaining = totalFrames
        while (remaining > 0) {
            val frames = minOf(remaining, chunkFrames)
            var i = 0
            for (f in 0 until frames) {
                val value = (amplitude * sin(2.0 * PI * hz * (frame + f) / rate)).toFloat()
                repeat(channels) { buffer[i++] = value }
            }
            meter.process(buffer, frames)
            frame += frames
            remaining -= frames
        }
    }
}
