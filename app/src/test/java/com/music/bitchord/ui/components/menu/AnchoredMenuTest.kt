package com.music.bitchord.ui.components.menu

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composition
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.ObservableComposition
import androidx.compose.runtime.tooling.setObserver
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.ui.AndroidAppUiHost
import com.music.bitchord.ui.AppUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The anchored menu's behaviour that placement arithmetic alone can't show:
 * it follows a moving button without recomposing, it stays up for its exit
 * spring, and a row tapped during that exit does nothing.
 */
@OptIn(ExperimentalComposeRuntimeApi::class, ComposeToolingApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class AnchoredMenuTest {

    @get:Rule
    val compose = createComposeRule()

    private var expanded by mutableStateOf(true)
    private var anchor by mutableStateOf(IntRect(900, 200, 1000, 300))
    private var taps = 0
    private var composition: Composition? = null

    @Before
    fun setUp() {
        AppUi.install(AndroidAppUiHost)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            composition = currentComposer.composition
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    val presence = rememberMenuPresence(expanded)
                    if (presence.isShowing) {
                        AnchoredMenu(presence = presence, anchor = { anchor }, onDismiss = { expanded = false }) {
                            Text(
                                "Row",
                                Modifier
                                    .testTag(ROW)
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .clickable { taps++ },
                            )
                        }
                    }
                }
            }
        }
        settle()
    }

    private fun frame() {
        // Writes made from the test thread reach composition only once sent;
        // with the clock paused nothing sends them for us.
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun settle() = repeat(60) { frame() }

    @Test
    fun aMovingButtonMovesTheMenuWithoutRecomposingAnything() {
        val counter = ScopeCounter()
        val handle = composition!!.setObserver(counter)
        val before = compose.onNodeWithTag(ROW).getUnclippedBoundsInRoot().top

        repeat(10) { i ->
            anchor = IntRect(900, 220 + i * 20, 1000, 320 + i * 20)
            frame()
        }

        val after = compose.onNodeWithTag(ROW).getUnclippedBoundsInRoot().top
        handle?.dispose()
        assertNotEquals("the menu follows its button", before, after)
        assertEquals("no scope recomposed while the button moved", 0, counter.enters)
    }

    @Test
    fun theMenuStaysUpForItsExitAndThenLeaves() {
        expanded = false
        frame()
        compose.onNodeWithTag(ROW).assertExists()
        settle()
        compose.onNodeWithTag(ROW).assertDoesNotExist()
    }

    @Test
    fun aRowTappedWhileTheMenuClosesDoesNothing() {
        compose.onNodeWithTag(ROW).assertIsDisplayed()
        expanded = false
        frame()
        compose.onNodeWithTag(ROW).performClick()
        frame()
        assertEquals(0, taps)
    }

    @Test
    fun aRowTappedWhileTheMenuIsOpenRuns() {
        compose.onNodeWithTag(ROW).performClick()
        frame()
        assertEquals(1, taps)
    }

    private class ScopeCounter : CompositionObserver {
        var enters = 0
        override fun onBeginComposition(composition: ObservableComposition) {}
        override fun onScopeEnter(scope: RecomposeScope) { enters++ }
        override fun onReadInScope(scope: RecomposeScope, value: Any) {}
        override fun onScopeExit(scope: RecomposeScope) {}
        override fun onEndComposition(composition: ObservableComposition) {}
        override fun onScopeInvalidated(scope: RecomposeScope, value: Any?) {}
        override fun onScopeDisposed(scope: RecomposeScope) {}
    }

    private companion object {
        const val ROW = "row"
    }
}
