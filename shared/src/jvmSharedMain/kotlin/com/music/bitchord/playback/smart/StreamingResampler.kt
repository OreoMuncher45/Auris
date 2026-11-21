package com.music.bitchord.playback.smart

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Resamples mono PCM as it is decoded, a block at a time, so the whole-track
 * pass never holds the track at the container's own rate.
 *
 * ## Why
 *
 * The whole-track pass used to decode the entire recording to mono float at
 * the container's rate and only then resample it to the analyser's 11 kHz.
 * At 44.1 kHz that is ten megabytes a minute, at 96 kHz twenty-three, and the
 * buffer it lived in grew by doubling — so a seven-minute song asked the heap
 * for one 64 MiB block while the 32 MiB one it was replacing was still live,
 * and a ten-minute hi-res file would have asked for 256 MiB. On a 192 MB heap
 * the first of those is an OutOfMemoryError.
 *
 * Resampled as it arrives, the only buffer that scales with the track is the
 * output, at the analyser's own rate: about 2.6 MB a minute whatever the
 * source was.
 *
 * ## Why the output is identical to a whole-array resample
 *
 * [resample] is the native polyphase FIR. Each output sample `i` of a
 * whole-array call reads the input taps `whole(i) - reach .. whole(i) + reach`,
 * where `whole(i) = floor(i * down / up)`, and nothing else. So a block is
 * resampled with enough history kept in front of it and enough lookahead
 * behind it that every output sample this keeps from it was computed from
 * exactly the taps the whole-array call would have used, by the same code:
 *
 * - Blocks start on a multiple of `down` input samples, so block-local output
 *   `j` is global output `j + start * up / down` with the same filter phase.
 * - Only outputs whose last tap is inside the block are kept; the rest wait for
 *   the next block. The native fast path is taken for exactly those outputs.
 * - The first block starts at the track's first sample and the last ends at its
 *   last, so the clamped edges are the real edges, as they are in one call.
 *
 * Rates whose ratio needs more than the native polyphase table allows go
 * through its continuous-kernel path instead; the same stitching keeps the
 * same taps there too, to within a last-bit rounding of the kernel position.
 *
 * Not thread-safe; one per decode.
 */
class StreamingResampler(
    private val inputRate: Int,
    private val outputRate: Int,
    /**
     * A whole-array resampler with the native resampler's tap geometry:
     * [TrackFeatures.resample] in production, a stand-in under test.
     */
    private val resample: (FloatArray, Double, Double) -> FloatArray?,
    blockFrames: Int = DEFAULT_BLOCK_FRAMES,
) {
    /** Within a hertz of each other the caller has always skipped the conversion. */
    val passthrough: Boolean = abs(inputRate - outputRate) <= 1

    /** The rate [finish]'s samples are at. */
    val rate: Int get() = if (passthrough) inputRate else outputRate

    private val up: Long
    private val down: Long
    private val reach: Long

    init {
        require(inputRate > 0 && outputRate > 0) { "rates must be positive" }
        val divisor = gcd(inputRate.toLong(), outputRate.toLong())
        up = outputRate / divisor
        down = inputRate / divisor
        reach = reachOf(inputRate, outputRate)
    }

    private val pending = FloatArray(
        if (passthrough) 0 else pendingCapacity(inputRate, outputRate, blockFrames),
    )
    private var pendingSize = 0

    /** Global input index of `pending[0]`; always a multiple of [down]. */
    private var bufferStart = 0L

    /** Global output samples already handed to [output]. */
    private var emitted = 0L

    private var failed = false
    private val output = ChunkedFloats()

    /** The most floats this has held live at once, for the memory tests. */
    var peakFloats: Long = 0
        private set

    /** Total input frames pushed. */
    var inputFrames: Long = 0
        private set

    /**
     * Appends [count] samples from [samples]. Returns false once a block failed
     * to resample, after which nothing more is kept and [finish] returns null.
     */
    fun push(samples: FloatArray, count: Int = samples.size): Boolean {
        if (failed) return false
        inputFrames += count
        if (passthrough) {
            output.add(samples, 0, count)
            notePeak(0)
            return true
        }
        var offset = 0
        while (offset < count) {
            val take = min(count - offset, pending.size - pendingSize)
            samples.copyInto(pending, pendingSize, offset, offset + take)
            pendingSize += take
            offset += take
            if (pendingSize == pending.size && !flushBlock()) {
                failed = true
                return false
            }
        }
        return true
    }

    /**
     * Resamples what is left and returns the whole output, contiguous. Null if
     * a block failed or nothing was pushed.
     */
    fun finish(): FloatArray? {
        if (failed || inputFrames == 0L) return null
        if (!passthrough && pendingSize > 0) {
            val block = if (pendingSize == pending.size) pending else pending.copyOf(pendingSize)
            val local = resample(block, inputRate.toDouble(), outputRate.toDouble()) ?: return null
            notePeak(local.size.toLong() + if (block === pending) 0L else block.size.toLong())
            val firstGlobal = bufferStart * up / down
            val from = (emitted - firstGlobal).toInt()
            if (from < local.size) output.add(local, from, local.size - from)
            emitted = firstGlobal + local.size
            pendingSize = 0
        }
        // Released before the contiguous copy is made: the copy is the one
        // allocation here that scales with the track, so nothing else should be
        // live beside it.
        return output.toArray().also { notePeak(it.size.toLong()) }
    }

    /**
     * Resamples a full [pending] buffer, keeps the outputs whose taps it fully
     * covers, and slides the history the next outputs need to the front.
     */
    private fun flushBlock(): Boolean {
        val local = resample(pending, inputRate.toDouble(), outputRate.toDouble()) ?: return false
        notePeak(local.size.toLong())
        val firstGlobal = bufferStart * up / down
        // Largest whole tap index the block can serve with a full kernel.
        val lastWhole = bufferStart + pendingSize - 1 - reach
        if (lastWhole >= 0) {
            val end = min(((lastWhole + 1) * up - 1) / down + 1, firstGlobal + local.size)
            if (end > emitted) {
                output.add(local, (emitted - firstGlobal).toInt(), (end - emitted).toInt())
                emitted = end
            }
        }
        // The next output's first tap, rounded down to a phase-aligned start.
        val nextWhole = emitted * down / up
        val nextStart = max(0L, nextWhole - reach) / down * down
        val drop = (nextStart - bufferStart).toInt()
        check(drop in 1..pendingSize) { "resampler block too small for its kernel" }
        pending.copyInto(pending, 0, drop, pendingSize)
        pendingSize -= drop
        bufferStart = nextStart
        return true
    }

    private fun notePeak(transient: Long) {
        val live = pending.size.toLong() + output.size + output.chunkSlack + transient
        if (live > peakFloats) peakFloats = live
    }

    /**
     * Floats appended in fixed-size chunks, so collecting the output never
     * copies it and never needs one block the size of the track until [toArray].
     */
    private class ChunkedFloats {
        private val chunks = ArrayList<FloatArray>()
        private var tail = 0
        var size = 0L
            private set
        val chunkSlack: Long get() = if (chunks.isEmpty()) 0 else (CHUNK_FLOATS - tail).toLong()

        fun add(source: FloatArray, from: Int, count: Int) {
            var offset = from
            var left = count
            while (left > 0) {
                if (chunks.isEmpty() || tail == CHUNK_FLOATS) {
                    chunks.add(FloatArray(CHUNK_FLOATS))
                    tail = 0
                }
                val take = min(left, CHUNK_FLOATS - tail)
                source.copyInto(chunks.last(), tail, offset, offset + take)
                tail += take
                offset += take
                left -= take
            }
            size += count
        }

        fun toArray(): FloatArray {
            check(size <= Int.MAX_VALUE) { "too many samples" }
            val result = FloatArray(size.toInt())
            var at = 0
            for (index in chunks.indices) {
                val count = if (index == chunks.lastIndex) tail else CHUNK_FLOATS
                chunks[index].copyInto(result, at, 0, count)
                at += count
            }
            chunks.clear()
            return result
        }
    }

    companion object {
        /** Input frames per resample call: about three seconds at 44.1 kHz. */
        const val DEFAULT_BLOCK_FRAMES = 1 shl 17

        /** Output is collected 256 k floats (1 MB) at a time. */
        const val CHUNK_FLOATS = 1 shl 18

        /** Kept equal to the native resampler's kResamplerZeroCrossings. */
        private const val ZERO_CROSSINGS = 32.0

        /**
         * How far either side of its centre one output sample reads, in input
         * samples — the native resampler's `reach`, from the same formula.
         */
        fun reachOf(inputRate: Int, outputRate: Int): Long {
            val cutoff = 0.5 * min(1.0, outputRate.toDouble() / inputRate.toDouble())
            val halfWidth = ZERO_CROSSINGS / (2.0 * cutoff)
            return ceil(halfWidth).toLong() + 1
        }

        /**
         * The input buffer's size: a block, plus the history and lookahead one
         * output sample's kernel needs, plus a phase's worth of alignment slack.
         */
        fun pendingCapacity(inputRate: Int, outputRate: Int, blockFrames: Int = DEFAULT_BLOCK_FRAMES): Int {
            val divisor = gcd(inputRate.toLong(), outputRate.toLong())
            val down = inputRate / divisor
            return (blockFrames + 4 * reachOf(inputRate, outputRate) + 2 * down).toInt()
        }

        /**
         * The most heap, in bytes, a streamed whole-track decode of [seconds] at
         * [inputRate] holds at once in this class: the input block, one block's
         * output, and the output twice over for the moment it is made contiguous.
         * Independent of the source's channel count and bit depth, which never
         * reach this side of the downmix.
         */
        fun peakBytesFor(seconds: Double, inputRate: Int, outputRate: Int): Long {
            val outputFloats = ceil(seconds * outputRate).toLong()
            val block = pendingCapacity(inputRate, outputRate).toLong()
            val blockOut = block * outputRate / inputRate + 1
            return 4L * (block + blockOut + 2 * outputFloats + CHUNK_FLOATS)
        }

        private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
    }
}
