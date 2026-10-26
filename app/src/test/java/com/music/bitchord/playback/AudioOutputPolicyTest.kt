package com.music.bitchord.playback

import com.music.bitchord.data.settings.OutputPcmMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOutputPolicyTest {
    @Test
    fun floatRequiresPreferredUsbRouteAndAdvertisedSupport() {
        assertTrue(
            AudioOutputPolicy.shouldUseFloatOutput(
                OutputPcmMode.FLOAT_32,
                isPreferredUsbRoute = true,
                advertisesPcmFloat = true,
            ),
        )
    }

    @Test
    fun floatFallsBackOnSpeakerEvenWhenRequested() {
        assertFalse(
            AudioOutputPolicy.shouldUseFloatOutput(
                OutputPcmMode.FLOAT_32,
                isPreferredUsbRoute = false,
                advertisesPcmFloat = true,
            ),
        )
    }

    @Test
    fun floatFallsBackWhenUsbDoesNotAdvertiseIt() {
        assertFalse(
            AudioOutputPolicy.shouldUseFloatOutput(
                OutputPcmMode.FLOAT_32,
                isPreferredUsbRoute = true,
                advertisesPcmFloat = false,
            ),
        )
    }

    @Test
    fun pcm16NeverRequestsFloat() {
        assertFalse(
            AudioOutputPolicy.shouldUseFloatOutput(
                OutputPcmMode.PCM_16,
                isPreferredUsbRoute = true,
                advertisesPcmFloat = true,
            ),
        )
    }

    @Test
    fun routeAwarePhoneIsAlwaysCappedAt16Bit() {
        assertFalse(
            AudioOutputPolicy.shouldUseFloatOutput(
                OutputPcmMode.FLOAT_32,
                routeKind = AudioRouting.Kind.PHONE,
            ),
        )
    }

    @Test
    fun routeAwareUsbAllowsFloatWithoutDeviceAdvertisement() {
        // A USB DAC's AudioDeviceInfo describes what its HAL accepts after
        // AudioFlinger, not what AudioTrack can open; the sink's delegate probe
        // is the real gate and falls back to PCM16 when float will not open.
        assertTrue(
            AudioOutputPolicy.shouldUseFloatOutput(
                OutputPcmMode.FLOAT_32,
                routeKind = AudioRouting.Kind.USB,
            ),
        )
    }

    @Test
    fun routeAwareBluetoothAllowsFloatWithoutDeviceAdvertisement() {
        assertTrue(
            AudioOutputPolicy.shouldUseFloatOutput(
                OutputPcmMode.FLOAT_32,
                routeKind = AudioRouting.Kind.BLUETOOTH,
            ),
        )
    }

    @Test
    fun routeAwarePcm16AlwaysReturnsFalse() {
        assertFalse(
            AudioOutputPolicy.shouldUseFloatOutput(
                OutputPcmMode.PCM_16,
                routeKind = AudioRouting.Kind.BLUETOOTH,
            ),
        )
        assertFalse(
            AudioOutputPolicy.shouldUseFloatOutput(
                OutputPcmMode.PCM_16,
                routeKind = AudioRouting.Kind.USB,
            ),
        )
    }

    @Test
    fun samsungVendorFlacDecoderIsBlockedForFloatOutput() {
        assertTrue(AudioOutputPolicy.isUnsafeFloatFlacDecoder("c2.sec.flac.decoder"))
        assertTrue(AudioOutputPolicy.isUnsafeFloatFlacDecoder("OMX.SEC.FLAC.Decoder"))
        assertFalse(AudioOutputPolicy.isUnsafeFloatFlacDecoder("c2.android.flac.decoder"))
    }

    @Test
    fun bitPerfectStartsAtFloatWhateverThePrecisionMenuSays() {
        // Float is the only encoding Media3 will open that carries a 24-bit
        // sample; leaving hi-res on the menu's PCM16 would truncate it before
        // the route ever got a say.
        assertEquals(
            OutputPcmMode.FLOAT_32,
            AudioOutputPolicy.requestedPcmMode(OutputPcmMode.PCM_16, bitPerfect = true),
        )
        assertEquals(
            OutputPcmMode.FLOAT_32,
            AudioOutputPolicy.requestedPcmMode(OutputPcmMode.FLOAT_32, bitPerfect = true),
        )
    }

    @Test
    fun bitPerfectOffKeepsThePrecisionMenuChoice() {
        assertEquals(
            OutputPcmMode.PCM_16,
            AudioOutputPolicy.requestedPcmMode(OutputPcmMode.PCM_16, bitPerfect = false),
        )
        assertEquals(
            OutputPcmMode.FLOAT_32,
            AudioOutputPolicy.requestedPcmMode(OutputPcmMode.FLOAT_32, bitPerfect = false),
        )
    }
}
