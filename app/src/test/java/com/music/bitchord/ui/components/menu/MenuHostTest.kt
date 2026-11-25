package com.music.bitchord.ui.components.menu

import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.DisposableEffect
import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.ui.AndroidAppUiHost
import com.music.bitchord.ui.AppUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The menu a screen asks for through [LocalMenuHost] — the shared screens'
 * only way to the app's anchored menu: it opens off the anchor it was given,
 * a row's `close` shuts it, and it stays where it hung while it closes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class MenuHostTest {

    @get:Rule
    val compose = createComposeRule()

    private val host = MenuHostState()
    private val playerHostEnabled = mutableStateOf(false)

    /** A button-sized rectangle a third of the way down the end edge, in window pixels. */
    private val button = Rect(left = 1_000f, top = 800f, right = 1_100f, bottom = 900f)

    private var chosen = 0
    private var rowY: Float? = null
    private var rowGone = false

    @Before
    fun setUp() {
        AppUi.install(AndroidAppUiHost)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalMenuHost provides host) {
                    Box(Modifier.fillMaxSize()) {
                        MenuHostOverlay(host, fromPlayer = false)
                        if (playerHostEnabled.value) MenuHostOverlay(host, fromPlayer = true)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun openChoices() {
        host.open(button) { close ->
            MenuChoiceRow(
                label = "Title A-Z",
                selected = true,
                ink = Color.White,
                accent = Color.Red,
                selectedDescription = null,
                modifier = Modifier
                    .testTag(ROW)
                    .onGloballyPositioned { rowY = it.positionInRoot().y },
            ) {
                chosen++
                close()
            }
            DisposableEffect(Unit) { onDispose { rowGone = true } }
        }
        compose.waitForIdle()
    }

    @Test
    fun theMenuOpensOffTheAnchorItWasGiven() {
        openChoices()
        val row = compose.onNodeWithTag(ROW).getUnclippedBoundsInRoot()
        val buttonBottom = with(compose.density) { button.bottom.toDp() }
        assertTrue("the menu opens below its anchor", row.top >= buttonBottom)
        assertTrue("…right under it", row.top - buttonBottom < 40.dp)
    }

    @Test
    fun aCenteredCardIsCentredInTheWindow() {
        host.open(button, centered = true) { close ->
            MenuRow(icon = null, label = "Quality", ink = Color.White, modifier = Modifier.testTag(ROW)) { close() }
        }
        compose.waitForIdle()

        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val row = compose.onNodeWithTag(ROW).getUnclippedBoundsInRoot()
        // Placement is integer-pixel; allow the resulting fraction of a dp.
        assertEquals((root.left + root.right).value / 2f, (row.left + row.right).value / 2f, 0.51f)
    }

    @Test
    fun aPlayerRequestIsRenderedOnlyByThePlayerHost() {
        host.open(button, fromPlayer = true) { close ->
            MenuRow(icon = null, label = "Quality", ink = Color.White, modifier = Modifier.testTag(ROW)) { close() }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(ROW).assertDoesNotExist()

        playerHostEnabled.value = true
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
        compose.onNodeWithTag(ROW).assertIsDisplayed()
    }

    @Test
    fun aRowsCloseShutsTheMenu() {
        openChoices()
        compose.onNodeWithTag(ROW).performClick()
        compose.waitForIdle()
        assertEquals(1, chosen)
        assertNull("a choice closes the menu", host.request)
        compose.onNodeWithTag(ROW).assertDoesNotExist()
    }

    @Test
    fun aClosingMenuStaysWhereItHung() {
        openChoices()
        val open = rowY
        assertNotNull(open)

        compose.mainClock.autoAdvance = false
        host.close()
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        // Up for its exit — the row still composed, so its last position is
        // a live one — and not jumped to the top end now that the request it
        // hung from is gone.
        assertTrue("the row is still there for the exit", !rowGone)
        assertEquals(open!!, rowY!!, 1f)

        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    @Test
    fun aSecondRequestReplacesTheFirst() {
        openChoices()
        host.open(null) { close ->
            MenuRow(icon = null, label = "Other", ink = Color.White, modifier = Modifier.testTag(OTHER)) { close() }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(OTHER).assertExists()
        compose.onNodeWithTag(ROW).assertDoesNotExist()
    }

    @Test
    fun aSurfaceGoingTakesOnlyItsOwnMenuWithIt() {
        val badge: @androidx.compose.runtime.Composable androidx.compose.foundation.layout.ColumnScope.(() -> Unit) -> Unit =
            { close -> MenuRow(icon = null, label = "Card", ink = Color.White, modifier = Modifier.testTag(ROW)) { close() } }
        host.open(button, content = badge)
        compose.waitForIdle()
        compose.onNodeWithTag(ROW).assertExists()
        host.closeIfShowing(badge)
        compose.waitForIdle()
        assertNull("the badge's own menu goes with it", host.request)

        // A newer menu, opened elsewhere, isn't the badge's to close.
        host.open(button, content = badge)
        openChoices()
        host.closeIfShowing(badge)
        compose.waitForIdle()
        assertNotNull("a newer menu stays up", host.request)
    }

    @Test
    fun aRowThatOpensAnotherMenuThenClosesItsOwnLeavesTheNewOneUp() {
        host.open(button) { close ->
            MenuRow(icon = null, label = "More", ink = Color.White, modifier = Modifier.testTag(ROW)) {
                host.open(null) { innerClose ->
                    MenuRow(icon = null, label = "Other", ink = Color.White, modifier = Modifier.testTag(OTHER)) { innerClose() }
                }
                close()
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(ROW).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(OTHER).assertExists()
    }

    @Test
    fun aMenuReplacingAScrolledOneStartsAtItsOwnTop() {
        fun rows(prefix: String) = host.open(button) { close ->
            repeat(40) { i ->
                MenuRow(icon = null, label = "$prefix $i", ink = Color.White, modifier = Modifier.testTag("$prefix$i")) { close() }
            }
        }
        rows("a")
        compose.waitForIdle()
        // Scrolled well down the first menu: its top row is out of view.
        compose.onNodeWithTag("a20").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithTag("a0").assertIsNotDisplayed()

        rows("b")
        compose.waitForIdle()
        compose.onNodeWithTag("b0").assertIsDisplayed()
    }

    private companion object {
        const val ROW = "row"
        const val OTHER = "other"
    }
}
