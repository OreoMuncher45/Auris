package com.music.bitchord.ui.components.menu

import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.clickable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.focusable
import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.ui.AndroidAppUiHost
import com.music.bitchord.ui.AppUi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A sheet drawn in the page's own window has to manage focus itself: a form
 * on a sheet that is going lets go of the focus at once, so the keyboard goes
 * down with it and a Done can't submit what the listener just dismissed.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class AppSheetFocusTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aSheetGoingTakesTheFocusWithIt() {
        AppUi.install(AndroidAppUiHost)
        var item by mutableStateOf<String?>(null)
        var focused = false
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    AppSheet(item = item, onDismiss = { item = null }) {
                        BasicTextField(
                            value = "Road trip",
                            onValueChange = {},
                            modifier = Modifier
                                .testTag(FIELD)
                                .onFocusChanged { focused = it.isFocused },
                        )
                    }
                }
            }
        }
        item = "form"
        compose.waitForIdle()
        compose.onNodeWithTag(FIELD).performClick()
        compose.waitForIdle()
        assertTrue("the form takes the focus while the sheet is up", focused)

        compose.mainClock.autoAdvance = false
        item = null
        Snapshot.sendApplyNotifications()
        // A few frames into the exit (220 ms), well before it ends.
        compose.mainClock.advanceTimeBy(80)
        compose.runOnIdle { }
        // Still drawn for its exit, and already holding nothing.
        assertFalse("the form lets go of the focus as the sheet goes", focused)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    @OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
    @Test
    fun withAKeyboardTheSheetTakesTheFocusFromWhatOpenedIt() {
        AppUi.install(AndroidAppUiHost)
        var item by mutableStateOf<String?>(null)
        var behindFocused = false
        var insideFocused = false
        val behind = FocusRequester()
        compose.setContent {
            val modes = LocalInputModeManager.current
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    // The button that opened it, with the focus.
                    Box(
                        Modifier
                            .focusRequester(behind)
                            .onFocusChanged { behindFocused = it.isFocused }
                            .focusable(),
                    )
                    AppSheet(item = item, onDismiss = { item = null }) {
                        Box(
                            Modifier
                                .testTag(INSIDE)
                                .onFocusChanged { insideFocused = it.isFocused }
                                .focusable(),
                        )
                    }
                }
            }
            LaunchedEffect(Unit) { modes.requestInputMode(InputMode.Keyboard) }
        }
        compose.runOnIdle { behind.requestFocus() }
        compose.waitForIdle()
        assertTrue(behindFocused)

        item = "downloads"
        compose.waitForIdle()
        assertFalse("the sheet took the focus from what is behind it", behindFocused)
        assertTrue("…and it is inside the sheet", insideFocused)

        // Shift+Tab, the way back out a keyboard would take.
        compose.onNodeWithTag(INSIDE).performKeyInput {
            withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) }
        }
        compose.waitForIdle()
        assertFalse("Shift+Tab doesn't reach what is behind the sheet", behindFocused)
        assertTrue("the focus stays in the sheet", insideFocused)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aKeyboardComingIntoUseWhileTheSheetIsUpBringsTheFocusIn() {
        AppUi.install(AndroidAppUiHost)
        var item by mutableStateOf<String?>(null)
        var behindFocused = false
        var insideFocused = false
        var behindClicks = 0
        var insideClicks = 0
        val behind = FocusRequester()
        // The test host has no touch mode to enter, so the mode is the test's.
        val modes = object : InputModeManager {
            var mode by mutableStateOf(InputMode.Touch)
            override val inputMode: InputMode get() = mode
            @ExperimentalComposeUiApi
            override fun requestInputMode(inputMode: InputMode): Boolean {
                mode = inputMode
                return true
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalInputModeManager provides modes) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize()) {
                        Box(
                            Modifier
                                .focusRequester(behind)
                                .onFocusChanged { behindFocused = it.isFocused }
                                // Focusable in touch mode too, which a plain
                                // clickable isn't: the test needs it to really
                                // hold the focus before the switch.
                                .focusProperties { canFocus = true }
                                .clickable { behindClicks++ },
                        )
                        AppSheet(item = item, onDismiss = { item = null }) {
                            Box(
                                Modifier
                                    .testTag(INSIDE)
                                    .onFocusChanged { insideFocused = it.isFocused }
                                    .clickable { insideClicks++ },
                            )
                        }
                    }
                }
            }
        }
        compose.runOnIdle { behind.requestFocus() }
        compose.waitForIdle()
        assertTrue("the control behind holds the focus to begin with", behindFocused)
        // Opened by touch: nothing moves the focus.
        item = "downloads"
        compose.waitForIdle()
        assertFalse("a touch-opened sheet takes nothing", insideFocused)
        assertTrue("the control behind still holds the focus", behindFocused)

        // Then a key is pressed.
        modes.mode = InputMode.Keyboard
        compose.waitForIdle()
        assertFalse("the focus left what is behind the sheet", behindFocused)
        assertTrue("…for the sheet", insideFocused)

        // Tab, then Enter: the keys a keyboard would reach the page with.
        compose.onNodeWithTag(INSIDE).performKeyInput {
            pressKey(Key.Tab)
            pressKey(Key.Enter)
        }
        compose.waitForIdle()
        assertFalse("Tab doesn't reach what is behind the sheet", behindFocused)
        assertEquals("nothing behind the sheet was activated", 0, behindClicks)
        assertEquals("Enter acted on the sheet's own control", 1, insideClicks)
    }

    @Test
    fun aDismissalSwappedWhileOpenIsTheOneADragCalls() {
        AppUi.install(AndroidAppUiHost)
        var item by mutableStateOf<String?>(null)
        var first = 0
        var second = 0
        var dismiss by mutableStateOf<() -> Unit>({ first++ })
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    AppSheet(item = item, onDismiss = dismiss) {
                        Box(Modifier.testTag(BODY).fillMaxWidth().height(300.dp))
                    }
                }
            }
        }
        item = "sheet"
        compose.waitForIdle()
        // Swapped after the gesture handler and the list connection were made.
        dismiss = { second++ }
        compose.waitForIdle()

        compose.onNodeWithTag(BODY).performTouchInput { swipeDown(startY = 10f, endY = bottom, durationMillis = 600) }
        compose.waitForIdle()
        assertEquals("the stale dismissal isn't called", 0, first)
        assertEquals("the current one is", 1, second)
    }

    private companion object {
        const val BODY = "body"
        const val INSIDE = "inside"
        const val FIELD = "field"
    }
}
