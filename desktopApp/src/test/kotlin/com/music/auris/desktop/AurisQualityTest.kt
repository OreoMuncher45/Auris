package com.music.auris.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AurisQualityTest {

    @Test
    fun aRungDecidesWhichKindsOfSourceMayAnswer() {
        // Everything, including the sources that can serve bit-exact audio.
        AurisSourceKind.entries.forEach {
            assertTrue(AurisAudioQuality.LOSSLESS.permits(it), it.name)
        }
        // No lossless answer is wanted, and a source that can serve one is the slow half of the
        // list.
        assertFalse(AurisAudioQuality.HIGH.permits(AurisSourceKind.ADDON))
        assertFalse(AurisAudioQuality.HIGH.permits(AurisSourceKind.CUSTOM_MODULE))
        assertTrue(AurisAudioQuality.HIGH.permits(AurisSourceKind.JIOSAAVN))
        // The lower rungs are YouTube's own ladder and nothing else.
        listOf(AurisAudioQuality.MEDIUM, AurisAudioQuality.LOW).forEach { rung ->
            assertFalse(rung.permits(AurisSourceKind.ADDON), rung.name)
            assertFalse(rung.permits(AurisSourceKind.JIOSAAVN), rung.name)
        }
    }

    @Test
    fun youtubeAnswersOnEveryRung() {
        // It is what the cap applies to, and the only source that can answer at all when the ones
        // above it are skipped.
        AurisAudioQuality.entries.forEach {
            assertTrue(it.permits(AurisSourceKind.YOUTUBE), it.name)
        }
    }

    @Test
    fun onlyTheLowestRungIsActuallyABitrateCap() {
        assertEquals(64, AurisAudioQuality.LOW.maxKbps)
        listOf(AurisAudioQuality.MEDIUM, AurisAudioQuality.HIGH, AurisAudioQuality.LOSSLESS)
            .forEach { assertEquals(Int.MAX_VALUE, it.maxKbps, it.name) }
    }

    @Test
    fun anOlderBuildsCeilingIsMigratedRatherThanReinterpreted() {
        // Three-rung builds had no source gating, so their "HIGH" meant "best available from
        // anything" — this model's LOSSLESS.
        assertEquals(AurisAudioQuality.LOSSLESS, AurisAudioQuality.stored("HIGH", hasFourRungs = false))
        assertEquals(AurisAudioQuality.MEDIUM, AurisAudioQuality.stored("MEDIUM", hasFourRungs = false))
        assertEquals(AurisAudioQuality.LOW, AurisAudioQuality.stored("LOW", hasFourRungs = false))
        // Once the four rungs have been seen, a stored HIGH is a real choice.
        assertEquals(AurisAudioQuality.HIGH, AurisAudioQuality.stored("HIGH", hasFourRungs = true))
    }

    @Test
    fun anUnsetOrUnreadableCeilingDefaultsToLossless() {
        assertEquals(AurisAudioQuality.LOSSLESS, AurisAudioQuality.stored(null, hasFourRungs = true))
        assertEquals(AurisAudioQuality.LOSSLESS, AurisAudioQuality.stored("", hasFourRungs = true))
        assertEquals(AurisAudioQuality.LOSSLESS, AurisAudioQuality.stored("nonsense", hasFourRungs = true))
        // The download rungs' spelling, in case one was ever written here.
        assertEquals(AurisAudioQuality.MEDIUM, AurisAudioQuality.stored("STANDARD", hasFourRungs = true))
    }
}
