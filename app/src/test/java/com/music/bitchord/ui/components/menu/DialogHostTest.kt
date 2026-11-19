package com.music.bitchord.ui.components.menu

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.AndroidAppUiHost
import com.music.bitchord.ui.AppUi
import com.music.bitchord.ui.components.ConfirmationAlert
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The app's dialog, drawn by the root wherever it was asked for: over the
 * screen that asked, dismissed by Back (the Update and Announcement dialogs
 * weren't), a second one standing over the first, and nothing on a closing
 * one answering.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class DialogHostTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val host = DialogHostState()
    private var first by mutableStateOf(false)
    private var second by mutableStateOf(false)
    private var confirmed = 0
    private var reduceDynamicBlur = false

    @Before
    fun setUp() {
        AppUi.install(AndroidAppUiHost)
        // No haze in the test page: the plain card is the same dialog.
        reduceDynamicBlur = AppSettings.reduceDynamicBlur.value
        AppSettings.reduceDynamicBlur.value = true
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDialogHost provides host) {
                    Box(Modifier.fillMaxSize()) {
                        // The screen that asks, deep in the layout.
                        if (first) {
                            ConfirmationAlert(
                                title = FIRST,
                                description = "",
                                confirmLabel = CONFIRM,
                                onConfirm = { confirmed++ },
                                onDismiss = { first = false },
                            )
                        }
                        if (second) {
                            ConfirmationAlert(
                                title = SECOND,
                                description = "",
                                confirmLabel = "Sure",
                                onConfirm = {},
                                onDismiss = { second = false },
                            )
                        }
                        DialogHostOverlay(host, hazeState = null)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @After
    fun restore() {
        AppSettings.reduceDynamicBlur.value = reduceDynamicBlur
    }

    @Test
    fun aDialogAskedForByAScreenIsDrawnByTheRoot() {
        first = true
        compose.waitForIdle()
        compose.onNodeWithText(FIRST).assertExists()
        assertEquals(1, host.requests.size)
    }

    @Test
    fun backDismissesIt() {
        first = true
        compose.waitForIdle()
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        assertFalse(first)
        compose.onNodeWithText(FIRST).assertDoesNotExist()
    }

    @Test
    fun aSecondDialogStandsOverTheFirst() {
        first = true
        second = true
        compose.waitForIdle()
        compose.onNodeWithText(FIRST).assertExists()
        compose.onNodeWithText(SECOND).assertExists()
        // Back takes the newest away first.
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        assertFalse(second)
        compose.onNodeWithText(FIRST).assertExists()
    }

    @Test
    fun anActionTappedWhileTheDialogGoesDoesNothing() {
        first = true
        compose.waitForIdle()
        val confirm = compose.onNodeWithText(CONFIRM).fetchSemanticsNode().boundsInRoot.center
        compose.mainClock.autoAdvance = false
        first = false
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onRoot().performTouchInput { click(confirm) }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(0, confirmed)
    }

    @Test
    fun anActionTappedWhileOpenRuns() {
        first = true
        compose.waitForIdle()
        compose.onNodeWithText(CONFIRM).performClick()
        compose.waitForIdle()
        assertEquals(1, confirmed)
    }

    private companion object {
        const val FIRST = "Clear the cache?"
        const val SECOND = "Sign out?"
        const val CONFIRM = "Clear"
    }
}
