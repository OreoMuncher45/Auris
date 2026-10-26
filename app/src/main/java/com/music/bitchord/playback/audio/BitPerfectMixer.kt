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

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import androidx.annotation.RequiresApi
import java.util.Locale

/**
 * Asks Android's own mixer for a path that does not mix.
 *
 * From Android 14 the framework can be told, per output device, that one exact
 * format must reach the HAL untouched: [AudioMixerAttributes] with
 * `MIXER_BEHAVIOR_BIT_PERFECT`. A sink can be bit-exact and the system can
 * still resample or re-encode afterwards; this is the part of the promise the
 * app cannot keep on its own, because the mixer belongs to the platform.
 *
 * The request has to name the exact format the app's AudioTrack writes. A float
 * track against a PCM16 preference is converted at the seam just the same as a
 * PCM16 track against a float one, so the caller passes the sink's encoding
 * rather than the source's bit depth. The source rate and channel count must
 * match too: a bit-perfect mixer at another rate is still a resample.
 *
 * The framework allows this on USB devices only, so every other route is told
 * so rather than asked and refused.
 *
 * Nothing is claimed when the request cannot be honoured. [State] carries the
 * reason, so the pipeline readout can say "requested but not available" instead
 * of implying a path that does not exist.
 */
object BitPerfectMixer {

    data class State(val active: Boolean, val detail: String)

    /**
     * One format the device offers, kept free of framework types so the
     * selection can be tested on a plain JVM. [index] points back into the
     * framework list the candidate was built from.
     */
    internal data class Candidate(
        val index: Int,
        val encoding: Int,
        val sampleRateHz: Int,
        val channelCount: Int,
        val bitPerfect: Boolean,
    )

    private class Applied(
        val device: AudioDeviceInfo,
        val audioAttributes: AudioAttributes,
        val encoding: Int,
        val sampleRateHz: Int,
        val channelCount: Int,
    )

    private var applied: Applied? = null

    /**
     * Requests a bit-perfect mix for [device], or withdraws the previous
     * request when [enabled] is false.
     *
     * @return the live state, or null when the feature is off: nothing is
     *   requested then, so there is nothing for the readout to say.
     */
    fun apply(
        manager: AudioManager?,
        device: AudioDeviceInfo?,
        enabled: Boolean,
        sampleRateHz: Int?,
        channelCount: Int,
        sinkEncoding: Int,
        audioAttributes: AudioAttributes,
    ): State? {
        if (!enabled) {
            clear(manager)
            return null
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return State(false, "Android 14+ required")
        }
        if (manager == null || device == null) return State(false, "No output device")
        // The framework allows only USB devices here today; asking on a
        // Bluetooth or wired route would be refused, so say why up front.
        if (!device.isUsb) return State(false, "Only USB output supports it")
        if (sampleRateHz == null || sampleRateHz <= 0) return State(false, "Sample rate unknown")
        // The framework has no bit-perfect story for a downmix: stereo and mono
        // reach the HAL as they are, anything wider would have to be folded.
        if (channelCount !in 1..2) return State(false, "Multichannel track")
        return applyApi34(manager, device, sampleRateHz, channelCount, sinkEncoding, audioAttributes)
    }

    /** Withdraws the request so a later app is not left with a preconfigured device. */
    fun clear(manager: AudioManager?) {
        val previous = applied ?: return
        applied = null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        runCatching {
            manager?.clearPreferredMixerAttributes(previous.audioAttributes, previous.device)
        }
    }

    private val AudioDeviceInfo.isUsb: Boolean
        get() = type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            type == AudioDeviceInfo.TYPE_USB_ACCESSORY

    /**
     * The first framework-offered format that matches what the sink writes.
     * Encoding, rate and channel count must all match exactly; a near miss
     * would be a conversion at the mixer, which is what this exists to avoid.
     */
    internal fun select(
        candidates: List<Candidate>,
        sampleRateHz: Int,
        channelCount: Int,
        sinkEncoding: Int,
    ): Candidate? = candidates.firstOrNull { candidate ->
        candidate.bitPerfect &&
            candidate.encoding == sinkEncoding &&
            candidate.sampleRateHz == sampleRateHz &&
            candidate.channelCount == channelCount
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun applyApi34(
        manager: AudioManager,
        device: AudioDeviceInfo,
        sampleRateHz: Int,
        channelCount: Int,
        sinkEncoding: Int,
        audioAttributes: AudioAttributes,
    ): State {
        val supported = runCatching { manager.getSupportedMixerAttributes(device) }
            .getOrDefault(emptyList())
        val candidates = supported.mapIndexed { index, attributes ->
            val format = attributes.format
            Candidate(
                index = index,
                encoding = format.encoding,
                sampleRateHz = format.sampleRate,
                channelCount = Integer.bitCount(format.channelMask),
                bitPerfect = attributes.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT,
            )
        }
        val match = select(candidates, sampleRateHz, channelCount, sinkEncoding)
            ?: return State(false, unavailable(candidates, sampleRateHz, channelCount, sinkEncoding))

        val differs = applied?.let { previous ->
            previous.device.id != device.id ||
                previous.encoding != match.encoding ||
                previous.sampleRateHz != match.sampleRateHz ||
                previous.channelCount != match.channelCount
        } ?: true
        if (differs) clear(manager)

        val mixerAttributes = supported[match.index]
        val accepted = runCatching {
            manager.setPreferredMixerAttributes(audioAttributes, device, mixerAttributes)
        }.getOrDefault(false)
        if (!accepted) return State(false, "Refused by the audio framework")

        // A true return is the request being stored, not the path being live.
        // Read it back and require the framework to name the same format, so
        // the readout cannot call a stale or different preference "active".
        val confirmed = runCatching {
            manager.getPreferredMixerAttributes(audioAttributes, device)
        }.getOrNull()?.let { preferred ->
            preferred.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                preferred.format.encoding == match.encoding &&
                preferred.format.sampleRate == match.sampleRateHz &&
                Integer.bitCount(preferred.format.channelMask) == match.channelCount
        } ?: false
        if (!confirmed) return State(false, "Framework did not confirm the request")

        applied = Applied(
            device = device,
            audioAttributes = audioAttributes,
            encoding = match.encoding,
            sampleRateHz = match.sampleRateHz,
            channelCount = match.channelCount,
        )
        return State(true, "${rateText(sampleRateHz)} ${encodingLabel(sinkEncoding)}")
    }

    private fun unavailable(
        candidates: List<Candidate>,
        sampleRateHz: Int,
        channelCount: Int,
        sinkEncoding: Int,
    ): String {
        if (candidates.isEmpty()) return "Device reports no bit-perfect mixer"
        val atRate = candidates.filter {
            it.sampleRateHz == sampleRateHz && it.channelCount == channelCount
        }
        if (atRate.isEmpty()) return "No bit-perfect mixer at ${rateText(sampleRateHz)}"
        val offered = atRate.map { encodingLabel(it.encoding) }.distinct().joinToString("/")
        return "Device offers $offered, sink writes ${encodingLabel(sinkEncoding)}"
    }

    private fun rateText(hz: Int): String = "%.1f kHz".format(Locale.ROOT, hz / 1000.0)

    internal fun encodingLabel(encoding: Int): String = when (encoding) {
        AudioFormat.ENCODING_PCM_16BIT -> "PCM16"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM24"
        AudioFormat.ENCODING_PCM_32BIT -> "PCM32"
        AudioFormat.ENCODING_PCM_FLOAT -> "Float32"
        else -> "encoding $encoding"
    }
}
