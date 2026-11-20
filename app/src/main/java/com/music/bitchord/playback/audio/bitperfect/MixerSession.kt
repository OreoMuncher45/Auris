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

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import com.music.bitchord.playback.AudioOutputStatus
import com.music.bitchord.playback.audio.BitPerfectMixer
import com.music.bitchord.playback.audio.ExclusiveSession
import com.music.bitchord.playback.audio.ListenerVolumePolicy
import com.music.bitchord.playback.audio.TransportType
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Android 14's bit-perfect mixer at the track's own integer precision.
 *
 * [BitPerfectMixer] can ask the framework for a bit-perfect mix, but only in
 * the format the app's AudioTrack writes, and Media3's sink writes Float32 or
 * PCM16. USB DACs offer their bit-perfect mixes in integer PCM, so a 24-bit
 * track never matched and the request was refused nearly everywhere. This
 * session owns an AudioTrack of its own in the integer format the DAC offers
 * at the track's rate (the narrowest that loses nothing, as upstream
 * BitChord's MixerTransport picks it) and writes the decoded samples into it
 * with lossless changes of representation only ([BitPerfectPcm.convert]).
 *
 * The request itself, its read-back and its withdrawal stay v2's
 * [BitPerfectMixer]: a true return is not taken as the path being live until
 * the framework names the same format back.
 *
 * Volume: the mixer applies none on this path, so the listener's level is a
 * sample gain, exactly as on a direct-USB DAC without a volume control. At
 * full level the samples are untouched.
 *
 * Shared by both crossfade sinks; only the audible one opens it.
 */
@OptIn(UnstableApi::class)
class MixerSession(
    context: Context,
    private val audioAttributes: AudioAttributes,
) : ExclusiveSession {

    override val pathLabel: String = "Android bit-perfect mixer"
    override val pathDetail: String = "through Android 14's bit-perfect mixer"

    /** The framework mixer is still in the path (in its bit-perfect mode): not a direct transport. */
    override val transport: TransportType = TransportType.AUDIO_TRACK

    private val manager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val lock = Any()

    @Volatile private var track: AudioTrack? = null
    /**
     * Set when the track refused a write with an error rather than a full
     * queue (a dead object after a route change, say). [isStreamAlive] then
     * says so, and the sink falls back instead of retrying it as backpressure.
     */
    @Volatile private var trackFailed = false
    @Volatile override var lastFailureReason: String? = null
        private set
    @Volatile private var retryable = false

    private var rateHz = 0
    private var channels = 0
    private var wireBits = 0
    private var sourceEncoding = C.ENCODING_INVALID

    /** Converted PCM the track has not taken yet. Reused; grown, never shrunk. */
    private var scratch: ByteBuffer = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)
    private var pending: ByteBuffer = scratch

    @Volatile private var paused = false
    @Volatile private var gain = 1f
    @Volatile private var exact = true

    private var framesSubmitted = 0L
    private var startMediaTimeUs = 0L
    private var startNeedsInit = true

    /** True once this session has opened the framework's preference and the track. */
    val isOpen: Boolean get() = track != null

    override fun canOpen(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        return BitPerfectMixer.usbOutput(manager) != null
    }

    override fun dacOffers(format: Format): Map<Int, Set<Int>> =
        BitPerfectMixer.bitPerfectIntegerFormats(manager, BitPerfectMixer.usbOutput(manager), format.channelCount)

    override fun configure(format: Format, bitDepthHint: Int?): Boolean = synchronized(lock) {
        closeLocked()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return fail("Android 14+ required", retryable = false)
        }
        if (format.sampleMimeType != MimeTypes.AUDIO_RAW || format.sampleRate <= 0) {
            return fail("Not a PCM track", retryable = false)
        }
        if (format.channelCount !in 1..2) return fail("Multichannel track", retryable = false)
        val sourceBits = BitPerfectPcm.sourceBits(format.pcmEncoding)
            ?: return fail("Unsupported PCM encoding", retryable = false)
        val device = BitPerfectMixer.usbOutput(manager) ?: return fail("No USB DAC", retryable = true)
        val offered = BitPerfectMixer.bitPerfectIntegerBits(manager, device, format.sampleRate, format.channelCount)
        val bits = BitPerfectPcm.outputBits(sourceBits, offered)
            ?: return fail(
                if (offered.isEmpty()) {
                    "DAC has no bit-perfect mix at ${format.sampleRate} Hz"
                } else {
                    "DAC offers ${offered.sorted().joinToString("/")}-bit at ${format.sampleRate} Hz, track is $sourceBits-bit"
                },
                retryable = false,
            )
        val encoding = BitPerfectMixer.integerEncoding(bits)
        val state = BitPerfectMixer.apply(
            manager = manager,
            device = device,
            enabled = true,
            sampleRateHz = format.sampleRate,
            channelCount = format.channelCount,
            sinkEncoding = encoding,
            audioAttributes = audioAttributes,
        )
        if (state?.active != true) return fail(state?.detail ?: "Refused by the audio framework", retryable = false)

        val channelMask = if (format.channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val frameBytes = bits / 8 * format.channelCount
        val minBuffer = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, encoding)
        // A quarter second, as upstream sizes it: deep enough that the screen-
        // off playback thread is not woken for every burst.
        val bufferBytes = maxOf(minBuffer * 2, format.sampleRate / 4 * frameBytes)
        val built = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(format.sampleRate)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull()
        if (built == null || built.state != AudioTrack.STATE_INITIALIZED) {
            built?.release()
            BitPerfectMixer.clear(manager)
            return fail("AudioTrack refused ${format.sampleRate} Hz $bits-bit", retryable = false)
        }
        built.setPreferredDevice(device)
        track = built
        rateHz = format.sampleRate
        channels = format.channelCount
        wireBits = bits
        sourceEncoding = format.pcmEncoding
        exact = true
        framesSubmitted = 0
        startNeedsInit = true
        pending = scratch.also { it.clear().limit(0) }
        lastFailureReason = null
        trackFailed = false
        if (!paused) built.play()
        Log.i(TAG, "open ${format.sampleRate} Hz $bits-bit (source $sourceBits-bit) on ${device.productName}")
        true
    }

    override fun currentRateHz(): Int = if (track != null) rateHz else 0

    override fun activeWireBits(): Int = if (track != null) wireBits else 0

    override fun failureIsRetryable(): Boolean = retryable

    override fun isPaused(): Boolean = paused

    override fun setPaused(value: Boolean) {
        paused = value
        val current = track ?: return
        runCatching { if (value) current.pause() else current.play() }
    }

    override fun setListenerLevel(level: Float) {
        gain = ListenerVolumePolicy.perceptualGain(level.coerceIn(0f, 1f))
    }

    override fun usesHardwareVolume(): Boolean = false

    override fun softwareGain(): Float = gain

    override fun hardwareOutputLevelDb(): Double? = null

    override fun publishActive(wire: String, rateHz: Int) {
        AudioOutputStatus.publishBitPerfectMixer(BitPerfectMixer.State(true, "$wire / $rateHz Hz, integer PCM"))
    }

    override fun write(buffer: ByteBuffer, presentationTimeUs: Long, isFloatBuffer: Boolean): Boolean {
        val current = track ?: return false
        if (paused) return false
        // What the track did not take last time goes first; the new buffer
        // waits for it, untouched, so nothing is reordered or dropped.
        if (trackFailed) return false
        if (pending.hasRemaining()) {
            val written = current.write(pending, pending.remaining(), AudioTrack.WRITE_NON_BLOCKING)
            if (written < 0) return failWrite(written)
            if (pending.hasRemaining()) return false
        }
        if (!buffer.hasRemaining()) return true
        if (startNeedsInit) {
            startMediaTimeUs = presentationTimeUs.coerceAtLeast(0L)
            startNeedsInit = false
        }
        val inBytes = BitPerfectPcm.bytesPerSample(sourceEncoding) * channels
        val frames = buffer.remaining() / inBytes
        val outBytes = frames * wireBits / 8 * channels
        if (scratch.capacity() < outBytes) {
            scratch = ByteBuffer.allocate(outBytes + outBytes / 2).order(ByteOrder.LITTLE_ENDIAN)
        }
        val out = scratch
        out.clear()
        val wasExact = BitPerfectPcm.convert(buffer, sourceEncoding, channels, wireBits, out)
        out.flip()
        val level = gain
        if (level < 1f - 1e-6f) scaleInPlace(out, wireBits, level)
        if (!wasExact && exact) {
            exact = false
            AudioOutputStatus.publishOutputExactness(false, "$pathLabel: this source isn't lossless PCM")
        }
        framesSubmitted += frames
        pending = out
        val written = current.write(pending, pending.remaining(), AudioTrack.WRITE_NON_BLOCKING)
        // The buffer is converted and held in [pending] either way, so it is
        // taken; a failed track is reported on the next write.
        if (written < 0) failWrite(written)
        return true
    }

    /** Marks the track failed after a write error; always false, for [write]. */
    private fun failWrite(code: Int): Boolean {
        trackFailed = true
        lastFailureReason = "$pathLabel: AudioTrack write failed ($code)"
        retryable = true
        return false
    }

    override fun isStreamAlive(): Boolean = !trackFailed && track?.state == AudioTrack.STATE_INITIALIZED

    override fun restartIfStopped(): Boolean = false

    override fun getCurrentPositionUs(): Long {
        val current = track ?: return AudioSink.CURRENT_POSITION_NOT_SET
        if (startNeedsInit || rateHz <= 0) return AudioSink.CURRENT_POSITION_NOT_SET
        val played = current.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
        return startMediaTimeUs + played * C.MICROS_PER_SECOND / rateHz
    }

    override fun hasPendingData(): Boolean {
        val current = track ?: return false
        if (pending.hasRemaining()) return true
        return framesSubmitted > (current.playbackHeadPosition.toLong() and 0xFFFF_FFFFL)
    }

    override fun flush() {
        val current = track ?: return
        runCatching {
            current.pause()
            current.flush()
        }
        pending = scratch.also { it.clear().limit(0) }
        framesSubmitted = 0
        startNeedsInit = true
        // [paused] is the listener's transport state (see PlaybackService's
        // onPlayWhenReadyChanged), so it is kept: a seek while paused stays
        // paused, and the track restarts only if the listener was playing.
        if (!paused) runCatching { current.play() }
    }

    override fun handleDiscontinuity() = Unit

    override fun prepareForNextItem() = flush()

    override fun reset() {
        synchronized(lock) { closeLocked() }
    }

    override fun release() = reset()

    private fun closeLocked() {
        val current = track ?: return
        track = null
        runCatching { current.pause() }
        runCatching { current.flush() }
        current.release()
        BitPerfectMixer.clear(manager)
        AudioOutputStatus.publishBitPerfectMixer(null)
        // A fresh empty buffer, not [scratch]: teardown runs off the playback
        // thread, which may be converting into [scratch] right now, and
        // moving its limit under it would overflow that write.
        pending = EMPTY_PCM
        framesSubmitted = 0
        startNeedsInit = true
    }

    private fun fail(reason: String, retryable: Boolean): Boolean {
        Log.i(TAG, "unavailable: $reason")
        lastFailureReason = "$pathLabel: $reason"
        this.retryable = retryable
        return false
    }

    internal companion object {
        private const val TAG = "MixerSession"

        /** Scales little-endian integer PCM in place; only used below full level. */
        internal fun scaleInPlace(pcm: ByteBuffer, bits: Int, gain: Float) {
            val g = gain.toDouble()
            var i = pcm.position()
            val end = pcm.limit()
            when (bits) {
                16 -> while (i + 1 < end) {
                    val v = pcm.getShort(i) * g
                    pcm.putShort(i, Math.round(v).coerceIn(-32768L, 32767L).toInt().toShort())
                    i += 2
                }
                24 -> while (i + 2 < end) {
                    var s = (pcm.get(i).toInt() and 0xFF) or
                        ((pcm.get(i + 1).toInt() and 0xFF) shl 8) or
                        (pcm.get(i + 2).toInt() shl 16)
                    s = Math.round(s * g).coerceIn(-0x800000L, 0x7FFFFFL).toInt()
                    pcm.put(i, s.toByte())
                    pcm.put(i + 1, (s shr 8).toByte())
                    pcm.put(i + 2, (s shr 16).toByte())
                    i += 3
                }
                else -> while (i + 3 < end) {
                    val v = pcm.getInt(i) * g
                    pcm.putInt(i, Math.round(v).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())
                    i += 4
                }
            }
        }
    }
}
