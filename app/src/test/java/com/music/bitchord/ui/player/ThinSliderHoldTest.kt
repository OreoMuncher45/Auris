/*
 * Copyright (C) 2026 BitChord
 *
 * A hold on the scrubber's Automix window opens the panel; it does not scrub.
 *
 * A finger never lies perfectly still, so a hold reports small moves while it
 * waits. Those used to turn the hold into a scrub on the first pixel, and each
 * one restarted the long-press clock, so a hold could neither open the panel
 * nor be told apart from a drag.
 */

package com.music.bitchord.ui.player

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
// The plain Application, as ThinSliderInteractionTest explains.
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xhdpi")
class ThinSliderHoldTest {

    @get:Rule
    val compose = createComposeRule()

    private val reported = mutableListOf<Float>()
    private var held = 0

    private fun setBar() {
        compose.setContent {
            Box(Modifier.width(200.dp).testTag(BAR)) {
                ThinSlider(
                    value = 0f,
                    onValueChange = { reported += it },
                    transitionWindow = 0.4f..0.6f,
                    onTransitionWindowLongPress = { held++ },
                )
            }
        }
    }

    /** A wobble inside the touch slop is still a hold: the panel opens and nothing seeks. */
    @Test
    fun aWobbleInsideTheSlopStaysAHold() {
        setBar()
        compose.onNodeWithTag(BAR).performTouchInput {
            down(center)
            moveBy(Offset(1f, 0f), delayMillis = 50)
            moveBy(Offset(-1f, 1f), delayMillis = 50)
        }
        compose.mainClock.advanceTimeBy(LONG_PRESS_MS * 2)
        compose.waitForIdle()

        assertEquals("a hold inside the window opens the panel", 1, held)
        assertTrue("a hold must not seek", reported.isEmpty())
        compose.onNodeWithTag(BAR).performTouchInput { up() }
    }

    /** A move past the slop is a scrub after all, and the hold is off. */
    @Test
    fun aMovePastTheSlopScrubs() {
        setBar()
        compose.onNodeWithTag(BAR).performTouchInput {
            down(center)
            moveBy(Offset(width * 0.2f, 0f), delayMillis = 50)
            up()
        }
        compose.mainClock.advanceTimeBy(LONG_PRESS_MS * 2)
        compose.waitForIdle()

        assertEquals(0, held)
        assertTrue("a drag must seek", reported.isNotEmpty())
    }

    /**
     * The long-press is counted from the press. Events that arrive while the
     * finger is held do not push it back, so a hold that reports often still
     * opens the panel on time.
     */
    @Test
    fun eventsDuringTheHoldDoNotRestartTheClock() {
        setBar()
        // Time moves only with the finger's own events, so nothing but the
        // hold's clock can open the panel.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(BAR).performTouchInput {
            down(center)
            // A second finger tapping beside it: events for the held pointer
            // that do not move it. Each gap is shorter than the long-press,
            // the total is longer.
            repeat(4) {
                advanceEventTime(LONG_PRESS_MS / 2)
                if (it % 2 == 0) down(1, Offset(1f, 1f)) else up(1)
            }
        }

        assertEquals("the hold had already lasted past the long-press", 1, held)
        assertTrue(reported.isEmpty())
        compose.onNodeWithTag(BAR).performTouchInput { up() }
    }

    private companion object {
        const val BAR = "scrubber"
        /** At least ViewConfiguration's long-press timeout on the test device (400-500 ms). */
        const val LONG_PRESS_MS = 500L
    }
}
