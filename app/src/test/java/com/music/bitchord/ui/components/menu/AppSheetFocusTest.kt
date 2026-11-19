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
import androidx.compose.ui.graphics.Color
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

    @Test
    fun aSheetGoingLeavesTheFocusOfOneOpenedInItsPlace() {
        AppUi.install(AndroidAppUiHost)
        var first by mutableStateOf<String?>(null)
        var second by mutableStateOf<String?>(null)
        var firstColor by mutableStateOf(Color.Black)
        var focused = false
        val field = FocusRequester()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    AppSheet(item = first, onDismiss = { first = null }, containerColor = firstColor) {
                        Box(Modifier.fillMaxWidth().height(200.dp))
                    }
                    AppSheet(item = second, onDismiss = { second = null }) {
                        BasicTextField(
                            value = "Road trip",
                            onValueChange = {},
                            modifier = Modifier
                                .focusRequester(field)
                                .onFocusChanged { focused = it.isFocused },
                        )
                    }
                }
            }
        }
        first = "downloads"
        compose.waitForIdle()

        compose.mainClock.autoAdvance = false
        // The first goes and a second opens in its place, whose field takes
        // the focus while the first is still on its way out (220 ms).
        first = null
        second = "form"
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(48)
        compose.runOnIdle { field.requestFocus() }
        compose.mainClock.advanceTimeBy(16)
        compose.runOnIdle { assertTrue("the second sheet's field has the focus", focused) }

        // The first, mid-exit, recomposes.
        firstColor = Color.DarkGray
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(16)
        compose.runOnIdle { }
        assertTrue("the going sheet leaves the field the focus", focused)
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

        // It landed on the sheet's handle; Tab moves on to its control.
        compose.onRoot().performKeyInput { pressKey(Key.Tab) }
        compose.waitForIdle()
        assertTrue("Tab reaches the sheet's own control", insideFocused)

        // Shift+Tab, the way back out a keyboard would take: twice, past the
        // handle and on towards whatever is behind.
        compose.onNodeWithTag(INSIDE).performKeyInput {
            withKeyDown(Key.ShiftLeft) {
                pressKey(Key.Tab)
                pressKey(Key.Tab)
            }
        }
        compose.waitForIdle()
        assertFalse("Shift+Tab doesn't reach what is behind the sheet", behindFocused)
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
        // Opened by touch: the focus moves onto the sheet, off what is behind.
        item = "downloads"
        compose.waitForIdle()
        assertFalse("the focus left what is behind the sheet", behindFocused)

        // Then a key is pressed, and Tab, then Enter: the keys a keyboard
        // would reach the page with.
        modes.mode = InputMode.Keyboard
        compose.waitForIdle()
        compose.onRoot().performKeyInput { pressKey(Key.Tab) }
        compose.waitForIdle()
        assertTrue("Tab reaches the sheet's own control", insideFocused)
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
    fun aKeyboardComingIntoUseLeavesTheFocusWithWhatCoversTheSheet() {
        AppUi.install(AndroidAppUiHost)
        var item by mutableStateOf<String?>(null)
        var onTop by mutableStateOf(true)
        var fieldFocused = false
        val field = FocusRequester()
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
                        AppSheet(item = item, onDismiss = { item = null }, trapFocus = { onTop }) {
                            Box(Modifier.fillMaxWidth().height(200.dp))
                        }
                        // What covers it — a sheet stacked on it — with a
                        // field the listener is typing in.
                        BasicTextField(
                            value = "",
                            onValueChange = {},
                            modifier = Modifier
                                .focusRequester(field)
                                .onFocusChanged { fieldFocused = it.isFocused },
                        )
                    }
                }
            }
        }
        item = "downloads"
        compose.waitForIdle()
        onTop = false
        compose.runOnIdle { field.requestFocus() }
        compose.waitForIdle()
        assertTrue(fieldFocused)

        modes.mode = InputMode.Keyboard
        compose.waitForIdle()
        assertTrue("the covered sheet leaves the field the focus", fieldFocused)
    }

    @OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
    @Test
    fun aMenuClosingOverTheSheetGivesItTheFocusBack() {
        AppUi.install(AndroidAppUiHost)
        var item by mutableStateOf<String?>(null)
        var behindFocused = false
        var insideFocused = false
        val menus = MenuHostState()
        compose.setContent {
            val modes = LocalInputModeManager.current
            CompositionLocalProvider(LocalMenuHost provides menus) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize()) {
                        Box(
                            Modifier
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
                        MenuHostOverlay(menus)
                    }
                }
            }
            LaunchedEffect(Unit) { modes.requestInputMode(InputMode.Keyboard) }
        }
        item = "downloads"
        compose.waitForIdle()

        // A menu opened from one of the sheet's rows takes the focus…
        menus.open(null) { close ->
            MenuRow(icon = null, label = "Sort", ink = androidx.compose.ui.graphics.Color.White) { close() }
        }
        compose.waitForIdle()
        // …and closing it hands the focus back to the sheet, not to nothing.
        menus.close()
        compose.waitForIdle()
        compose.onRoot().performKeyInput { pressKey(Key.Tab) }
        compose.waitForIdle()
        assertFalse("the focus didn't land behind the sheet", behindFocused)
        assertTrue("one Tab from the sheet's handle reaches its control", insideFocused)
    }

    @OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
    @Test
    fun aSheetStackedOnAnotherClosingGivesItTheFocusBack() {
        AppUi.install(AndroidAppUiHost)
        var parent by mutableStateOf<String?>(null)
        var child by mutableStateOf<String?>(null)
        var behindFocused = false
        var insideFocused = false
        compose.setContent {
            val modes = LocalInputModeManager.current
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .onFocusChanged { behindFocused = it.isFocused }
                            .focusable(),
                    )
                    AppSheet(item = parent, onDismiss = { parent = null }, trapFocus = { child == null }) {
                        Box(
                            Modifier
                                .testTag(INSIDE)
                                .onFocusChanged { insideFocused = it.isFocused }
                                .focusable(),
                        )
                    }
                    AppSheet(item = child, onDismiss = { child = null }) {
                        Box(Modifier.fillMaxWidth().height(120.dp).focusable())
                    }
                }
            }
            LaunchedEffect(Unit) { modes.requestInputMode(InputMode.Keyboard) }
        }
        parent = "colours"
        compose.waitForIdle()
        child = "picker"
        compose.waitForIdle()
        child = null
        compose.waitForIdle()

        compose.onRoot().performKeyInput { pressKey(Key.Tab) }
        compose.waitForIdle()
        assertFalse("the focus didn't land behind the sheets", behindFocused)
        assertTrue("one Tab from the parent's handle reaches its control", insideFocused)
    }

    @Test
    fun aSheetOpeningUnderADialogLeavesTheDialogItsField() {
        AppUi.install(AndroidAppUiHost)
        var item by mutableStateOf<String?>(null)
        var fieldFocused = false
        val field = FocusRequester()
        val dialogs = DialogHostState()
        compose.setContent {
            CompositionLocalProvider(LocalDialogHost provides dialogs) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize()) {
                        AppSheet(item = item, onDismiss = { item = null }) {
                            Box(Modifier.fillMaxWidth().height(200.dp))
                        }
                        // The dialog's field, typed in.
                        BasicTextField(
                            value = "",
                            onValueChange = {},
                            modifier = Modifier
                                .focusRequester(field)
                                .onFocusChanged { fieldFocused = it.isFocused },
                        )
                    }
                }
            }
        }
        dialogs.open { _ -> }
        compose.runOnIdle { field.requestFocus() }
        compose.waitForIdle()
        assertTrue(fieldFocused)

        item = "downloads"
        compose.waitForIdle()
        assertTrue("the dialog over the sheet keeps its field", fieldFocused)
    }

    @Test
    fun aTouchOpenedSheetTakesTheFocusOffAFieldBehindIt() {
        AppUi.install(AndroidAppUiHost)
        var item by mutableStateOf<String?>(null)
        var fieldFocused = false
        val field = FocusRequester()
        val modes = object : InputModeManager {
            override val inputMode: InputMode get() = InputMode.Touch
            @ExperimentalComposeUiApi
            override fun requestInputMode(inputMode: InputMode): Boolean = false
        }
        compose.setContent {
            CompositionLocalProvider(LocalInputModeManager provides modes) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize()) {
                        // A search field behind, with the software keyboard up.
                        BasicTextField(
                            value = "",
                            onValueChange = {},
                            modifier = Modifier
                                .focusRequester(field)
                                .onFocusChanged { fieldFocused = it.isFocused },
                        )
                        AppSheet(item = item, onDismiss = { item = null }) {
                            Box(Modifier.fillMaxWidth().height(200.dp))
                        }
                    }
                }
            }
        }
        compose.runOnIdle { field.requestFocus() }
        compose.waitForIdle()
        assertTrue(fieldFocused)

        item = "sheet"
        compose.waitForIdle()
        assertFalse("the covered field no longer takes the typing", fieldFocused)
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
