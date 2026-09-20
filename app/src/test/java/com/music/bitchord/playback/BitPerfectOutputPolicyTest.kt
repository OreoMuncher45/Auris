package com.music.bitchord.playback

import com.music.bitchord.data.settings.OutputPcmMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The float-output decision, which is the one bit-perfect mode gets wrong in
 * the most counter-intuitive direction.
 *
 * Media3's `DefaultAudioSink` can open exactly two kinds of linear-PCM
 * AudioTrack: float, and 16-bit. Its `configure` adds
 * `ToInt16PcmAudioProcessor` to the chain for *every* input encoding whenever
 * float output is off. So on a 24-bit source, turning float off in the name of
 * purity does not pass 24 bits through — it downconverts them to 16. Float is
 * the only thing that carries a 24-bit sample to the hardware intact.
 *
 * An earlier version of this mode disabled float output, on the reasoning that
 * float is "a conversion" and bit-perfect should do none. These tests exist so
 * that reasoning cannot come back.
 */
class BitPerfectOutputPolicyTest {

    @Test
    fun `bit-perfect asks for float on an external route that advertises it`() {
        assertTrue(
            AudioOutputPolicy.allowsFloatForBitPerfect(
                routeKind = AudioRouting.Kind.USB,
                advertisesPcmFloat = true,
            ),
        )
    }

    /**
     * Unlike the ordinary preference, bit-perfect does not require the listener
     * to have picked 32-bit float in Output precision. In this mode float is
     * the mechanism, not the preference.
     */
    @Test
    fun `bit-perfect ignores the output precision preference`() {
        // The preference path refuses, because PCM_16 was requested...
        assertFalse(
            AudioOutputPolicy.shouldUseFloatOutput(
                requestedMode = OutputPcmMode.PCM_16,
                routeKind = AudioRouting.Kind.USB,
                advertisesPcmFloat = true,
            ),
        )
        // ...but bit-perfect still needs float to carry 24 bits.
        assertTrue(
            AudioOutputPolicy.allowsFloatForBitPerfect(
                routeKind = AudioRouting.Kind.USB,
                advertisesPcmFloat = true,
            ),
        )
    }

    /**
     * The built-in speaker keeps its guard. An OEM path that accepts a float
     * AudioTrack and then converts it in AudioFlinger produces distortion on
     * affected devices, and that is a worse outcome than 16-bit — which is
     * itself bit-exact for a 16-bit source, and honestly reported as inexact
     * for anything above it.
     */
    @Test
    fun `bit-perfect still refuses float on the phone speaker`() {
        assertFalse(
            AudioOutputPolicy.allowsFloatForBitPerfect(
                routeKind = AudioRouting.Kind.PHONE,
                advertisesPcmFloat = true,
            ),
        )
    }

    /** A route that does not advertise float does not get asked for it. */
    @Test
    fun `bit-perfect does not invent float support`() {
        assertFalse(
            AudioOutputPolicy.allowsFloatForBitPerfect(
                routeKind = AudioRouting.Kind.BLUETOOTH,
                advertisesPcmFloat = false,
            ),
        )
    }
}
