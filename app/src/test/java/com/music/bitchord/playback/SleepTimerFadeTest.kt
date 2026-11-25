package com.music.bitchord.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerFadeTest {

    /** A receiver's stream volume that records every level asked of it. */
    private class FakeStreamVolume(override val level: Double, private val accepts: Boolean = true) : StreamVolume {
        val asked = mutableListOf<Double>()
        override suspend fun set(level: Double): Boolean {
            asked += level
            return accepts
        }
    }

    @Test
    fun fullVolumeUntilTheFadeStarts() {
        assertEquals(1f, SleepTimer.fadeVolume(45_000L), 0.001f)
        assertEquals(1f, SleepTimer.fadeVolume(30_000L), 0.001f)
    }

    @Test
    fun fadesInAStraightLineToSilenceAtTheDeadline() {
        assertEquals(0.5f, SleepTimer.fadeVolume(15_000L), 0.001f)
        assertEquals(0.25f, SleepTimer.fadeVolume(7_500L), 0.001f)
        assertEquals(0f, SleepTimer.fadeVolume(0L), 0.001f)
        assertEquals(0f, SleepTimer.fadeVolume(-100L), 0.001f)
    }

    @Test
    fun aShorterFadeSpansWhatIsLeft() {
        // A fade that could only start late (a blend was landing) runs over
        // the time that was left rather than starting part-way down.
        assertEquals(1f, SleepTimer.fadeVolume(10_000L, fadeMs = 10_000L), 0.001f)
        assertEquals(0.5f, SleepTimer.fadeVolume(5_000L, fadeMs = 10_000L), 0.001f)
        // No fade at all: full volume up to the pause.
        assertEquals(1f, SleepTimer.fadeVolume(5_000L, fadeMs = 0L), 0.001f)
    }

    @Test
    fun theReceiverIsPausedWhileCasting() {
        val paused = mutableListOf<String>()
        SleepTimer.pauseFor(castActive = true, pauseCast = { paused += "cast" }, pauseLocal = { paused += "local" })
        assertEquals(listOf("cast"), paused)
    }

    @Test
    fun thePhoneIsPausedWhenNotCasting() {
        val paused = mutableListOf<String>()
        SleepTimer.pauseFor(castActive = false, pauseCast = { paused += "cast" }, pauseLocal = { paused += "local" })
        assertEquals(listOf("local"), paused)
    }

    @Test
    fun aCastFadeGoesToSilenceThenPausesThenPutsTheVolumeBack() = runTest {
        val volume = FakeStreamVolume(level = 0.8)
        val events = mutableListOf<String>()
        val paused = SleepTimer.fadeOutCast(
            volume = volume,
            spanMs = 2_000L,
            stepMs = 500L,
            now = { testScheduler.currentTime },
            stillPlaying = { true },
            pause = { events += "pause at ${volume.asked.last()}" },
        )
        assertTrue(paused)
        // Down in steps from the starting level to silence, then back up.
        assertEquals(listOf(0.8, 0.6, 0.4, 0.2, 0.0, 0.8), volume.asked.map { Math.round(it * 10) / 10.0 })
        assertEquals(listOf("pause at 0.0"), events)
    }

    @Test
    fun aReceiverThatRefusesStreamVolumeIsJustPaused() = runTest {
        val volume = FakeStreamVolume(level = 0.8, accepts = false)
        var pauses = 0
        val paused = SleepTimer.fadeOutCast(
            volume = volume,
            spanMs = 30_000L,
            stepMs = 500L,
            now = { testScheduler.currentTime },
            stillPlaying = { true },
            pause = { pauses++ },
        )
        assertTrue(paused)
        assertEquals(1, pauses)
        // One refused ask and no fade or restore after it.
        assertEquals(1, volume.asked.size)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun noStreamVolumeAtAllIsJustAPause() = runTest {
        var pauses = 0
        SleepTimer.fadeOutCast(null, 30_000L, 500L, { testScheduler.currentTime }, { true }, { pauses++ })
        assertEquals(1, pauses)
    }

    @Test
    fun aPauseByTheListenerMidFadeCallsItOffAndPutsTheVolumeBack() = runTest {
        val volume = FakeStreamVolume(level = 0.5)
        var playing = true
        var pauses = 0
        var result: Boolean? = null
        val job = launch {
            result = SleepTimer.fadeOutCast(volume, 4_000L, 500L, { testScheduler.currentTime }, { playing }, { pauses++ })
        }
        advanceTimeBy(1_100L)
        playing = false
        advanceTimeBy(1_000L)
        job.join()
        assertFalse(result!!)
        assertEquals(0, pauses)
        assertEquals(0.5, volume.asked.last(), 0.0)
    }

    @Test
    fun aTimerResetMidFadePutsTheVolumeBack() = runTest {
        val volume = FakeStreamVolume(level = 0.5)
        var pauses = 0
        val job = launch {
            SleepTimer.fadeOutCast(volume, 4_000L, 500L, { testScheduler.currentTime }, { true }, { pauses++ })
        }
        advanceTimeBy(1_100L)
        runCurrent()
        job.cancel()
        job.join()
        assertEquals(0, pauses)
        assertTrue("it had faded part of the way", volume.asked.dropLast(1).last() < 0.5)
        assertEquals(0.5, volume.asked.last(), 0.0)
    }
}
