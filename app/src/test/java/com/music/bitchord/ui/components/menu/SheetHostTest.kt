package com.music.bitchord.ui.components.menu

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.ui.AndroidAppUiHost
import com.music.bitchord.ui.AppUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A screen's sheet, drawn by the root: up while the screen composes its
 * [HostedSheet], down when it stops, and a close from the sheet itself told
 * back to the screen — once.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class SheetHostTest {

    @get:Rule
    val compose = createComposeRule()

    private val host = SheetHostState()
    private var wanted by mutableStateOf(false)
    private var dismissed = 0

    @Before
    fun setUp() {
        AppUi.install(AndroidAppUiHost)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSheetHost provides host) {
                    Box(Modifier.fillMaxSize()) {
                        // The screen, somewhere in the layout.
                        if (wanted) {
                            HostedSheet(onDismiss = {
                                dismissed++
                                wanted = false
                            }) { _ ->
                                Box(Modifier.testTag(BODY).fillMaxWidth().height(200.dp))
                            }
                        }
                        // The root's overlay, last.
                        SheetHostOverlay(host)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun theSheetIsUpWhileTheScreenAsksForIt() {
        wanted = true
        compose.waitForIdle()
        compose.onNodeWithTag(BODY).assertExists()
    }

    @Test
    fun aCloseFromTheSheetIsToldToTheScreenOnce() {
        wanted = true
        compose.waitForIdle()
        compose.onRoot().performTouchInput { click(Offset(centerX, 40f)) }
        compose.waitForIdle()
        assertEquals(1, dismissed)
        assertFalse(wanted)
        compose.onNodeWithTag(BODY).assertDoesNotExist()
    }

    @Test
    fun aScreenThatLetsGoTakesTheSheetDownWithoutBeingToldTwice() {
        wanted = true
        compose.waitForIdle()
        wanted = false
        compose.waitForIdle()
        compose.onNodeWithTag(BODY).assertDoesNotExist()
        assertEquals("the screen let go itself; nobody tells it", 0, dismissed)
    }

    private companion object {
        const val BODY = "body"
    }
}
