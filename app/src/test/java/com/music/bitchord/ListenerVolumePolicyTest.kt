package com.music.bitchord

import com.music.bitchord.playback.audio.ListenerVolumePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The listener's own volume.
 *
 * The rule the whole thing rests on: the phone's slider is an **offset from
 * the level the output already has** — never an absolute level, and never
 * applied twice. Writing an absolute level into a DAC's own volume control
 * is what left USB DACs at full volume, because Android pins the media
 * stream at maximum the moment a DAC attaches.
 */
class ListenerVolumePolicyTest {

    @Test
    fun fullVolumeAsksForNoAttenuation() {
        assertEquals(0.0, ListenerVolumePolicy.offsetDb(1f), 0.001)
        assertEquals(1f, ListenerVolumePolicy.perceptualGain(1f), 0.0001f)
    }

    @Test
    fun silenceAsksForTheFloor() {
        assertEquals(
            ListenerVolumePolicy.FLOOR_DB,
            ListenerVolumePolicy.offsetDb(0f),
            0.001,
        )
        assertEquals(0f, ListenerVolumePolicy.perceptualGain(0f), 0.0001f)
    }

    @Test
    fun theCurveNeverGetsLouderAsTheSliderGoesDown() {
        var previous = 0.0
        for (step in 20 downTo 0) {
            val level = step / 20f
            val offset = ListenerVolumePolicy.offsetDb(level)
            assertTrue(
                "the offset rose from $previous to $offset at level $level",
                offset <= previous + 0.0001,
            )
            previous = offset
        }
    }

    @Test
    fun theGainIsTheSameCurve() {
        // Zero is silence rather than the floor's attenuation, so the curve
        // is checked from the first step above it.
        for (step in 1..20) {
            val level = step / 20f
            val db = ListenerVolumePolicy.offsetDb(level)
            val expected = Math.pow(10.0, db / 20.0)
            assertTrue(
                "gain did not match $db dB at level $level",
                Math.abs(ListenerVolumePolicy.perceptualGain(level) - expected) < 0.0001,
            )
        }
    }

    @Test
    fun halfVolumeIsQuieterThanThreeQuarters() {
        assertTrue(
            ListenerVolumePolicy.perceptualGain(0.5f) <
                ListenerVolumePolicy.perceptualGain(0.75f),
        )
    }

    @Test
    fun theDacKeepsItsOwnLevelAtFullVolume() {
        // The DAC's knob is the listener's. At full volume the app writes
        // nothing that would move it.
        assertEquals(
            -18.0,
            ListenerVolumePolicy.dacTargetDb(
                baseDb = -18.0,
                level = 1f,
                minDb = -62.0,
                maxDb = 0.0,
            ),
            0.001,
        )
    }

    @Test
    fun theDacIsOffsetDownwardsFromItsOwnLevel() {
        val base = -18.0
        val target = ListenerVolumePolicy.dacTargetDb(
            baseDb = base,
            level = 0.5f,
            minDb = -62.0,
            maxDb = 0.0,
        )
        assertTrue("the target $target was not below the base $base", target < base)
        assertEquals(
            base + ListenerVolumePolicy.offsetDb(0.5f),
            target,
            0.001,
        )
    }

    @Test
    fun theDacTargetStaysInsideTheDacsRange() {
        val low = ListenerVolumePolicy.dacTargetDb(
            baseDb = -60.0,
            level = 0.1f,
            minDb = -62.0,
            maxDb = 0.0,
        )
        assertEquals(-62.0, low, 0.001)

        val high = ListenerVolumePolicy.dacTargetDb(
            baseDb = 0.0,
            level = 1f,
            minDb = -62.0,
            maxDb = 0.0,
        )
        assertEquals(0.0, high, 0.001)
    }

    @Test
    fun aPinnedSystemReadingIsOnlyIgnoredWithAUsbOutput() {
        // Android pins the media stream at maximum when a USB DAC attaches.
        assertTrue(
            ListenerVolumePolicy.systemReadIsPinned(
                systemGain = 1f,
                usbAttached = true,
                appLevel = 0.6f,
            ),
        )
        // Without one, a reading of maximum is the listener's own choice.
        assertFalse(
            ListenerVolumePolicy.systemReadIsPinned(
                systemGain = 1f,
                usbAttached = false,
                appLevel = 0.6f,
            ),
        )
        // A reading below maximum is the listener's own choice either way.
        assertFalse(
            ListenerVolumePolicy.systemReadIsPinned(
                systemGain = 0.5f,
                usbAttached = true,
                appLevel = 0.6f,
            ),
        )
        // And there is nothing to protect when the app is already at full.
        assertFalse(
            ListenerVolumePolicy.systemReadIsPinned(
                systemGain = 1f,
                usbAttached = true,
                appLevel = 1f,
            ),
        )
    }
}
