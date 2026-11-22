package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class SleepTimerFadeTest {
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
}
