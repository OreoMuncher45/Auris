package com.music.auris.desktop

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sleep timer's two modes and the boundary between them.
 *
 * A timer that quietly does not run is indistinguishable from one that has not
 * fired yet, so its state has to be checkable rather than watched.
 */
class AurisSleepTimerTest {

    @AfterTest
    fun tearDown() = AurisSleepTimer.cancel()

    @Test
    fun nothingIsSetUntilSomethingStartsIt() {
        AurisSleepTimer.cancel()
        assertNull(AurisSleepTimer.minutes.value)
        assertNull(AurisSleepTimer.remainingMs())
        assertFalse(AurisSleepTimer.afterTrack.value)
        assertFalse(AurisSleepTimer.isExpired())
    }

    @Test
    fun aDurationCountsDownFromWhatWasAsked() {
        AurisSleepTimer.start(30)
        assertEquals(30, AurisSleepTimer.minutes.value)
        val remaining = AurisSleepTimer.remainingMs()!!
        // Within a second of the full half hour, and never over it.
        assertTrue(remaining in 29 * 60_000L..30 * 60_000L, "remaining was $remaining")
        assertFalse(AurisSleepTimer.isExpired())
    }

    @Test
    fun theTwoModesDisplaceEachOther() {
        // Both at once has no meaning: one stops at a time, the other at the end
        // of the track, and the UI shows a single line either way.
        AurisSleepTimer.start(15)
        AurisSleepTimer.startAfterTrack()
        assertTrue(AurisSleepTimer.afterTrack.value)
        assertNull(AurisSleepTimer.minutes.value)
        assertNull(AurisSleepTimer.remainingMs())

        AurisSleepTimer.start(15)
        assertFalse(AurisSleepTimer.afterTrack.value)
        assertEquals(15, AurisSleepTimer.minutes.value)
    }

    @Test
    fun cancellingClearsBoth() {
        AurisSleepTimer.startAfterTrack()
        AurisSleepTimer.cancel()
        assertFalse(AurisSleepTimer.afterTrack.value)
        assertNull(AurisSleepTimer.minutes.value)
    }

    @Test
    fun aTimerOfNoLengthIsRefusedRatherThanFiringAtOnce() {
        assertFailsWith<IllegalArgumentException> { AurisSleepTimer.start(0) }
        assertFailsWith<IllegalArgumentException> { AurisSleepTimer.start(-5) }
    }

    @Test
    fun everyPresetIsAUsableDuration() {
        assertEquals(listOf(15, 30, 45, 60), AurisSleepTimer.presets)
        AurisSleepTimer.presets.forEach { AurisSleepTimer.start(it) }
    }
}
