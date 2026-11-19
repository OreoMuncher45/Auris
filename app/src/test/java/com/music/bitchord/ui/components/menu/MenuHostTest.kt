package com.music.bitchord.ui.components.menu

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
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

    /** A button-sized rectangle a third of the way down the end edge, in window pixels. */
    private val button = Rect(left = 1_000f, top = 800f, right = 1_100f, bottom = 900f)

    private var chosen = 0
    private var rowY: Float? = null

    @Before
    fun setUp() {
        AppUi.install(AndroidAppUiHost)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalMenuHost provides host) {
                    Box(Modifier.fillMaxSize()) { MenuHostOverlay(host) }
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
        // Up for its exit, and not jumped to the top end now that the
        // request it hung from is gone.
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

    private companion object {
        const val ROW = "row"
        const val OTHER = "other"
    }
}
