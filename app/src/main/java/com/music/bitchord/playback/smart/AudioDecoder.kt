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

    /** Decoded mono PCM at the container's own sample rate; the caller resamples. */
    data class Pcm(val samples: FloatArray, val sampleRate: Double)

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
     */
    fun decodeRegion(source: MediaDataSource, startSeconds: Double, endSeconds: Double): Pair<Pcm, Double>? {
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
            var actualStartSeconds = -1.0
            var sawFirstSample = false
            var inputDone = false
            var outputDone = false
            val chunks = ArrayList<FloatArray>()

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        if (inputBuffer == null) {
                            // Nothing to feed this cycle; try again next iteration.
                        } else {
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
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = codec.outputFormat
                        outputRate = newFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: outputRate
                        outputChannels = newFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: outputChannels
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
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
                                chunks += toMono(output, bufferInfo, outputChannels)
                            }
                            if (bufferInfo.presentationTimeUs > endUs) outputDone = true
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }

            if (!sawFirstSample || outputRate <= 0) return null
            val samples = FloatArray(chunks.sumOf { it.size })
            var offset = 0
            for (chunk in chunks) {
                chunk.copyInto(samples, offset)
                offset += chunk.size
            }
            return Pcm(samples, outputRate.toDouble()) to actualStartSeconds
        } catch (error: Exception) {
            Log.w(TAG, "Region decode failed", error)
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** Downmixes one 16-bit PCM output buffer to mono float in [-1, 1]. */
    private fun toMono(buffer: ByteBuffer, info: MediaCodec.BufferInfo, channels: Int): FloatArray {
        val safeChannels = max(1, channels)
        val shorts = buffer.duplicate().apply {
            order(ByteOrder.LITTLE_ENDIAN)
            position(info.offset)
            limit(info.offset + info.size)
        }.asShortBuffer()
        val frames = shorts.remaining() / safeChannels
        val mono = FloatArray(frames)
        val frame = ShortArray(safeChannels)
        for (index in 0 until frames) {
            shorts.get(frame, 0, safeChannels)
            var sum = 0
            for (value in frame) sum += value
            mono[index] = (sum / safeChannels.toFloat()) / 32768f
        }
        return mono
    }

    private fun MediaFormat.intOrNull(key: String): Int? = if (containsKey(key)) getInteger(key) else null
}
