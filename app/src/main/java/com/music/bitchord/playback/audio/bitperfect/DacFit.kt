/*
 * Copyright (C) 2026 BitChord Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.music.bitchord.playback.audio.bitperfect

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.playback.audio.ExclusiveSession
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A track fitted to a DAC that cannot take it as it is, on its way into an
 * [ExclusiveSession] that was opened at [target] instead of the track's own
 * format. "Convert for lower-spec DACs" only: never bit-perfect, always
 * reported as converted.
 *
 * The session sees a plain integer track at [target]; this holds the
 * [DacFormatConverter] in front of it and the converted audio the session
 * has not taken yet, so a refused write never loses or re-converts anything.
 * Buffers are reused, so steady playback allocates nothing.
 */
@OptIn(UnstableApi::class)
internal class DacFit(
    val source: Format,
    val target: Format,
    private val targetBits: Int,
) {
    private val converter = DacFormatConverter(
        encoding = source.pcmEncoding,
        channels = source.channelCount,
        inputRate = source.sampleRate,
        outputRate = target.sampleRate,
        validBits = targetBits,
        containerBits = targetBits,
    )
    private val inFrameBytes = BitPerfectPcm.bytesPerSample(source.pcmEncoding) * source.channelCount
    private val outFrameBytes = targetBits / 8 * source.channelCount
    private var out: ByteBuffer = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)

    /**
     * Converts [input] whole and offers it to [session]. Returns false, with
     * [input] untouched, only while earlier converted audio is still waiting.
     */
    fun write(session: ExclusiveSession, input: ByteBuffer, presentationTimeUs: Long): Boolean {
        if (out.hasRemaining()) {
            if (!session.write(out, presentationTimeUs, false)) return false
            out.position(out.limit())
        }
        if (!input.hasRemaining()) return true
        // Room for every frame this input can produce, plus the filter's slack.
        val inFrames = input.remaining() / inFrameBytes
        val outFrames = (inFrames.toLong() * target.sampleRate / source.sampleRate + SLACK_FRAMES).toInt()
        val needed = outFrames * outFrameBytes
        if (out.capacity() < needed) out = ByteBuffer.allocate(needed + needed / 2).order(ByteOrder.LITTLE_ENDIAN)
        out.clear()
        converter.process(input, out)
        out.flip()
        // A partial frame at the end cannot be converted; it is dropped with the
        // buffer, as it would be on any PCM path.
        input.position(input.limit())
        if (out.hasRemaining() && session.write(out, presentationTimeUs, false)) out.position(out.limit())
        return true
    }

    /** After a seek or flush: the filter's history belongs to audio that is gone. */
    fun reset() {
        converter.reset()
        out.clear().limit(0)
    }

    val label: String
        get() = "${target.sampleRate / 1000.0} kHz/$targetBits-bit, converted from " +
            "${source.sampleRate / 1000.0} kHz/${BitPerfectPcm.sourceBits(source.pcmEncoding) ?: "?"}-bit"

    companion object {
        private const val SLACK_FRAMES = 64

        /** At most this many formats are tried: each attempt opens the device. */
        const val MAX_ATTEMPTS = 3

        /** [source] as the plain integer track a session is opened with to carry it at [rate]/[bits]. */
        fun targetFormat(source: Format, rate: Int, bits: Int): Format = source.buildUpon()
            .setSampleRate(rate)
            .setPcmEncoding(
                when (bits) {
                    16 -> C.ENCODING_PCM_16BIT
                    24 -> C.ENCODING_PCM_24BIT
                    else -> C.ENCODING_PCM_32BIT
                },
            )
            .build()

        /**
         * The (rate, bits) pairs to try for [source], best first, from what a
         * DAC [offers] (rate to the integer depths it takes there): the track's
         * own rate at a lower depth, then other rates as [BitPerfectPcm.rankRates]
         * orders them. The track's own format itself is never listed.
         */
        fun candidates(source: Format, offers: Map<Int, Set<Int>>): List<Pair<Int, Int>> {
            val sourceBits = BitPerfectPcm.sourceBits(source.pcmEncoding) ?: return emptyList()
            return BitPerfectPcm.rankRates(source.sampleRate, offers.keys).mapNotNull { rate ->
                val bits = BitPerfectPcm.dacBits(sourceBits, offers[rate].orEmpty()) ?: return@mapNotNull null
                (rate to bits).takeUnless { rate == source.sampleRate && bits >= sourceBits }
            }.take(MAX_ATTEMPTS)
        }
    }
}
