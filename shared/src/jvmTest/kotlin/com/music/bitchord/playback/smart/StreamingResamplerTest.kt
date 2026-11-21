package com.music.bitchord.playback.smart

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamingResamplerTest {

    @Test
    fun streamedOutputIsIdenticalToOneWholeArrayCall() {
        // The rates a phone actually decodes to, against the analyser's 11 025 Hz.
        for (inputRate in listOf(44_100, 48_000, 96_000, 32_000, 22_050)) {
            val input = noise(inputRate * 9 + 777, seed = inputRate)
            val whole = polyphase(input, inputRate, OUT)
            // Small blocks and odd push sizes, so every boundary case is crossed
            // many times over rather than once.
            for (push in listOf(1, 1153, 4096, 70_001)) {
                val streamed = StreamingResampler(inputRate, OUT, ::polyphase, blockFrames = 8_192)
                var at = 0
                val scratch = FloatArray(push)
                while (at < input.size) {
                    val count = min(push, input.size - at)
                    input.copyInto(scratch, 0, at, at + count)
                    assertTrue(streamed.push(scratch, count))
                    at += count
                }
                val result = assertNotNull(streamed.finish())
                assertContentEquals(whole, result, "rate $inputRate, push $push")
            }
        }
    }

    @Test
    fun aRateWithinAHertzPassesThroughUntouched() {
        val input = noise(50_000, seed = 3)
        val streamed = StreamingResampler(OUT, OUT, { _, _, _ -> error("not called") })
        assertTrue(streamed.passthrough)
        streamed.push(input, 30_000)
        streamed.push(input.copyOfRange(30_000, 50_000))
        assertContentEquals(input, streamed.finish())
        assertEquals(OUT, streamed.rate)
    }

    @Test
    fun aFailedBlockFailsTheDecode() {
        val streamed = StreamingResampler(48_000, OUT, { _, _, _ -> null }, blockFrames = 4_096)
        val input = noise(20_000, seed = 9)
        assertTrue(!streamed.push(input))
        assertNull(streamed.finish())
    }

    /**
     * The regression this exists for: a ten-minute 24-bit/96 kHz stereo file,
     * the worst the whole-track pass accepts (see MAX_WHOLE_TRACK_SECONDS).
     * Decoded whole it is 57.6 M mono floats — a 256 MiB doubling buffer and a
     * 230 MB copy of it — on a 192 MB heap. Streamed, nothing scales with the
     * source rate.
     */
    @Test
    fun tenMinutesOfHiResStaysUnderTheCap() {
        val seconds = 600.0
        val inputRate = 96_000
        val estimate = StreamingResampler.peakBytesFor(seconds, inputRate, OUT)
        assertTrue(estimate < CAP_BYTES, "estimated peak $estimate bytes")

        // And measured, by actually streaming that many frames through it with
        // a stand-in resampler that has the same output count.
        val streamed = StreamingResampler(inputRate, OUT, ::decimate)
        val buffer = FloatArray(4_608) // one FLAC block's worth of frames
        val frames = (seconds * inputRate).toLong()
        var pushed = 0L
        while (pushed < frames) {
            val count = min(buffer.size.toLong(), frames - pushed).toInt()
            streamed.push(buffer, count)
            pushed += count
        }
        val result = assertNotNull(streamed.finish())
        assertEquals(floor(frames * OUT.toDouble() / inputRate).toLong(), result.size.toLong())
        val peakBytes = streamed.peakFloats * 4
        assertTrue(peakBytes <= estimate, "measured $peakBytes > estimate $estimate")
        assertTrue(peakBytes < CAP_BYTES, "measured peak $peakBytes bytes")
        // No single buffer is anywhere near the 64 MiB allocation that failed.
        assertTrue(result.size * 4L < 32L * 1024 * 1024)
        assertTrue(StreamingResampler.pendingCapacity(inputRate, OUT) * 4L < 1024 * 1024)
    }

    private fun noise(count: Int, seed: Int): FloatArray {
        val random = Random(seed)
        return FloatArray(count) { index ->
            (0.6 * sin(index * 0.013) + 0.4 * (random.nextFloat() * 2 - 1)).toFloat()
        }
    }

    /** Output count of the polyphase path, with no filtering: memory tests only. */
    private fun decimate(input: FloatArray, inputRate: Double, outputRate: Double): FloatArray {
        val count = floor(input.size * outputRate / inputRate).toInt()
        return FloatArray(count) { input[min(input.size - 1, (it * inputRate / outputRate).toInt())] }
    }

    /**
     * A line-for-line port of native/analyzer/resampler.cpp's ResamplePolyphase,
     * fast path and clamped-edge path both, so an output stitched from the wrong
     * taps — or computed on the edge path where one call would have taken the
     * fast one — comes out different in the last bits and fails the comparison.
     */
    private fun polyphase(input: FloatArray, inputRateD: Double, outputRateD: Double): FloatArray =
        polyphase(input, inputRateD.toInt(), outputRateD.toInt())

    private fun polyphase(input: FloatArray, inputRate: Int, outputRate: Int): FloatArray {
        tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
        val divisor = gcd(inputRate.toLong(), outputRate.toLong())
        val up = outputRate / divisor
        val down = inputRate / divisor
        require(up <= 1024)
        val ratio = up.toDouble() / down.toDouble()
        val cutoff = 0.5 * min(1.0, ratio)
        val halfWidth = 32.0 / (2.0 * cutoff)
        val outputCount = floor(input.size * ratio).toInt()
        if (outputCount == 0) return FloatArray(0)
        val reach = ceil(halfWidth).toLong() + 1
        val taps = (2 * reach + 1).toInt()
        val weights = FloatArray((up * taps).toInt())
        for (phase in 0 until up) {
            val fraction = phase.toDouble() / up.toDouble()
            val row = DoubleArray(taps)
            var sum = 0.0
            for (k in 0 until taps) {
                row[k] = kernelAt(fraction - (k - reach).toDouble(), cutoff, halfWidth)
                sum += row[k]
            }
            for (k in 0 until taps) weights[(phase * taps + k).toInt()] = (if (sum != 0.0) row[k] / sum else 0.0).toFloat()
        }
        val out = FloatArray(outputCount)
        val size = input.size.toLong()
        for (index in 0 until outputCount) {
            val position = index.toLong() * down
            val whole = position / up
            val phase = position % up
            val rowStart = (phase * taps).toInt()
            val first = whole - reach
            if (first >= 0 && first + taps <= size) {
                var sum = 0f
                for (k in 0 until taps) sum += weights[rowStart + k] * input[(first + k).toInt()]
                out[index] = sum
            } else {
                var sum = 0.0
                var weightSum = 0.0
                for (k in 0 until taps) {
                    val tap = min(max(first + k, 0L), size - 1).toInt()
                    sum += weights[rowStart + k].toDouble() * input[tap].toDouble()
                    weightSum += weights[rowStart + k]
                }
                out[index] = (if (weightSum != 0.0) sum / weightSum else 0.0).toFloat()
            }
        }
        return out
    }

    private fun kernelAt(offsetIn: Double, cutoff: Double, halfWidth: Double): Double {
        val offset = abs(offsetIn)
        if (offset >= halfWidth) return 0.0
        val position = (offset + halfWidth) / (2.0 * halfWidth)
        val window = 0.42 - 0.5 * cos(2.0 * PI * position) + 0.08 * cos(4.0 * PI * position)
        val x = 2.0 * cutoff * offset
        val sinc = if (abs(x) < 1e-12) 1.0 else sin(PI * x) / (PI * x)
        return 2.0 * cutoff * sinc * window
    }

    private companion object {
        const val OUT = 11_025
        /** What the whole-track pass may hold for its decode: a sixth of a 192 MB heap. */
        const val CAP_BYTES = 64L * 1024 * 1024
    }
}
