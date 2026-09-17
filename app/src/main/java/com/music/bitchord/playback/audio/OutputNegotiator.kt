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

import android.media.AudioFormat
import com.music.bitchord.data.settings.OutputPcmMode
import com.music.bitchord.playback.AudioOutputPolicy
import com.music.bitchord.playback.AudioRouting
import com.music.bitchord.playback.audio.usb.DirectUsbProbeResult

/**
 * Output transport mechanism.
 */
enum class TransportType(val label: String) {
    AUDIO_TRACK("AudioTrack"),
    DIRECT_USB("Direct USB"),
}

/**
 * Authoritative rationale when the audio pipeline falls back from the listener's requested
 * format or transport to a lower-precision or system-mixed path.
 */
enum class FallbackReason(val label: String) {
    NONE("None"),
    UNSUPPORTED_FORMAT("Unsupported source format"),
    ROUTE_LIMITATION("Route limitation (device profile)"),
    OS_LIMITATION("OS limitation (system audio policy)"),
    DIRECT_USB_UNAVAILABLE("Direct USB unavailable"),
    DECODER_LIMITATION("Decoder limitation"),
}

data class SourceDescriptor(
    val encoding: String,
    val sampleRateHz: Int,
    val channelCount: Int,
    val bitDepth: Int?,
)

data class DecoderDescriptor(
    val name: String?,
    val encoding: String = "Float32",
    val sampleRateHz: Int,
    val channelCount: Int,
)

data class DspDescriptor(
    val format: String = "Float32",
    val sampleRateHz: Int,
    val channelCount: Int,
)

data class RouteDescriptor(
    val kind: AudioRouting.Kind,
    val deviceName: String,
    val isDirectUsbCapable: Boolean = false,
    val advertisedEncodings: List<Int> = emptyList(),
    val advertisedSampleRates: List<Int> = emptyList(),
)

data class OutputDescriptor(
    val transport: TransportType,
    val encoding: PcmEncoding,
    val sampleRateHz: Int,
    val channelCount: Int,
    val systemMixerRateHz: Int? = null,
    val fallbackReason: FallbackReason = FallbackReason.NONE,
    val fallbackDetail: String? = null,
)

/**
 * Complete, authoritative snapshot of the 6-layer audio pipeline state:
 * Source -> Decoder -> DSP -> Route -> Output -> System.
 */
data class OutputNegotiationResult(
    val source: SourceDescriptor,
    val decoder: DecoderDescriptor,
    val dsp: DspDescriptor,
    val route: RouteDescriptor,
    val output: OutputDescriptor,
) {
    /** True if end-to-end sample rate was preserved without downstream mixer conversion. */
    val isSampleRatePreserved: Boolean
        get() = output.systemMixerRateHz == null || output.sampleRateHz == output.systemMixerRateHz
}

/**
 * Authoritative route and output negotiation engine.
 *
 * Rules:
 * 1. Internal DSP is ALWAYS Float32 for all supported linear PCM sources.
 * 2. Speaker output (PHONE) is strictly capped at 16-bit PCM.
 * 3. External routes (USB, Bluetooth, Wired, HDMI) negotiate formats based strictly on
 *    actively advertised runtime capabilities (AudioDeviceInfo encodings/rates).
 * 4. Never use source metadata as a substitute for runtime output facts.
 * 5. Never label a route as bit-perfect without verified hardware endpoint proof.
 */
object OutputNegotiator {

    fun negotiate(
        source: SourceDescriptor,
        decoderName: String?,
        decoderEncoding: String = "Float32",
        sampleRateHz: Int,
        channelCount: Int,
        routeKind: AudioRouting.Kind,
        deviceName: String,
        advertisedEncodings: List<Int>,
        advertisedSampleRates: List<Int>,
        requestedMode: OutputPcmMode,
        directUsbProbe: DirectUsbProbeResult? = null,
        delegateSupportsFloat: Boolean = true,
        knownSystemMixerRateHz: Int? = null,
    ): OutputNegotiationResult {
        val decoder = DecoderDescriptor(
            name = decoderName,
            encoding = decoderEncoding,
            sampleRateHz = sampleRateHz,
            channelCount = channelCount,
        )

        val dsp = DspDescriptor(
            format = "Float32",
            sampleRateHz = sampleRateHz,
            channelCount = channelCount,
        )

        val advertisesFloat = advertisedEncodings.contains(AudioFormat.ENCODING_PCM_FLOAT)
        val canUseFloat = AudioOutputPolicy.shouldUseFloatOutput(requestedMode, routeKind, advertisesFloat) &&
            delegateSupportsFloat

        val isDirectViable = directUsbProbe?.isViable == true && routeKind == AudioRouting.Kind.USB
        val transport = if (isDirectViable) TransportType.DIRECT_USB else TransportType.AUDIO_TRACK

        val (targetEncoding, fallbackReason, fallbackDetail) = when {
            isDirectViable -> {
                Triple(PcmEncoding.PCM_FLOAT, FallbackReason.NONE, null)
            }
            routeKind == AudioRouting.Kind.PHONE -> {
                val reason = if (requestedMode == OutputPcmMode.FLOAT_32) {
                    FallbackReason.ROUTE_LIMITATION
                } else {
                    FallbackReason.NONE
                }
                val detail = if (reason != FallbackReason.NONE) {
                    "Speaker output capped at 16-bit PCM to prevent OEM mixer distortion"
                } else {
                    null
                }
                Triple(PcmEncoding.PCM_16BIT, reason, detail)
            }
            canUseFloat -> {
                Triple(PcmEncoding.PCM_FLOAT, FallbackReason.NONE, null)
            }
            routeKind == AudioRouting.Kind.USB && requestedMode == OutputPcmMode.FLOAT_32 -> {
                val reason = if (directUsbProbe?.isViable == false) {
                    FallbackReason.DIRECT_USB_UNAVAILABLE
                } else {
                    FallbackReason.ROUTE_LIMITATION
                }
                val detail = directUsbProbe?.diagnosticReason
                    ?: "USB device does not advertise Float32 (16-bit PCM fallback)"
                Triple(PcmEncoding.PCM_16BIT, reason, detail)
            }
            requestedMode == OutputPcmMode.FLOAT_32 && !advertisesFloat -> {
                Triple(
                    PcmEncoding.PCM_16BIT,
                    FallbackReason.ROUTE_LIMITATION,
                    "${routeKind.name.lowercase().replaceFirstChar { it.uppercase() }} route advertises PCM16 only (16-bit fallback)",
                )
            }
            else -> {
                Triple(PcmEncoding.PCM_16BIT, FallbackReason.NONE, null)
            }
        }

        val route = RouteDescriptor(
            kind = routeKind,
            deviceName = deviceName,
            isDirectUsbCapable = directUsbProbe?.isViable == true,
            advertisedEncodings = advertisedEncodings,
            advertisedSampleRates = advertisedSampleRates,
        )

        val output = OutputDescriptor(
            transport = transport,
            encoding = targetEncoding,
            sampleRateHz = sampleRateHz,
            channelCount = channelCount,
            systemMixerRateHz = knownSystemMixerRateHz,
            fallbackReason = fallbackReason,
            fallbackDetail = fallbackDetail,
        )

        return OutputNegotiationResult(
            source = source,
            decoder = decoder,
            dsp = dsp,
            route = route,
            output = output,
        )
    }
}
