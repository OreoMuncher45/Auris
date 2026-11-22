package com.music.bitchord.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ConvertToVideoPromptTest {

    @Test
    fun anAlreadyShownSongGetsNoSecondPrompt() = runTest {
        val state = ConvertToVideoPromptState(initiallyVisible = false)
        var shown = 0

        state.runCountdown { shown++ }

        assertFalse(state.visible)
        assertEquals(0f, state.remainingFraction)
        assertEquals(0, shown)
    }

    @Test
    fun theTimeoutLineAndPromptFinishAtFiveSeconds() = runTest {
        val state = ConvertToVideoPromptState(initiallyVisible = true)
        var shown = 0
        val countdown = launch { state.runCountdown { shown++ } }
        runCurrent()
        assertEquals(1, shown)
        assertEquals(1f, state.remainingFraction)

        advanceTimeBy(2_500)
        assertTrue(state.visible)
        assertTrue(state.remainingFraction in 0.45f..0.55f)

        advanceTimeBy(2_499)
        runCurrent()
        assertTrue(state.visible)
        advanceTimeBy(1)
        runCurrent()
        assertFalse(state.visible)
        assertEquals(0f, state.remainingFraction)
        countdown.join()
    }

    @Test
    fun dismissingOrLeavingCancelsTheOneShot() = runTest {
        val state = ConvertToVideoPromptState(initiallyVisible = true)
        val countdown = launch { state.runCountdown {} }
        runCurrent()
        advanceTimeBy(1_000)

        state.dismiss()
        countdown.cancelAndJoin()

        assertFalse(state.visible)
        assertEquals(0f, state.remainingFraction)
        state.runCountdown { error("a consumed prompt must not restart") }
    }
}
