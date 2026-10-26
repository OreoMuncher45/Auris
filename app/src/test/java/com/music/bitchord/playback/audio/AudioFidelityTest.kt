package com.music.bitchord.playback.audio

import com.music.bitchord.playback.AudioRouting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFidelityTest {

    @Test
    fun exclusiveUsbWithNothingRunningScores100() {
        val result = AudioFidelity.assess(
            AudioFidelity.Inputs(
                routeKind = AudioRouting.Kind.USB,
                exclusiveUsbActive = true,
                outputExact = true,
                dspBypassed = true,
                loudnessActive = true, // bypassed must win over the switch
                equalizerActive = true,
            ),
        )

        assertEquals(100, result.score)
        assertEquals("Bit-perfect", result.grade)
        assertTrue(result.activeEffects.isEmpty())
        assertTrue(result.effectsBypassed)
        assertTrue(result.deductions.isEmpty())
    }

    @Test
    fun bluetoothLosesPointsForMixerAndCodecEvenWhenOutputIsExact() {
        val result = AudioFidelity.assess(
            AudioFidelity.Inputs(
                routeKind = AudioRouting.Kind.BLUETOOTH,
                outputExact = true,
                dspBypassed = true,
            ),
        )

        assertEquals(76, result.score)
        assertEquals("High fidelity", result.grade)
        assertTrue(result.deductions.any { it.label.contains("System mixer") })
        assertTrue(result.deductions.any { it.label.contains("Bluetooth") })
    }

    @Test
    fun runningEffectsAreCountedAndNamed() {
        val result = AudioFidelity.assess(
            AudioFidelity.Inputs(
                routeKind = AudioRouting.Kind.WIRED,
                outputExact = true,
                equalizerActive = true,
                spatialActive = true,
            ),
        )

        assertEquals(listOf("Equalizer", "Spatial audio"), result.activeEffects)
        assertEquals(2, result.activeEffects.size)
        assertEquals(100 - 12 - 8 - 8, result.score)
        assertFalse(result.effectsBypassed)
    }

    @Test
    fun dsdConversionIsDeductedOnceNotAsAResample() {
        val result = AudioFidelity.assess(
            AudioFidelity.Inputs(
                routeKind = AudioRouting.Kind.USB,
                outputExact = true,
                dspBypassed = true,
                dsd = true,
                sourceRateHz = 2_822_400,
                outputRateHz = 176_400,
            ),
        )

        assertEquals(100 - 12 - 6, result.score)
        assertTrue(result.deductions.any { it.label.contains("DSD") })
        assertFalse(result.deductions.any { it.label.startsWith("Resampled") })
    }

    @Test
    fun changedRateBetweenSourceAndOutputIsNamed() {
        val result = AudioFidelity.assess(
            AudioFidelity.Inputs(
                routeKind = AudioRouting.Kind.WIRED,
                outputExact = true,
                dspBypassed = true,
                sourceRateHz = 96_000,
                outputRateHz = 48_000,
            ),
        )

        assertEquals(100 - 12 - 8, result.score)
        assertTrue(result.deductions.any { it.label == "Resampled 96 kHz -> 48 kHz" })
    }

    @Test
    fun phonePathIsCappedBelowEveryExternalRoute() {
        val result = AudioFidelity.assess(
            AudioFidelity.Inputs(
                routeKind = AudioRouting.Kind.PHONE,
                outputExact = true,
                dspBypassed = true,
            ),
        )

        assertEquals(100 - 12 - 32, result.score)
        assertEquals("Mixed path", result.grade)
    }

    @Test
    fun encodingNarrowerThanTheSourceIsDeducted() {
        val result = AudioFidelity.assess(
            AudioFidelity.Inputs(
                routeKind = AudioRouting.Kind.WIRED,
                outputExact = false,
                dspBypassed = true,
            ),
        )

        assertEquals(100 - 12 - 12, result.score)
        assertTrue(result.deductions.any { it.label.contains("cannot carry") })
    }

    @Test
    fun passthroughStreamDoesNotInventEncodingOrEffectDeductions() {
        val result = AudioFidelity.assess(
            AudioFidelity.Inputs(
                routeKind = AudioRouting.Kind.HDMI,
                outputExact = false,
                dspAvailable = false,
                loudnessActive = true,
                equalizerActive = true,
            ),
        )

        assertEquals(100 - 12, result.score)
        assertTrue(result.activeEffects.isEmpty())
        assertFalse(result.deductions.any { it.label.contains("cannot carry") })
        assertFalse(result.deductions.any { it.label.contains("Equalizer") })
    }
}
