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
import com.music.bitchord.playback.AudioRouting
import com.music.bitchord.playback.audio.usb.DirectUsbProbeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputNegotiatorTest {

    private val flac96kHz24BitSource = SourceDescriptor(
        encoding = "FLAC",
        sampleRateHz = 96000,
        channelCount = 2,
        bitDepth = 24,
    )

    @Test
    fun internalDspIsAlwaysFloat32RegardlessOfRouteOrOutput() {
        val result = OutputNegotiator.negotiate(
            source = flac96kHz24BitSource,
            decoderName = "c2.android.flac.decoder",
            sampleRateHz = 96000,
            channelCount = 2,
            routeKind = AudioRouting.Kind.PHONE,
            deviceName = "Built-in speaker",
            advertisedEncodings = listOf(AudioFormat.ENCODING_PCM_16BIT),
            advertisedSampleRates = listOf(48000),
            requestedMode = OutputPcmMode.FLOAT_32,
        )

        assertEquals("Float32", result.dsp.format)
        assertEquals(96000, result.dsp.sampleRateHz)
        assertEquals(2, result.dsp.channelCount)
        assertEquals(PcmEncoding.PCM_16BIT, result.output.encoding)
    }

    @Test
    fun phoneSpeakerStrictlyCapsOutputAt16BitWithRouteLimitation() {
        val result = OutputNegotiator.negotiate(
            source = flac96kHz24BitSource,
            decoderName = "c2.android.flac.decoder",
            sampleRateHz = 96000,
            channelCount = 2,
            routeKind = AudioRouting.Kind.PHONE,
            deviceName = "Built-in speaker",
            advertisedEncodings = listOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT),
            advertisedSampleRates = listOf(48000),
            requestedMode = OutputPcmMode.FLOAT_32,
        )

        assertEquals(PcmEncoding.PCM_16BIT, result.output.encoding)
        assertEquals(FallbackReason.ROUTE_LIMITATION, result.output.fallbackReason)
        assertNotNull(result.output.fallbackDetail)
        assertTrue(result.output.fallbackDetail!!.contains("Speaker output capped at 16-bit"))
    }

    @Test
    fun bluetoothNegotiatesFloatOnlyWhenAdvertised() {
        val btWithFloat = OutputNegotiator.negotiate(
            source = flac96kHz24BitSource,
            decoderName = "c2.android.flac.decoder",
            sampleRateHz = 96000,
            channelCount = 2,
            routeKind = AudioRouting.Kind.BLUETOOTH,
            deviceName = "Sony WH-1000XM4 (LDAC)",
            advertisedEncodings = listOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT),
            advertisedSampleRates = listOf(44100, 48000, 96000),
            requestedMode = OutputPcmMode.FLOAT_32,
        )
        assertEquals(PcmEncoding.PCM_FLOAT, btWithFloat.output.encoding)
        assertEquals(FallbackReason.NONE, btWithFloat.output.fallbackReason)

        val btPcm16Only = OutputNegotiator.negotiate(
            source = flac96kHz24BitSource,
            decoderName = "c2.android.flac.decoder",
            sampleRateHz = 96000,
            channelCount = 2,
            routeKind = AudioRouting.Kind.BLUETOOTH,
            deviceName = "SBC Headset",
            advertisedEncodings = listOf(AudioFormat.ENCODING_PCM_16BIT),
            advertisedSampleRates = listOf(44100, 48000),
            requestedMode = OutputPcmMode.FLOAT_32,
        )
        assertEquals(PcmEncoding.PCM_16BIT, btPcm16Only.output.encoding)
        assertEquals(FallbackReason.ROUTE_LIMITATION, btPcm16Only.output.fallbackReason)
    }

    @Test
    fun usbPcm16DacProvidesAuthoritativeDiagnosticFallbackReason() {
        val portronicsProbe = DirectUsbProbeResult(
            isViable = false,
            productName = "Portronics iKonnect C Pro",
            vendorId = 0x001F,
            productId = 0x0B21,
            uacVersion = 1,
            hasPermission = false,
            endpointOutAddress = 3,
            maxPacketSize = 384,
            altSettingCount = 1,
            isInterfaceClaimed = false,
            supportedBitDepths = listOf(16, 24, 32),
            maxCalculatedSampleRate = 96000,
            diagnosticReason = "Direct USB requires USB host permission for 'Portronics iKonnect C Pro' (managed by Android ALSA driver)",
        )

        val result = OutputNegotiator.negotiate(
            source = flac96kHz24BitSource,
            decoderName = "c2.android.flac.decoder",
            sampleRateHz = 96000,
            channelCount = 2,
            routeKind = AudioRouting.Kind.USB,
            deviceName = "Portronics iKonnect C Pro",
            advertisedEncodings = listOf(AudioFormat.ENCODING_PCM_16BIT),
            advertisedSampleRates = listOf(8000, 48000),
            requestedMode = OutputPcmMode.FLOAT_32,
            directUsbProbe = portronicsProbe,
            knownSystemMixerRateHz = 48000,
        )

        assertEquals(TransportType.AUDIO_TRACK, result.output.transport)
        assertEquals(PcmEncoding.PCM_16BIT, result.output.encoding)
        assertEquals(FallbackReason.DIRECT_USB_UNAVAILABLE, result.output.fallbackReason)
        assertEquals(portronicsProbe.diagnosticReason, result.output.fallbackDetail)
        assertFalse(result.isSampleRatePreserved)
    }

    @Test
    fun viableDirectUsbUsesDirectUsbTransportAndFloatEncoding() {
        val uac2Probe = DirectUsbProbeResult(
            isViable = true,
            productName = "HiFi UAC2 DAC",
            vendorId = 0x1234,
            productId = 0x5678,
            uacVersion = 2,
            hasPermission = true,
            endpointOutAddress = 1,
            maxPacketSize = 1024,
            altSettingCount = 2,
            isInterfaceClaimed = true,
            supportedBitDepths = listOf(16, 24, 32),
            maxCalculatedSampleRate = 192000,
            diagnosticReason = "UAC2 device ready for direct userspace transfer",
        )

        val result = OutputNegotiator.negotiate(
            source = flac96kHz24BitSource,
            decoderName = "c2.android.flac.decoder",
            sampleRateHz = 96000,
            channelCount = 2,
            routeKind = AudioRouting.Kind.USB,
            deviceName = "HiFi UAC2 DAC",
            advertisedEncodings = listOf(AudioFormat.ENCODING_PCM_16BIT),
            advertisedSampleRates = listOf(48000),
            requestedMode = OutputPcmMode.FLOAT_32,
            directUsbProbe = uac2Probe,
        )

        assertEquals(TransportType.DIRECT_USB, result.output.transport)
        assertEquals(PcmEncoding.PCM_FLOAT, result.output.encoding)
        assertEquals(FallbackReason.NONE, result.output.fallbackReason)
    }
}
