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

    /**
     * A receiver's stream volume that records every level asked of it. It
     * suspends and checks for cancellation first, as the real round trip does,
     * so a restore attempted from a cancelled coroutine (without
     * NonCancellable) would throw here and never be recorded.
     */
    private class FakeStreamVolume(override val level: Double, private val accepts: Boolean = true) : StreamVolume {
        val asked = mutableListOf<Double>()
        override suspend fun set(level: Double): Boolean {
            kotlinx.coroutines.yield()
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
        val result = SleepTimer.fadeOutCast(
            volume = volume,
            spanMs = 2_000L,
            stepMs = 500L,
            now = { testScheduler.currentTime },
            castActive = { true },
            playing = { true },
            pause = { events += "pause at ${volume.asked.last()}"; true },
        )
        assertEquals(SleepTimer.CastFade.PAUSED, result)
        // Down in steps from the starting level to silence, then back up after the pause.
        assertEquals(listOf(0.8, 0.6, 0.4, 0.2, 0.0, 0.8), volume.asked.map { Math.round(it * 10) / 10.0 })
        assertEquals(listOf("pause at 0.0"), events)
    }

    @Test
    fun aReceiverThatRefusesStreamVolumeStillPausesAtTheDeadline() = runTest {
        val volume = FakeStreamVolume(level = 0.8, accepts = false)
        var pausedAt = -1L
        val result = SleepTimer.fadeOutCast(
            volume, 30_000L, 500L, { testScheduler.currentTime }, { true }, { true },
            pause = { pausedAt = testScheduler.currentTime; true },
        )
        assertEquals(SleepTimer.CastFade.PAUSED, result)
        assertEquals("not 30 s early", 30_000L, pausedAt)
        // One refused ask, no fade after it, and nothing to restore.
        assertEquals(1, volume.asked.size)
    }

    @Test
    fun noStreamVolumeAtAllPausesAtTheDeadline() = runTest {
        var pausedAt = -1L
        val result = SleepTimer.fadeOutCast(
            null, 30_000L, 500L, { testScheduler.currentTime }, { true }, { true },
            pause = { pausedAt = testScheduler.currentTime; true },
        )
        assertEquals(SleepTimer.CastFade.PAUSED, result)
        assertEquals(30_000L, pausedAt)
    }

    @Test
    fun aPauseByTheListenerMidFadeIsReportedAsSuchAndPutsTheVolumeBack() = runTest {
        val volume = FakeStreamVolume(level = 0.5)
        var playing = true
        var pauses = 0
        var result: SleepTimer.CastFade? = null
        val job = launch {
            result = SleepTimer.fadeOutCast(volume, 4_000L, 500L, { testScheduler.currentTime }, { true }, { playing }) { pauses++; true }
        }
        advanceTimeBy(1_100L)
        playing = false
        advanceTimeBy(1_000L)
        job.join()
        assertEquals(SleepTimer.CastFade.LISTENER_PAUSED, result)
        assertEquals(0, pauses)
        assertEquals(0.5, volume.asked.last(), 0.0)
    }

    @Test
    fun playbackMovingToThePhoneIsReportedAsAMoveNotAPause() = runTest {
        val volume = FakeStreamVolume(level = 0.5)
        var onReceiver = true
        var result: SleepTimer.CastFade? = null
        val job = launch {
            result = SleepTimer.fadeOutCast(volume, 4_000L, 500L, { testScheduler.currentTime }, { onReceiver }, { true }) { true }
        }
        advanceTimeBy(1_100L)
        onReceiver = false
        advanceTimeBy(1_000L)
        job.join()
        assertEquals(SleepTimer.CastFade.MOVED, result)
        assertEquals(0.5, volume.asked.last(), 0.0)
    }

    @Test
    fun anUnconfirmedPauseIsRetriedThenReportedAsFailed() = runTest {
        var asks = 0
        val result = SleepTimer.fadeOutCast(null, 0L, 500L, { testScheduler.currentTime }, { true }, { true }) { asks++; false }
        assertEquals(SleepTimer.CastFade.PAUSE_FAILED, result)
        assertEquals(SleepTimer.CAST_PAUSE_ATTEMPTS, asks)
    }

    @Test
    fun aTimerResetMidFadePutsTheVolumeBack() = runTest {
        val volume = FakeStreamVolume(level = 0.5)
        var pauses = 0
        val job = launch {
            SleepTimer.fadeOutCast(volume, 4_000L, 500L, { testScheduler.currentTime }, { true }, { true }) { pauses++; true }
        }
        advanceTimeBy(1_100L)
        runCurrent()
        job.cancel()
        job.join()
        assertEquals(0, pauses)
        assertTrue("it had faded part of the way", volume.asked.dropLast(1).last() < 0.5)
        assertEquals("restored despite the cancellation", 0.5, volume.asked.last(), 0.0)
    }
}
