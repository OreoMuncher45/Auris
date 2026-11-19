package com.music.bitchord.ui.player

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.ui.AndroidAppUiHost
import com.music.bitchord.ui.AppUi
import dev.chrisbanes.haze.HazeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The player's drawer, drawn by the app's drawer host: it plays its exit after
 * its caller lets go (it used to vanish with it), it leaves nothing over the
 * page afterwards, and its slide recomposes nothing.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class PlayerDrawerTest {

    @get:Rule
    val compose = createComposeRule()

    private val host = DrawerHostState()
    private var open by mutableStateOf(false)
    private var gone = false
    private var bodyCompositions = 0
    private var pageTaps = 0

    @Before
    fun setUp() {
        AppUi.install(AndroidAppUiHost)
        PlayerPlatform.install(AndroidPlayerHost(ApplicationProvider.getApplicationContext<Application>()))
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDrawerHost provides host) {
                    Box(Modifier.fillMaxSize().clickable { pageTaps++ }) {
                        if (open) {
                            PlayerDrawer(hazeState = HazeState(), title = "Output", onDismiss = { open = false }) {
                                SideEffect { bodyCompositions++ }
                                DisposableEffect(Unit) {
                                    gone = false
                                    onDispose { gone = true }
                                }
                                Box(Modifier.fillMaxWidth().height(200.dp))
                            }
                        }
                        DrawerHostOverlay(host)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun itPlaysItsExitAfterItsCallerLetsGo() {
        open = true
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        open = false
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertFalse("still up for its way down", gone)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertTrue("gone once it is down", gone)
    }

    @Test
    fun onceDownItLeavesNothingOverThePage() {
        open = true
        compose.waitForIdle()
        open = false
        compose.waitForIdle()
        compose.onRoot().performTouchInput { click(Offset(centerX, 60f)) }
        compose.waitForIdle()
        assertEquals("the page answers again", 1, pageTaps)
    }

    @Test
    fun itsSlideRecomposesNothing() {
        open = true
        compose.waitForIdle()
        val settled = bodyCompositions
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals(settled, bodyCompositions)
        assertTrue(settled in 1..2)
    }
}
