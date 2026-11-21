/*
 * Modeled on Orchard's own AudioDecoder (https://github.com/SFG5453/Orchard),
 * scoped down to the platform MediaCodec path — Orchard prefers a native
 * libopus decode with the platform decoder as its documented fallback; this
 * only needs the fallback, since BitChord has no reason to carry a second
 * Opus decoder purely for background analysis.
 *
 * Copyright (C) 2026 Kushagra Singh
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.music.bitchord.playback.smart

import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Decodes a region of a cached track to mono float PCM, for [TrackAnalyzer].
 *
 * Only a region: a transition only ever reads the tail of the outgoing track
 * and the head of the incoming one, not either track in full, and decoding a
 * whole album's worth of audio to analyse thirty seconds of it would cost
 * battery for nothing.
 *
 * Everything here is best-effort. A codec that will not configure, a
 * container Android cannot parse, a region past the end — all return null,
 * and the caller falls back to no analysis, which the transition policy
 * already handles as its bottom rung.
 */
object AudioDecoder {

    private const val TAG = "BitChordAudioDecoder"
    private const val TIMEOUT_US = 10_000L

    /** What [SampleBuffer] starts at when the region's length is not known. */
    private const val INITIAL_SAMPLES = 1 shl 16

    /** Head-room over a region's length for the sync-sample seek and the decoder's priming. */
    private const val PRE_ROLL_SECONDS = 2.0

    /** The most [SampleBuffer] presizes to; anything longer grows by half. */
    private const val MAX_PRESIZE_SAMPLES = 1 shl 25

    /** Decoded mono PCM at the container's own sample rate; the caller resamples. */
    data class Pcm(val samples: FloatArray, val sampleRate: Double)

    /**
     * Decoded planar stereo PCM at the container's own sample rate.
     *
     * Planar rather than interleaved because the only consumer is the vocal
     * front end, which wants one array per channel; a mono source is widened
     * by sharing the same samples on both sides, which is what the model was
     * trained to see for centre-panned material anyway.
     */
    data class StereoPcm(val left: FloatArray, val right: FloatArray, val sampleRate: Double)

    /**
     * Reads the audio duration a fully-cached container advertises, without
     * decoding it. Queue metadata isn't always trustworthy, so this is the
     * fallback [TrackAnalyzer] reaches for when a track's own duration is
     * missing or non-finite.
     */
    fun containerDurationSeconds(source: MediaDataSource): Double? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(source)
            (0 until extractor.trackCount)
                .mapNotNull { index ->
                    val format = extractor.getTrackFormat(index)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: return@mapNotNull null
                    if (!mime.startsWith("audio/") || !format.containsKey(MediaFormat.KEY_DURATION)) {
                        return@mapNotNull null
                    }
                    format.getLong(MediaFormat.KEY_DURATION).takeIf { it > 0 }?.div(1_000_000.0)
                }
                .maxOrNull()
                ?.takeIf { it.isFinite() && it > 0 }
        } catch (error: Exception) {
            Log.w(TAG, "Could not read duration from cached media", error)
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * Decodes [startSeconds] to [endSeconds] of [source], downmixed to mono at
     * the container's native rate.
     *
     * The extractor seeks to the closest sync sample at or before the
     * requested start, so a little more audio than asked for may come back at
     * the front; the caller is given the real start via the returned offset
     * so frame indices still map to true track times.
     *
     * [abort] is polled once per pass of the decode loop; answering true
     * abandons the decode as if it had failed. A whole-track decode is the
     * longest single thing analysis does, and it holds tens of megabytes of
     * PCM while it runs, so the caller that no longer wants the answer wants
     * it stopped rather than merely ignored.
     */
    fun decodeRegion(
        source: MediaDataSource,
        startSeconds: Double,
        endSeconds: Double,
        abort: () -> Boolean = { false },
    ): Pair<Pcm, Double>? {
        val samples = SampleBuffer(endSeconds - startSeconds)
        var scratch = FloatArray(0)
        val decoded = decodeRaw(source, startSeconds, endSeconds, abort) { buffer, info, channels, rate ->
            scratch = scratchFor(scratch, info, channels)
            val frames = toMono(buffer, info, channels, scratch)
            samples.add(scratch, frames, rate)
        } ?: return null
        return Pcm(samples.toArray(), decoded.first) to decoded.second
    }

    /**
     * As [decodeRegion], but resampled to [outputRate] while it decodes, so the
     * region is never held at the container's own rate.
     *
     * This is the whole-track pass's decode. Decoded first and resampled after,
     * the whole track sat on the heap as mono float at the container's rate —
     * ten megabytes a minute at 44.1 kHz, twenty-three at 96 kHz, an hour-long
     * mix well over half a gigabyte — and then again as the list of chunks it
     * was flattened from. Streamed, the only buffer that scales with the track
     * is the output, at [outputRate]: about 2.6 MB a minute at the analyser's
     * 11 kHz, whatever the source was. See [StreamingResampler] for why the
     * samples are the ones a single resample call would produce.
     *
     * [resample] is the whole-array resampler the stream is cut into, normally
     * [TrackFeatures.resample]; a block it fails on fails the decode, and so
     * does an output rate that changes mid-stream. The returned
     * [Pcm.sampleRate] is [outputRate], or the container's own rate when the
     * two are within a hertz and nothing was converted.
     */
    fun decodeRegionResampled(
        source: MediaDataSource,
        startSeconds: Double,
        endSeconds: Double,
        outputRate: Int,
        resample: (FloatArray, Double, Double) -> FloatArray?,
        abort: () -> Boolean = { false },
    ): Pair<Pcm, Double>? {
        var stream: StreamingResampler? = null
        var streamRate = 0
        var broken = false
        var scratch = FloatArray(0)
        val decoded = decodeRaw(source, startSeconds, endSeconds, { broken || abort() }) { buffer, info, channels, rate ->
            if (broken) return@decodeRaw
            val current = stream ?: StreamingResampler(rate, outputRate, resample).also {
                stream = it
                streamRate = rate
            }
            // A rate change mid-stream would put two rates in one buffer.
            // Containers do not do this; a decoder that did is refused rather
            // than resampled at the wrong ratio.
            if (rate != streamRate) {
                Log.w(TAG, "Output rate changed mid-decode ($streamRate -> $rate); giving up")
                broken = true
                return@decodeRaw
            }
            scratch = scratchFor(scratch, info, channels)
            val frames = toMono(buffer, info, channels, scratch)
            if (!current.push(scratch, frames)) broken = true
        } ?: return null
        // A rate change on the very last buffer sets this after the loop's
        // last abort check, so the decode above can still come back "done".
        if (broken) return null
        val finished = stream ?: return null
        val samples = finished.finish() ?: return null
        return Pcm(samples, finished.rate.toDouble()) to decoded.second
    }

    /**
     * As [decodeRegion], but keeping the two channels apart.
     *
     * Only the vocal front end needs this: open-unmix was trained on stereo,
     * and handing it a duplicated mono mix throws away the very stereo
     * information it uses to tell a centred vocal from the instruments
     * around it.
     */
    fun decodeRegionStereo(
        source: MediaDataSource,
        startSeconds: Double,
        endSeconds: Double,
        abort: () -> Boolean = { false },
    ): Pair<StereoPcm, Double>? {
        val left = SampleBuffer(endSeconds - startSeconds)
        val right = SampleBuffer(endSeconds - startSeconds)
        var leftScratch = FloatArray(0)
        var rightScratch = FloatArray(0)
        val decoded = decodeRaw(source, startSeconds, endSeconds, abort) { buffer, info, channels, rate ->
            leftScratch = scratchFor(leftScratch, info, channels)
            rightScratch = scratchFor(rightScratch, info, channels)
            val frames = toStereo(buffer, info, channels, leftScratch, rightScratch)
            left.add(leftScratch, frames, rate)
            right.add(rightScratch, frames, rate)
        } ?: return null
        return StereoPcm(left.toArray(), right.toArray(), decoded.first) to decoded.second
    }

    /**
     * As [decodeRegion], but handing each decoded mono chunk to [onChunk] instead
     * of keeping it, so a caller that only reduces the audio never holds the
     * region. Returns the output rate paired with the region's real start.
     */
    fun streamRegion(
        source: MediaDataSource,
        startSeconds: Double,
        endSeconds: Double,
        abort: () -> Boolean = { false },
        onChunk: (samples: FloatArray, atSeconds: Double, sampleRate: Int) -> Unit,
    ): Pair<Double, Double>? =
        decodeRaw(source, startSeconds, endSeconds, abort) { buffer, info, channels, rate ->
            val mono = FloatArray(info.size / 2 / max(1, channels))
            toMono(buffer, info, channels, mono)
            onChunk(mono, info.presentationTimeUs / 1_000_000.0, rate)
        }

    /**
     * One growable buffer of decoded samples.
     *
     * The decoder hands its output over one buffer at a time and the caller
     * wants the region contiguous, and the obvious way to get there — collect
     * the chunks, then copy them into one array — holds the region twice.
     *
     * So the samples go into one buffer, sized for the region the moment the
     * first output buffer says what rate it is at: [seconds] of audio plus
     * [PRE_ROLL_SECONDS] for the sync-sample seek and the decoder's priming. A
     * decoder that produces more than that still grows, by half rather than
     * double.
     */
    private class SampleBuffer(private val seconds: Double) {
        private var samples = FloatArray(0)
        private var size = 0

        fun add(chunk: FloatArray, count: Int, rate: Int) {
            if (samples.isEmpty()) {
                val expected = if (seconds.isFinite() && seconds > 0 && rate > 0) {
                    ((seconds + PRE_ROLL_SECONDS) * rate).coerceAtMost(MAX_PRESIZE_SAMPLES.toDouble()).toInt()
                } else {
                    INITIAL_SAMPLES
                }
                samples = FloatArray(max(expected, count))
            }
            if (size + count > samples.size) {
                var next = samples.size
                while (next < size + count) next += max(next shr 1, INITIAL_SAMPLES)
                samples = samples.copyOf(next)
            }
            chunk.copyInto(samples, size, 0, count)
            size += count
        }

        fun toArray(): FloatArray = if (size == samples.size) samples else samples.copyOf(size)
    }

    /**
     * Runs the decode loop, handing each output buffer to [onBuffer], and
     * returns the output sample rate paired with the region's real start.
     *
     * Shared by the mono and stereo entry points so there is one dequeue loop
     * to get right rather than two that can drift apart; all that differs
     * between them is how a buffer is reduced, which is what [onBuffer] owns.
     */
    private fun decodeRaw(
        source: MediaDataSource,
        startSeconds: Double,
        endSeconds: Double,
        abort: () -> Boolean,
        onBuffer: (ByteBuffer, MediaCodec.BufferInfo, Int, Int) -> Unit,
    ): Pair<Double, Double>? {
        if (endSeconds <= startSeconds) return null
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(source)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null

            val startUs = (startSeconds * 1_000_000).toLong()
            val endUs = (endSeconds * 1_000_000).toLong()
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            codec = runCatching { MediaCodec.createDecoderByType(mime) }
                .onFailure { Log.w(TAG, "No decoder for $mime", it) }
                .getOrNull() ?: return null
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var outputChannels = format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 1
            var outputRate = format.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 0
            // 16-bit until the decoder's own output format says otherwise. The
            // track format's encoding is what was asked of the decoder, not what
            // it will produce, and MediaCodec reports the output format before
            // the first output buffer.
            var outputEncoding = PcmNarrowing.PCM_16BIT
            val narrowed = Narrowed()
            var actualStartSeconds = -1.0
            var sawFirstSample = false
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                // Polled at the top of the loop rather than only around the
                // model passes that follow: everything decoded so far goes with
                // it, and on a long track that is most of what the analysis
                // costs in both time and heap. `finally` still tears the codec
                // and extractor down.
                if (abort()) return null
                // Every free input slot is filled, and every ready output
                // drained, before anything waits. Feeding one packet and then
                // blocking for its frame left the loop waiting on the codec's
                // latency once per 20 ms Opus frame: about 6 ms each, which is
                // most of what decoding a region cost. It only blocks now when
                // neither side could move.
                var progressed = false
                while (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(0L)
                    if (inputIndex < 0) break
                    val inputBuffer = codec.getInputBuffer(inputIndex) ?: break
                    progressed = true
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    val sampleTimeUs = extractor.sampleTime
                    if (sampleSize < 0 || (sampleTimeUs in 0..Long.MAX_VALUE && sampleTimeUs > endUs)) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTimeUs, 0)
                        extractor.advance()
                    }
                }

                var timeout = if (progressed) 0L else TIMEOUT_US
                while (!outputDone) {
                    val outputIndex = codec.dequeueOutputBuffer(bufferInfo, timeout)
                    if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    timeout = 0L
                    when (outputIndex) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val newFormat = codec.outputFormat
                            outputRate = newFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: outputRate
                            outputChannels = newFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: outputChannels
                            outputEncoding = PcmNarrowing.outputEncodingOf(newFormat.intOrNull(MediaFormat.KEY_PCM_ENCODING))
                            if (!PcmNarrowing.supports(outputEncoding)) {
                                Log.w(TAG, "Unsupported PCM encoding $outputEncoding; giving up")
                                return null
                            }
                        }
                        MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                        else -> if (outputIndex >= 0) {
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                            }
                            if (bufferInfo.size > 0) {
                                if (!sawFirstSample) {
                                    actualStartSeconds = bufferInfo.presentationTimeUs / 1_000_000.0
                                    sawFirstSample = true
                                }
                                codec.getOutputBuffer(outputIndex)?.let { output ->
                                    if (outputEncoding == PcmNarrowing.PCM_16BIT) {
                                        onBuffer(output, bufferInfo, outputChannels, outputRate)
                                    } else {
                                        val (shorts, info) = narrowed.of(output, bufferInfo, outputEncoding)
                                        onBuffer(shorts, info, outputChannels, outputRate)
                                    }
                                }
                                if (bufferInfo.presentationTimeUs > endUs) outputDone = true
                            }
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }

            if (!sawFirstSample || outputRate <= 0) return null
            return outputRate.toDouble() to actualStartSeconds
        } catch (error: Exception) {
            Log.w(TAG, "Region decode failed", error)
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * A decoder's non-16-bit output, narrowed to 16-bit PCM in a reused buffer.
     *
     * Everything downstream reads 16-bit samples, which is what MediaCodec
     * produces unless asked otherwise — but a hi-res FLAC's track format can
     * carry a float or 24-bit `pcm-encoding` through to the decoder, and read
     * as shorts that is noise with twice or one and a half times the frames:
     * an analysis of garbage, held in a buffer sized for audio that is not
     * there. Narrowed here instead, so the 16-bit path stays exactly what it
     * was. Sixteen bits is far more resolution than the analysis reads.
     */
    private class Narrowed {
        private val info = MediaCodec.BufferInfo()
        private var raw = ByteArray(0)
        private var narrow = ByteArray(0)
        private var wrapped = ByteBuffer.wrap(narrow)

        /**
         * Bulk-copied out of the codec's buffer and converted with plain array
         * indexing ([PcmNarrowing.narrow]): a per-sample read from a direct
         * buffer costs a bounds check and a call for every sample.
         */
        fun of(source: ByteBuffer, from: MediaCodec.BufferInfo, encoding: Int): Pair<ByteBuffer, MediaCodec.BufferInfo> {
            val width = PcmNarrowing.widthOf(encoding)
            val count = from.size / width
            val bytes = count * width
            if (raw.size < bytes) raw = ByteArray(bytes)
            if (narrow.size < count * 2) {
                narrow = ByteArray(count * 2)
                wrapped = ByteBuffer.wrap(narrow)
            }
            source.duplicate().apply {
                position(from.offset)
                limit(from.offset + bytes)
            }.get(raw, 0, bytes)
            PcmNarrowing.narrow(raw, count, encoding, narrow)
            wrapped.clear()
            wrapped.limit(count * 2)
            info.set(0, count * 2, from.presentationTimeUs, from.flags)
            return wrapped to info
        }
    }

    /** A scratch array big enough for one output buffer's frames, reused while it is. */
    private fun scratchFor(current: FloatArray, info: MediaCodec.BufferInfo, channels: Int): FloatArray {
        val frames = info.size / 2 / max(1, channels)
        return if (current.size >= frames) current else FloatArray(frames)
    }

    /**
     * Downmixes one 16-bit PCM output buffer to mono float in [-1, 1], into
     * [into], and returns the frame count. [into] is reused across buffers:
     * a whole-track decode is thousands of them.
     */
    private fun toMono(buffer: ByteBuffer, info: MediaCodec.BufferInfo, channels: Int, into: FloatArray): Int {
        val safeChannels = max(1, channels)
        val shorts = buffer.duplicate().apply {
            order(ByteOrder.LITTLE_ENDIAN)
            position(info.offset)
            limit(info.offset + info.size)
        }.asShortBuffer()
        val frames = shorts.remaining() / safeChannels
        val divisor = safeChannels.toFloat()
        for (index in 0 until frames) {
            var sum = 0
            for (channel in 0 until safeChannels) sum += shorts.get()
            into[index] = (sum / divisor) / 32768f
        }
        return frames
    }

    /**
     * Splits one 16-bit PCM output buffer into planar left/right float in
     * [-1, 1], into [left] and [right], and returns the frame count.
     *
     * A mono source is widened by giving both sides the same samples, and
     * anything above two channels keeps only the first two: the model's input
     * is stereo, and a downmix of a 5.1 track would put the centre channel —
     * where the vocal usually is — into both sides at half level, which is
     * the opposite of helpful for telling a vocal apart from the bed.
     */
    private fun toStereo(
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        channels: Int,
        left: FloatArray,
        right: FloatArray,
    ): Int {
        val safeChannels = max(1, channels)
        val shorts = buffer.duplicate().apply {
            order(ByteOrder.LITTLE_ENDIAN)
            position(info.offset)
            limit(info.offset + info.size)
        }.asShortBuffer()
        val frames = shorts.remaining() / safeChannels
        for (index in 0 until frames) {
            val first = shorts.get()
            val second = if (safeChannels > 1) shorts.get() else first
            for (skip in 2 until safeChannels) shorts.get()
            left[index] = first / 32768f
            right[index] = second / 32768f
        }
        return frames
    }

    private fun MediaFormat.intOrNull(key: String): Int? = if (containsKey(key)) getInteger(key) else null
}
