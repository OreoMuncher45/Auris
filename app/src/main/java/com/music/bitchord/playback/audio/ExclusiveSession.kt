/*
 * Copyright (C) 2026 BitChord Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.music.bitchord.playback.audio

import androidx.media3.common.Format
import java.nio.ByteBuffer

/**
 * One mixer-free output that [PrecisionAudioSink] can hand a track's decoded
 * PCM to untouched, instead of its AudioTrack.
 *
 * The direct-USB driver ([com.music.bitchord.playback.usb.ExclusiveUsbOutput])
 * was the first; the Android 14 bit-perfect mixer at integer precision and an
 * AAudio exclusive stream for a built-in DAC are the others. They differ in
 * how they reach the DAC, not in what the sink asks of them, so the sink
 * drives whichever one the service picked through this.
 *
 * A session is shared by the two crossfade sinks; only the audible one ever
 * claims it.
 */
interface ExclusiveSession {

    /** How the readouts name this path: "USB exclusive", "AAudio exclusive" and so on. */
    val pathLabel: String

    /** What carries the audio past the system mixer, for the signal-path readout. */
    val pathDetail: String

    /** The transport the status reports while this session is live. */
    val transport: TransportType

    /**
     * Tells the readouts this session is live at [wire] / [rateHz]. The
     * default reports a mixer-free exclusive path; a session that goes through
     * Android's own (bit-perfect) mixer reports that instead.
     */
    fun publishActive(wire: String, rateHz: Int) {
        com.music.bitchord.playback.AudioOutputStatus.publishExclusiveUsb(
            active = true,
            detail = "$wire / $rateHz Hz $pathDetail",
            transport = transport,
        )
    }

    /** Why the last open failed, for the signal-path readout; null after a good open. */
    val lastFailureReason: String?

    /** Whether there is something to try right now: the device is there and allowed. */
    fun canOpen(): Boolean

    /** Opens at [format]'s own rate and channel count, or returns false. */
    fun configure(format: Format, bitDepthHint: Int? = null): Boolean

    /** The rate the output actually runs at; 0 when not open. */
    fun currentRateHz(): Int

    /** Integer precision on the wire; 0 when unknown or not open. */
    fun activeWireBits(): Int

    /** Whether the last open failure is worth retrying at the next track. */
    fun failureIsRetryable(): Boolean

    fun isPaused(): Boolean

    fun setPaused(value: Boolean)

    /** The listener's level, 0..1. Applied on the DAC where it can be, else on the samples. */
    fun setListenerLevel(level: Float)

    fun usesHardwareVolume(): Boolean

    /** The sample gain in use; 1 when the samples are untouched. */
    fun softwareGain(): Float

    /** The DAC's own level in dB, when the DAC carries the listener's level. */
    fun hardwareOutputLevelDb(): Double?

    /**
     * Takes [buffer] whole, or returns false and leaves it untouched for the
     * renderer to offer again ("full", not "failed": see [isStreamAlive]).
     */
    fun write(buffer: ByteBuffer, presentationTimeUs: Long, isFloatBuffer: Boolean): Boolean

    fun isStreamAlive(): Boolean

    /** Re-arms a stream that stopped while the device stayed open; true if it did. */
    fun restartIfStopped(): Boolean

    fun getCurrentPositionUs(): Long

    fun hasPendingData(): Boolean

    fun flush()

    fun handleDiscontinuity()

    /** Between items: drop queued audio but keep the device for the next configure. */
    fun prepareForNextItem()

    /** Closes the stream and hands the device back. */
    fun reset()

    fun release()
}
