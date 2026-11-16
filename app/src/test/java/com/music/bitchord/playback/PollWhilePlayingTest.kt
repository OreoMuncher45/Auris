package com.music.bitchord.playback

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The scrubber poll behind [rememberPlayerView]: it runs only while playing,
 * stops on pause, starts again on play, and does not stack loops.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PollWhilePlayingTest {
    private val playing = mutableStateOf(false)

    private fun setPlaying(value: Boolean) {
        Snapshot.withMutableSnapshot { playing.value = value }
        Snapshot.sendApplyNotifications()
    }

    @Test fun `ticks every interval while playing and not while paused`() = runTest {
        var ticks = 0
        backgroundScope.launch { pollWhilePlaying({ playing.value }, intervalMs = 500) { ticks++ } }
        runCurrent()
        advanceTimeBy(2_000)
        assertEquals("paused from the start", 0, ticks)

        setPlaying(true)
        runCurrent()
        assertEquals("first tick on play", 1, ticks)
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals("then one per 500 ms", 4, ticks)

        setPlaying(false)
        runCurrent()
        advanceTimeBy(5_000)
        assertEquals("nothing while paused", 4, ticks)
    }

    @Test fun `play after pause starts one loop, not a second alongside the first`() = runTest {
        var ticks = 0
        backgroundScope.launch { pollWhilePlaying({ playing.value }, intervalMs = 500) { ticks++ } }
        runCurrent()
        repeat(3) {
            setPlaying(true)
            runCurrent()
            setPlaying(false)
            runCurrent()
        }
        val before = ticks
        setPlaying(true)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        // One loop: a tick on start and one per 500 ms after it.
        assertEquals(3, ticks - before)
    }

    /**
     * A pause and a resume that both land before the flow looks again. The
     * player writes its state from a listener between frames, so the flow can
     * see true, then true: no emission. The loop has to keep going through
     * that rather than notice the pause on its own and stop for good.
     */
    @Test fun `a pause and resume between two looks of the flow does not stop the poll`() = runTest {
        var ticks = 0
        backgroundScope.launch { pollWhilePlaying({ playing.value }, intervalMs = 500) { ticks++ } }
        runCurrent()
        setPlaying(true)
        runCurrent()
        assertEquals(1, ticks)
        // Paused, with no apply notification yet: the flow has not seen it.
        playing.value = false
        advanceTimeBy(600)
        runCurrent()
        // Resumed before the flow looked: it reads true again and emits nothing.
        playing.value = true
        Snapshot.sendApplyNotifications()
        runCurrent()
        val before = ticks
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals("still polling every 500 ms", 3, ticks - before)
    }
}
