package com.music.bitchord.ui.onboarding

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The catalogue-change restart kills the process, so the finished flow is
 * committed in one synchronous write first rather than left to apply().
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OnboardingCommitTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun commitFinishedWritesPicksNameAndCompletedFlag() {
        val picks = listOf(
            OnboardingState.Pick("Artist A", "https://img/a", "UC_a"),
            OnboardingState.Pick("Artist B"),
        )

        val saved = OnboardingState.commitFinished(context, picks, "  Listener  ")

        assertTrue(saved)
        assertTrue(OnboardingState.isCompleted(context))
        assertEquals("Listener", OnboardingState.name(context))
        assertEquals(picks, OnboardingState.picks(context))
    }
}
