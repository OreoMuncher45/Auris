package com.music.bitchord.ui.components.menu

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.ui.AndroidAppUiHost
import com.music.bitchord.ui.AppUi
import com.music.bitchord.ui.components.MenuOrigin
import com.music.bitchord.ui.components.RowMoreButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A list's ⋮ opening its item's menu: the button reports where it is, the
 * menu hangs off it wherever the host sits in the window, it stays up for its
 * exit, and each opening starts afresh.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class AnchoredItemMenuTest {

    @get:Rule
    val compose = createComposeRule()

    private var item by mutableStateOf<String?>(null)
    private var anchor: Rect? = null

    @Before
    fun setUp() {
        AppUi.install(AndroidAppUiHost)
        MenuOrigin.consume()
    }

    /**
     * A page with a ⋮ at [buttonY] down its end edge, and the menu host
     * pushed [hostTop] down the window — as a host under a status bar or a
     * tablet's side pane is.
     */
    private fun setPage(buttonY: Dp, hostTop: Dp = 0.dp, content: @androidx.compose.runtime.Composable (String) -> Unit = { MenuRowFor(it) }) {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    RowMoreButton(
                        onClick = {
                            anchor = MenuOrigin.consume()
                            item = ITEM
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(y = buttonY)
                            .testTag(BUTTON),
                    )
                    Box(Modifier.fillMaxSize().padding(top = hostTop)) {
                        AnchoredItemMenu(
                            item = item,
                            anchorInWindow = { anchor },
                            onDismiss = { item = null },
                        ) { shown -> content(shown) }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @androidx.compose.runtime.Composable
    private fun MenuRowFor(label: String) {
        MenuRow(
            icon = Icons.Rounded.Check,
            label = label,
            ink = Color.White,
            modifier = Modifier.testTag(ROW),
            onClick = {},
        )
    }

    @Test
    fun theMenuHangsBelowTheButtonThatWasTapped() {
        setPage(buttonY = 120.dp)
        compose.onNodeWithTag(BUTTON).performClick()
        compose.waitForIdle()

        val button = compose.onNodeWithTag(BUTTON).getUnclippedBoundsInRoot()
        val row = compose.onNodeWithTag(ROW).getUnclippedBoundsInRoot()
        assertTrue("the menu opens below its button (${row.top} vs ${button.bottom})", row.top >= button.bottom)
        assertTrue("…and right under it, not somewhere down the page", row.top - button.bottom < 40.dp)
    }

    @Test
    fun nearTheBottomTheMenuOpensAboveTheButton() {
        setPage(buttonY = 820.dp)
        compose.onNodeWithTag(BUTTON).performClick()
        compose.waitForIdle()

        val button = compose.onNodeWithTag(BUTTON).getUnclippedBoundsInRoot()
        val row = compose.onNodeWithTag(ROW).getUnclippedBoundsInRoot()
        assertTrue("the menu opens above a button with no room below it", row.bottom <= button.top)
    }

    @Test
    fun aHostLowerInTheWindowStillHangsTheMenuOffTheButton() {
        setPage(buttonY = 300.dp, hostTop = 200.dp)
        compose.onNodeWithTag(BUTTON).performClick()
        compose.waitForIdle()

        val button = compose.onNodeWithTag(BUTTON).getUnclippedBoundsInRoot()
        val row = compose.onNodeWithTag(ROW).getUnclippedBoundsInRoot()
        assertTrue("the host's own offset is taken out of the button's position", row.top >= button.bottom)
        assertTrue(row.top - button.bottom < 40.dp)
    }

    @Test
    fun theMenuKeepsItsItemThroughItsExit() {
        // A closing menu's rows leave the accessibility tree on purpose, so
        // whether they are still composed is asked of the content itself.
        var shownWith: String? = null
        var gone = false
        setPage(buttonY = 120.dp) { label ->
            shownWith = label
            DisposableEffect(Unit) { onDispose { gone = true } }
            MenuRowFor(label)
        }
        compose.onNodeWithTag(BUTTON).performClick()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false

        item = null
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertFalse("still up for its exit after the caller let go", gone)
        assertEquals("…with the item the caller let go of", ITEM, shownWith)

        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertTrue("gone once the exit has run", gone)
    }

    @Test
    fun eachOpeningStartsAfresh() {
        setPage(buttonY = 120.dp) { label ->
            // Stands for a sleep-timer page or a delete awaiting its second tap.
            var taps by remember { mutableIntStateOf(0) }
            MenuRow(
                icon = Icons.Rounded.Check,
                label = "$label $taps",
                ink = Color.White,
                modifier = Modifier.testTag(ROW),
                onClick = { taps++ },
            )
        }
        compose.onNodeWithTag(BUTTON).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(ROW).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("$ITEM 1").assertExists()

        item = null
        compose.waitForIdle()
        compose.onNodeWithTag(BUTTON).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("$ITEM 0").assertExists()
    }

    @Test
    fun aMenuWithNoButtonOpensAtTheTopEnd() {
        setPage(buttonY = 600.dp)
        // Opened by something that reported no button.
        anchor = null
        item = ITEM
        compose.waitForIdle()

        val row = compose.onNodeWithTag(ROW).getUnclippedBoundsInRoot()
        assertTrue("with nowhere to hang from it opens at the top", row.top < 120.dp)
        assertTrue("…against the end edge", row.right > 360.dp)
    }

    private companion object {
        const val ITEM = "Glass Harbour"
        const val BUTTON = "button"
        const val ROW = "row"
    }
}
