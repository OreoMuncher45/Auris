/*
 * Copyright (C) 2026 BitChord Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.music.bitchord.playback

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import com.music.bitchord.data.settings.OutputPcmMode
import com.music.bitchord.playback.audio.FallbackReason
import com.music.bitchord.playback.audio.OutputNegotiationResult
import com.music.bitchord.playback.audio.TransportType
import com.music.bitchord.playback.audio.usb.DirectUsbProbeResult
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Live facts about the Android output route and negotiated audio pipeline,
 * kept separate from source-format statistics.
 *
 * This snapshot transparently reports:
 * - Route kind and active transport (AudioTrack vs Direct USB)
 * - Actual decoder output format, DSP format, and AudioTrack format
 * - Exact fallback reason and explanation if fallback occurred
 * - Downstream system mixer sample rate (e.g. 48 kHz AudioFlinger)
 */
object AudioOutputStatus {
    data class Snapshot(
        val sink: String = "AudioTrack",
        val requestedPcmMode: OutputPcmMode = OutputPcmMode.PCM_16,
        val deviceName: String = "System default",
        val sampleRatesHz: IntArray = IntArray(0),
        val encodings: IntArray = IntArray(0),
        val isUsb: Boolean = false,
        val routeKind: AudioRouting.Kind = AudioRouting.Kind.PHONE,
        val transportType: TransportType = TransportType.AUDIO_TRACK,
        val actualEncoding: Int? = null,
        val actualSampleRateHz: Int? = null,
        val floatFallback: Boolean = false,
        val fallbackReason: FallbackReason = FallbackReason.NONE,
        val fallbackDetail: String? = null,
        val systemMixerRateHz: Int? = null,
        val decoderName: String? = null,
        val bufferSize: Int? = null,
        val decoderOutputEncoding: String? = null,
        val dspFormat: String = "Float32",
        val directUsbProbe: DirectUsbProbeResult? = null,
        val negotiationResult: OutputNegotiationResult? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Snapshot) return false
            return sink == other.sink &&
                requestedPcmMode == other.requestedPcmMode &&
                deviceName == other.deviceName &&
                sampleRatesHz.contentEquals(other.sampleRatesHz) &&
                encodings.contentEquals(other.encodings) &&
                isUsb == other.isUsb &&
                routeKind == other.routeKind &&
                transportType == other.transportType &&
                actualEncoding == other.actualEncoding &&
                actualSampleRateHz == other.actualSampleRateHz &&
                floatFallback == other.floatFallback &&
                fallbackReason == other.fallbackReason &&
                fallbackDetail == other.fallbackDetail &&
                systemMixerRateHz == other.systemMixerRateHz &&
                decoderName == other.decoderName &&
                bufferSize == other.bufferSize &&
                decoderOutputEncoding == other.decoderOutputEncoding &&
                dspFormat == other.dspFormat &&
                directUsbProbe == other.directUsbProbe &&
                negotiationResult == other.negotiationResult
        }

        override fun hashCode(): Int {
            var result = sink.hashCode()
            result = 31 * result + requestedPcmMode.hashCode()
            result = 31 * result + deviceName.hashCode()
            result = 31 * result + sampleRatesHz.contentHashCode()
            result = 31 * result + encodings.contentHashCode()
            result = 31 * result + isUsb.hashCode()
            result = 31 * result + routeKind.hashCode()
            result = 31 * result + transportType.hashCode()
            result = 31 * result + (actualEncoding ?: 0)
            result = 31 * result + (actualSampleRateHz ?: 0)
            result = 31 * result + floatFallback.hashCode()
            result = 31 * result + fallbackReason.hashCode()
            result = 31 * result + (fallbackDetail?.hashCode() ?: 0)
            result = 31 * result + (systemMixerRateHz ?: 0)
            result = 31 * result + (decoderName?.hashCode() ?: 0)
            result = 31 * result + (bufferSize ?: 0)
            result = 31 * result + (decoderOutputEncoding?.hashCode() ?: 0)
            result = 31 * result + dspFormat.hashCode()
            result = 31 * result + (directUsbProbe?.hashCode() ?: 0)
            result = 31 * result + (negotiationResult?.hashCode() ?: 0)
            return result
        }
    }

    val current = MutableStateFlow(Snapshot())

    fun publish(
        manager: AudioManager,
        requestedPcmMode: OutputPcmMode,
        preferred: AudioDeviceInfo?,
        floatEnabled: Boolean,
        routeKind: AudioRouting.Kind? = null,
        directUsbProbe: DirectUsbProbeResult? = null,
        systemMixerRateHz: Int? = null,
    ) {
        val device = preferred ?: manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.isSink }
        val isUsbDevice = device?.type in setOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
        )
        val computedRouteKind = routeKind ?: when {
            isUsbDevice -> AudioRouting.Kind.USB
            device?.type in setOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER,
                AudioDeviceInfo.TYPE_BLE_BROADCAST,
                AudioDeviceInfo.TYPE_HEARING_AID,
            ) -> AudioRouting.Kind.BLUETOOTH
            device?.type in setOf(
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_LINE_ANALOG,
                AudioDeviceInfo.TYPE_AUX_LINE,
            ) -> AudioRouting.Kind.WIRED
            device?.type in setOf(
                AudioDeviceInfo.TYPE_HDMI,
                AudioDeviceInfo.TYPE_HDMI_ARC,
            ) -> AudioRouting.Kind.HDMI
            else -> AudioRouting.Kind.PHONE
        }

        val isDirectViable = directUsbProbe?.isViable == true && isUsbDevice
        val transport = if (isDirectViable) TransportType.DIRECT_USB else TransportType.AUDIO_TRACK

        val fallback = requestedPcmMode == OutputPcmMode.FLOAT_32 && !floatEnabled
        val (reason, detail) = when {
            isDirectViable -> Pair(FallbackReason.NONE, null)
            computedRouteKind == AudioRouting.Kind.PHONE && fallback ->
                Pair(FallbackReason.ROUTE_LIMITATION, "Speaker output capped at 16-bit to avoid OEM mixer distortion")
            computedRouteKind == AudioRouting.Kind.USB && fallback ->
                Pair(
                    if (directUsbProbe?.isViable == false) FallbackReason.DIRECT_USB_UNAVAILABLE else FallbackReason.ROUTE_LIMITATION,
                    directUsbProbe?.diagnosticReason ?: "USB route does not advertise Float32 output",
                )
            fallback ->
                Pair(FallbackReason.ROUTE_LIMITATION, "${computedRouteKind.name} route does not advertise Float32")
            else ->
                Pair(FallbackReason.NONE, null)
        }

        current.value = current.value.copy(
            requestedPcmMode = requestedPcmMode,
            deviceName = device?.productName?.toString()?.ifBlank { null } ?: "System default",
            sampleRatesHz = device?.sampleRates ?: IntArray(0),
            encodings = device?.encodings ?: IntArray(0),
            isUsb = isUsbDevice,
            routeKind = computedRouteKind,
            transportType = transport,
            floatFallback = fallback,
            fallbackReason = reason,
            fallbackDetail = detail,
            systemMixerRateHz = systemMixerRateHz ?: if (isUsbDevice) 48000 else null,
            directUsbProbe = directUsbProbe,
        )
    }

    fun publishNegotiation(result: OutputNegotiationResult) {
        current.value = current.value.copy(
            negotiationResult = result,
            routeKind = result.route.kind,
            transportType = result.output.transport,
            fallbackReason = result.output.fallbackReason,
            fallbackDetail = result.output.fallbackDetail,
            systemMixerRateHz = result.output.systemMixerRateHz,
            decoderOutputEncoding = result.decoder.encoding,
            dspFormat = result.dsp.format,
        )
    }

    fun publishDecoder(decoderName: String?) {
        current.value = current.value.copy(decoderName = decoderName)
    }

    fun publishDsp(decoderOutputEncoding: String?, dspFormat: String = "Float32") {
        current.value = current.value.copy(
            decoderOutputEncoding = decoderOutputEncoding,
            dspFormat = dspFormat,
        )
    }

    fun publishAudioTrack(encoding: Int, sampleRateHz: Int, bufferSize: Int? = null) {
        current.value = current.value.copy(
            actualEncoding = encoding,
            actualSampleRateHz = sampleRateHz,
            bufferSize = bufferSize ?: current.value.bufferSize,
            floatFallback = current.value.requestedPcmMode == OutputPcmMode.FLOAT_32 &&
                encoding != AudioFormat.ENCODING_PCM_FLOAT,
        )
    }

    fun reset() {
        current.value = Snapshot()
    }

    fun encodingLabel(snapshot: Snapshot): String = when (snapshot.actualEncoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> "32-bit float"
        AudioFormat.ENCODING_PCM_16BIT -> if (snapshot.floatFallback) "16-bit fallback" else "16-bit PCM"
        null -> if (snapshot.floatFallback) "16-bit fallback" else snapshot.requestedPcmMode.label
        else -> "PCM (${snapshot.actualEncoding})"
    }
}
