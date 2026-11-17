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
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import com.music.bitchord.playback.AudioOutputStatus
import com.music.bitchord.playback.audio.ExclusiveSession
import com.music.bitchord.playback.audio.ListenerVolumePolicy
import com.music.bitchord.playback.audio.TransportType
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** JNI over `exclusive_output.cpp` (from upstream BitChord). Handles are opaque ids, never pointers. */
internal object ExclusiveNative {
    /** False when the library is missing for this ABI; the path then never opens. */
    val loaded: Boolean = runCatching { System.loadLibrary("bitchord_exclusive") }.isSuccess

    external fun open(rate: Int, channels: Int, bits: Int, device: Int): Long
    external fun bits(handle: Long): Int
    external fun device(handle: Long): Int
    external fun capacity(handle: Long): Int
    external fun write(handle: Long, data: ByteBuffer, offset: Int, count: Int): Int
    external fun start(handle: Long)
    external fun pause(handle: Long)
    external fun flush(handle: Long)
    external fun frames(handle: Long): Long
    external fun close(handle: Long)
}

/**
 * Exact-rate PCM through an AAudio exclusive (MMAP) stream, for a player whose
 * DAC is built in: a DAP's headphone or line output. The native side opens the
 * stream at the track's own rate, channel count and depth with every
 * conversion disabled, and keeps it only if Android granted exclusive mode
 * exactly as asked; a shared stream would be mixed, so it is refused.
 *
 * The stream is pinned to the wired output, so a DAP whose default route is
 * its speaker still plays through its DAC. Unplugging it ends the session.
 *
 * Battery: an MMAP stream is serviced every hardware burst (a few ms), and the
 * platform's offload or deep-buffer paths are not used while it plays. That is
 * the price of the path, paid only while it plays: a pause stops the stream's
 * callbacks, and turning bit-perfect off or picking another path closes it.
 *
 * Volume: nothing downstream applies the listener's level, so it is a sample
 * gain; at full level the samples are untouched.
 */
@OptIn(UnstableApi::class)
class AAudioExclusiveSession(context: Context) : ExclusiveSession {

    override val pathLabel: String = "AAudio exclusive"
    override val pathDetail: String = "via an AAudio exclusive stream, mixer not in the path"
    override val transport: TransportType = TransportType.AAUDIO_EXCLUSIVE

    private val manager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val lock = Any()

    @Volatile private var handle = 0L
    @Volatile override var lastFailureReason: String? = null
        private set
    @Volatile private var retryable = false
    @Volatile private var lostDevice = false
    private var deviceId = 0

    private var rateHz = 0
    private var channels = 0
    private var wireBits = 0
    private var sourceEncoding = C.ENCODING_INVALID

    /** Converted PCM the stream has not taken yet. Direct (the native side reads its address); reused. */
    private var scratch: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.LITTLE_ENDIAN)
    private var pending: ByteBuffer = scratch

    @Volatile private var paused = false
    @Volatile private var gain = 1f
    private var exact = true
    private var started = false
    private var framesSubmitted = 0L
    private var startMediaTimeUs = 0L
    private var startNeedsInit = true

    /** The stream reports no position while paused; the clock must not run backwards meanwhile. */
    private var lastPlayedFrames = 0L

    private val route = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            if (handle != 0L && removed.any { it.id == deviceId }) lostDevice = true
        }
    }
    private var routeRegistered = false

    /** The wired output this path plays to, if one is attached. */
    fun wiredOutput(): AudioDeviceInfo? =
        runCatching { manager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrNull()?.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_LINE_ANALOG
        }

    /**
     * The wired output's own rate list where it gives one. Its depths are only
     * found out by opening, so 16 and 24 are offered and the native side
     * falls back from there.
     */
    override fun dacOffers(format: Format): Map<Int, Set<Int>> {
        val rates = wiredOutput()?.sampleRates?.takeIf { it.isNotEmpty() }?.toList() ?: BitPerfectPcm.STANDARD_RATES
        return rates.associateWith { setOf(16, 24) }
    }

    override fun canOpen(): Boolean = ExclusiveNative.loaded && wiredOutput() != null

    override fun configure(format: Format, bitDepthHint: Int?): Boolean = synchronized(lock) {
        closeLocked()
        if (!ExclusiveNative.loaded) return fail("not built for this device", retryable = false)
        if (format.sampleMimeType != MimeTypes.AUDIO_RAW || format.sampleRate <= 0) {
            return fail("not a PCM track", retryable = false)
        }
        if (format.channelCount !in 1..2) return fail("multichannel track", retryable = false)
        val sourceBits = BitPerfectPcm.sourceBits(format.pcmEncoding)
            ?: return fail("unsupported PCM encoding", retryable = false)
        val wired = wiredOutput() ?: return fail("no wired output", retryable = true)
        val opened = try {
            ExclusiveNative.open(format.sampleRate, format.channelCount, sourceBits, wired.id)
        } catch (error: IllegalStateException) {
            // The native side lists every format it tried and what came back.
            Log.i(TAG, error.message ?: "open refused")
            return fail("Android did not grant exclusive mode at ${format.sampleRate} Hz", retryable = false)
        }
        try {
            wireBits = ExclusiveNative.bits(opened)
            deviceId = ExclusiveNative.device(opened)
        } catch (error: IllegalStateException) {
            ExclusiveNative.close(opened)
            return fail(error.message ?: "stream closed", retryable = false)
        }
        handle = opened
        lostDevice = false
        if (!routeRegistered) {
            manager?.registerAudioDeviceCallback(route, Handler(Looper.getMainLooper()))
            routeRegistered = true
        }
        rateHz = format.sampleRate
        channels = format.channelCount
        sourceEncoding = format.pcmEncoding
        exact = true
        started = false
        framesSubmitted = 0
        lastPlayedFrames = 0
        startNeedsInit = true
        pending = scratch.also { it.clear().limit(0) }
        lastFailureReason = null
        Log.i(TAG, "open ${format.sampleRate} Hz ${wireBits}-bit (source $sourceBits-bit) on device $deviceId")
        true
    }

    override fun currentRateHz(): Int = if (handle != 0L) rateHz else 0

    override fun activeWireBits(): Int = if (handle != 0L) wireBits else 0

    override fun failureIsRetryable(): Boolean = retryable

    override fun isPaused(): Boolean = paused

    override fun setPaused(value: Boolean) {
        paused = value
        val current = handle
        if (current == 0L || !started) return
        // A paused MMAP stream stops calling back, so a pause costs no wakeups.
        runCatching { if (value) ExclusiveNative.pause(current) else ExclusiveNative.start(current) }
    }

    override fun setListenerLevel(level: Float) {
        gain = ListenerVolumePolicy.perceptualGain(level.coerceIn(0f, 1f))
    }

    override fun usesHardwareVolume(): Boolean = false

    override fun softwareGain(): Float = gain

    override fun hardwareOutputLevelDb(): Double? = null

    override fun write(buffer: ByteBuffer, presentationTimeUs: Long, isFloatBuffer: Boolean): Boolean {
        val current = handle
        if (current == 0L || lostDevice || paused) return false
        try {
            if (pending.hasRemaining()) {
                push(current)
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
                scratch = ByteBuffer.allocateDirect(outBytes + outBytes / 2).order(ByteOrder.LITTLE_ENDIAN)
            }
            val out = scratch
            out.clear()
            val wasExact = BitPerfectPcm.convert(buffer, sourceEncoding, channels, wireBits, out)
            out.flip()
            val level = gain
            if (level < 1f - 1e-6f) MixerSession.scaleInPlace(out, wireBits, level)
            if (!wasExact && exact) {
                exact = false
                AudioOutputStatus.publishOutputExactness(false, "$pathLabel: this source isn't lossless PCM")
            }
            framesSubmitted += frames
            pending = out
            push(current)
            if (!started) {
                ExclusiveNative.start(current)
                started = true
            }
            return true
        } catch (error: IllegalStateException) {
            lostDevice = true
            lastFailureReason = "$pathLabel: ${error.message}"
            return false
        }
    }

    private fun push(current: Long) {
        // The native side refuses single writes above 256 KiB.
        val count = minOf(pending.remaining(), MAX_WRITE)
        val taken = ExclusiveNative.write(current, pending, pending.position(), count)
        pending.position(pending.position() + taken)
    }

    override fun isStreamAlive(): Boolean = handle != 0L && !lostDevice

    override fun restartIfStopped(): Boolean = false

    override fun getCurrentPositionUs(): Long {
        val current = handle
        if (current == 0L || startNeedsInit || rateHz <= 0) return AudioSink.CURRENT_POSITION_NOT_SET
        if (!lostDevice) {
            val frames = runCatching { ExclusiveNative.frames(current) }.getOrDefault(lastPlayedFrames)
            lastPlayedFrames = maxOf(lastPlayedFrames, frames)
        }
        return startMediaTimeUs + lastPlayedFrames * C.MICROS_PER_SECOND / rateHz
    }

    override fun hasPendingData(): Boolean {
        if (handle == 0L) return false
        return pending.hasRemaining() || framesSubmitted > lastPlayedFrames
    }

    override fun flush() {
        val current = handle
        if (current != 0L) runCatching { ExclusiveNative.flush(current) }
        pending = scratch.also { it.clear().limit(0) }
        framesSubmitted = 0
        lastPlayedFrames = 0
        started = false
        startNeedsInit = true
        // A seek only flushes; the renderer will not call play() again.
        paused = false
    }

    override fun handleDiscontinuity() = Unit

    override fun prepareForNextItem() = flush()

    override fun reset() {
        synchronized(lock) { closeLocked() }
    }

    override fun release() = reset()

    private fun closeLocked() {
        val current = handle
        if (current != 0L) {
            handle = 0L
            ExclusiveNative.close(current)
        }
        if (routeRegistered) {
            runCatching { manager?.unregisterAudioDeviceCallback(route) }
            routeRegistered = false
        }
        pending = scratch.also { it.clear().limit(0) }
        framesSubmitted = 0
        lastPlayedFrames = 0
        started = false
        startNeedsInit = true
    }

    private fun fail(reason: String, retryable: Boolean): Boolean {
        Log.i(TAG, "unavailable: $reason")
        lastFailureReason = "$pathLabel: $reason"
        this.retryable = retryable
        return false
    }

    private companion object {
        const val TAG = "AAudioExclusive"
        const val MAX_WRITE = 262_144
    }
}
