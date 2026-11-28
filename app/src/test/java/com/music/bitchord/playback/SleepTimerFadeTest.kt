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

    /** Accepts every fade step, then refuses the first [refusedRestores] tries at the restore. */
    private class RefusesRestore(override val level: Double, private var refusedRestores: Int) : StreamVolume {
        val asked = mutableListOf<Double>()
        override suspend fun set(level: Double): Boolean {
            asked += level
            if (level == this.level && asked.size > 1 && refusedRestores > 0) {
                refusedRestores--
                return false
            }
            return true
        }
    }

    @Test
    fun aSlowReceiverDoesNotPushThePausePastTheDeadline() = runTest {
        // Every volume step takes 400 ms to be answered.
        val volume = object : StreamVolume {
            override val level = 0.8
            override suspend fun set(level: Double): Boolean {
                kotlinx.coroutines.delay(400L)
                return true
            }
        }
        var pausedAt = -1L
        SleepTimer.fadeOutCast(
            volume = volume,
            spanMs = 2_000L,
            stepMs = 500L,
            now = { testScheduler.currentTime },
            castActive = { true },
            playing = { true },
            pause = { pausedAt = testScheduler.currentTime; true },
        )
        // At most one slow step past the deadline, not one per step.
        assertTrue("paused at $pausedAt", pausedAt in 2_000L..2_400L)
    }

    @Test
    fun aRefusedVolumeRestoreIsRetriedSoTheNextPlayIsNotSilent() = runTest {
        val volume = RefusesRestore(level = 0.8, refusedRestores = 1)
        val result = SleepTimer.fadeOutCast(
            volume = volume,
            spanMs = 1_000L,
            stepMs = 500L,
            now = { testScheduler.currentTime },
            castActive = { true },
            playing = { true },
            pause = { true },
        )
        assertEquals(SleepTimer.CastFade.PAUSED, result)
        // Down to silence, the restore refused once, then accepted.
        assertEquals(listOf(0.8, 0.4, 0.0, 0.8, 0.8), volume.asked.map { Math.round(it * 10) / 10.0 })
    }

    @Test
    fun aRestoreRefusedEveryTimeStopsAfterItsAttempts() = runTest {
        val volume = RefusesRestore(level = 0.8, refusedRestores = Int.MAX_VALUE)
        SleepTimer.fadeOutCast(
            volume = volume,
            spanMs = 1_000L,
            stepMs = 500L,
            now = { testScheduler.currentTime },
            castActive = { true },
            playing = { true },
            pause = { true },
        )
        assertEquals(SleepTimer.CAST_RESTORE_ATTEMPTS, volume.asked.drop(3).size)
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
        // One refused fade step and no fade after it. That step may still have
        // reached the receiver, so the original level is asked back, retried
        // as a refused restore is.
        assertEquals(1 + SleepTimer.CAST_RESTORE_ATTEMPTS, volume.asked.size)
        assertEquals(List(SleepTimer.CAST_RESTORE_ATTEMPTS) { 0.8 }, volume.asked.drop(1))
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

    @Test
    fun aRouteChangeWhileTheFinalPauseIsInFlightIsAMove() = runTest {
        var onReceiver = true
        val result = SleepTimer.fadeOutCast(null, 0L, 500L, { testScheduler.currentTime }, { onReceiver }, { true }) {
            onReceiver = false // playback resumed on the phone while the pause was awaited
            false
        }
        assertEquals(SleepTimer.CastFade.MOVED, result)
    }

    @Test
    fun theWatcherFollowsPlaybackAfterTheFade() {
        // A handoff during the awaited pause or the restore wins over what the fade reported.
        assertEquals(SleepTimer.AfterCastFade.CONTINUE_LOCAL, SleepTimer.afterCastFade(SleepTimer.CastFade.PAUSED, castActiveNow = false))
        assertEquals(SleepTimer.AfterCastFade.CONTINUE_LOCAL, SleepTimer.afterCastFade(SleepTimer.CastFade.PAUSE_FAILED, castActiveNow = false))
        assertEquals(SleepTimer.AfterCastFade.CONTINUE_LOCAL, SleepTimer.afterCastFade(SleepTimer.CastFade.MOVED, castActiveNow = true))
        assertEquals(SleepTimer.AfterCastFade.WAIT_DEADLINE, SleepTimer.afterCastFade(SleepTimer.CastFade.LISTENER_PAUSED, castActiveNow = true))
        assertEquals(SleepTimer.AfterCastFade.CANCEL, SleepTimer.afterCastFade(SleepTimer.CastFade.PAUSED, castActiveNow = true))
        assertEquals(SleepTimer.AfterCastFade.CANCEL, SleepTimer.afterCastFade(SleepTimer.CastFade.PAUSE_FAILED, castActiveNow = true))
    }

    @Test
    fun theDeadlinePauseIsConfirmedOnTheReceiverAndRetried() = runTest {
        var asks = 0
        var local = 0
        SleepTimer.pauseAtDeadline(castActive = { true }, confirmCastPause = { ++asks == 2 }, pauseLocal = { local++ })
        assertEquals(2, asks)
        assertEquals(0, local)
    }

    @Test
    fun theDeadlinePauseFollowsPlaybackToThePhone() = runTest {
        var onReceiver = true
        var local = 0
        SleepTimer.pauseAtDeadline(castActive = { onReceiver }, confirmCastPause = { onReceiver = false; false }, pauseLocal = { local++ })
        assertEquals(1, local)
    }

    @Test
    fun aRouteChangeDuringTheFinalPauseAttemptIsAMove() = runTest {
        var onReceiver = true
        var attempts = 0
        val result = SleepTimer.fadeOutCast(null, 0L, 500L, { testScheduler.currentTime }, { onReceiver }, { true }) {
            attempts++
            // The earlier attempts are refused; playback moves to the phone while the last one is awaited.
            if (attempts == SleepTimer.CAST_PAUSE_ATTEMPTS) {
                kotlinx.coroutines.yield()
                onReceiver = false
            }
            false
        }
        assertEquals(SleepTimer.CAST_PAUSE_ATTEMPTS, attempts)
        assertEquals(SleepTimer.CastFade.MOVED, result)
        // And the watcher then follows playback to the phone instead of clearing the timer.
        assertEquals(SleepTimer.AfterCastFade.CONTINUE_LOCAL, SleepTimer.afterCastFade(result, castActiveNow = false))
    }

    @Test
    fun castEndingDuringAConfirmedDeadlinePausePausesThePhone() = runTest {
        var onReceiver = true
        var local = 0
        SleepTimer.pauseAtDeadline(
            castActive = { onReceiver },
            confirmCastPause = { onReceiver = false; true }, // confirmed, then playback resumed on the phone
            pauseLocal = { local++ },
        )
        assertEquals(1, local)
    }

    @Test
    fun castEndingDuringTheFinalFailedDeadlinePausePausesThePhone() = runTest {
        var onReceiver = true
        var asks = 0
        var local = 0
        SleepTimer.pauseAtDeadline(
            castActive = { onReceiver },
            confirmCastPause = { if (++asks == SleepTimer.CAST_PAUSE_ATTEMPTS) onReceiver = false; false },
            pauseLocal = { local++ },
        )
        assertEquals(SleepTimer.CAST_PAUSE_ATTEMPTS, asks)
        assertEquals(1, local)
    }

    @Test
    fun aConfirmedDeadlinePauseOnTheReceiverLeavesThePhoneAlone() = runTest {
        var local = 0
        SleepTimer.pauseAtDeadline(castActive = { true }, confirmCastPause = { true }, pauseLocal = { local++ })
        assertEquals(0, local)
    }

    @Test
    fun aPlayerAdoptedMidFadeKeepsTheFadesGain() {
        // A version swap landing while the timer fades: the new player goes in
        // at the faded level, not at full volume until the next step.
        assertEquals(0.2f, SleepTimer.handoffGain(0.2f), 0f)
        // No step has run yet: nothing has been turned down.
        assertEquals(1f, SleepTimer.handoffGain(null), 0f)
    }

    @Test
    fun anUpgradeCannotStartBlendingUnderTheSleepFade() {
        try {
            SleepTimer.fadingOut = true
            assertFalse("deferred, not blended in under the fade", SleepTimer.claimUpgradeBlend())
            assertFalse("so the fade has no blend to wait for", SleepTimer.upgradeBlending)
            assertFalse("and a stopped upgrade leaves the faded volume alone", SleepTimer.upgradeMayRestoreVolume)
        } finally {
            SleepTimer.fadingOut = false
            SleepTimer.releaseUpgradeBlend()
        }
    }

    @Test
    fun onlyARunningUpgradeBlendHoldsTheSleepFade() {
        try {
            // Preparing and aligning its standby: no claim yet, so a fade
            // starting now does not wait on it.
            assertFalse(SleepTimer.upgradeBlending)
            assertTrue(SleepTimer.claimUpgradeBlend())
            assertTrue("a blend writing both volumes is waited out", SleepTimer.upgradeBlending)
            SleepTimer.releaseUpgradeBlend()
            assertFalse(SleepTimer.upgradeBlending)
            assertTrue(SleepTimer.upgradeMayRestoreVolume)
        } finally {
            SleepTimer.releaseUpgradeBlend()
        }
    }

    /** Applies each level, but answers [answers] in turn (true by default once they run out). */
    private class LateReceiver(override val level: Double, private val answers: MutableList<Boolean>) : StreamVolume {
        val asked = mutableListOf<Double>()
        val restoredLater = mutableListOf<Double>()
        override suspend fun set(level: Double): Boolean {
            asked += level
            return if (answers.isEmpty()) true else answers.removeAt(0)
        }
        override fun restoreLater(level: Double) {
            restoredLater += level
        }
    }

    @Test
    fun aFadeStepThatTimedOutIsStillRestored() = runTest {
        // The first step reached the receiver but its answer came too late.
        val volume = LateReceiver(level = 0.8, answers = mutableListOf(false))
        SleepTimer.fadeOutCast(volume, 2_000L, 500L, { testScheduler.currentTime }, { true }, { true }, { true })
        assertEquals("the level it had is put back", 0.8, volume.asked.last(), 0.0)
        assertTrue(volume.restoredLater.isEmpty())
    }

    @Test
    fun aRestoreRefusedEveryTimeIsKeptForTheNextPlay() = runTest {
        // The fade and pause go through; every restore attempt is refused.
        val answers = MutableList(5) { true }.apply { addAll(List(SleepTimer.CAST_RESTORE_ATTEMPTS) { false }) }
        val volume = LateReceiver(level = 0.6, answers = answers)
        val fade = SleepTimer.fadeOutCast(volume, 2_000L, 500L, { testScheduler.currentTime }, { true }, { true }, { true })
        assertEquals(SleepTimer.CastFade.PAUSED, fade)
        assertEquals(listOf(0.6), volume.restoredLater)
    }

    @Test
    fun aVersionSwapIsAdoptedOnlyOnTheTrackItBeganOn() {
        assertTrue(versionSwapStillCurrent(true, 3, 3, "a", "a"))
        assertFalse("the track moved on while the standby aligned", versionSwapStillCurrent(true, 3, 4, "a", "b"))
        assertFalse("same index, another entry", versionSwapStillCurrent(true, 3, 3, "a", "b"))
        assertFalse("the players were rebuilt", versionSwapStillCurrent(false, 3, 3, "a", "a"))
    }
}
