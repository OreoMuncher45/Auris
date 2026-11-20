package com.music.bitchord.ui.components.menu

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.ui.AndroidAppUiHost
import com.music.bitchord.ui.AppUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The app's in-place sheet: it opens and goes on Back, the scrim, a long
 * drag or a flick; it springs back from a short drag; it stays up for its
 * exit with nothing on it answering; and its entrance recomposes nothing.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class AppSheetTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var item by mutableStateOf<String?>(null)
    private var taps = 0
    private var contentCompositions = 0

    @Before
    fun setUp() {
        AppUi.install(AndroidAppUiHost)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    AppSheet(item = item, onDismiss = { item = null }) { label ->
                        SideEffect { contentCompositions++ }
                        MenuRow(
                            icon = Icons.Rounded.Check,
                            label = label,
                            ink = Color.Black,
                            density = MenuDensity.Sheet,
                            modifier = Modifier.testTag(ROW).fillMaxWidth().height(300.dp),
                        ) { taps++ }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun open() {
        item = ITEM
        compose.waitForIdle()
    }

    @Test
    fun itOpensWithItsContentAndBackPutsItAway() {
        open()
        compose.onNodeWithTag(ROW).assertExists()
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        assertNull(item)
        compose.onNodeWithTag(ROW).assertDoesNotExist()
    }

    @Test
    fun aTapOnThePageBehindPutsItAway() {
        open()
        compose.onRoot().performTouchInput { click(Offset(centerX, 60f)) }
        compose.waitForIdle()
        assertNull(item)
    }

    @Test
    fun aLongDragPutsItAwayAndAShortOneSpringsBack() {
        open()
        val top = compose.onNodeWithTag(ROW).getUnclippedBoundsInRoot().top
        // A short, slow pull: a few percent of the sheet, nothing like a flick.
        compose.onNodeWithTag(ROW).performTouchInput {
            down(center)
            repeat(10) {
                advanceEventTime(40)
                moveBy(Offset(0f, 4f))
            }
            advanceEventTime(200)
            up()
        }
        compose.waitForIdle()
        assertEquals("a short pull springs back", ITEM, item)
        assertEquals(top.value, compose.onNodeWithTag(ROW).getUnclippedBoundsInRoot().top.value, 1f)

        compose.onNodeWithTag(ROW).performTouchInput { swipeDown(startY = 10f, endY = bottom, durationMillis = 600) }
        compose.waitForIdle()
        assertNull("a long pull puts it away", item)
    }

    @Test
    fun aShortFastFlickPutsItAway() {
        open()
        // Well under a quarter of the sheet, but fast: the velocity branch.
        compose.onNodeWithTag(ROW).performTouchInput {
            swipeDown(startY = 10f, endY = 10f + height * 0.12f, durationMillis = 40)
        }
        compose.waitForIdle()
        assertNull("a flick puts it away however short", item)
    }

    @Test
    fun aRowTappedWhileTheSheetGoesDoesNothing() {
        open()
        val row = compose.onNodeWithTag(ROW).getUnclippedBoundsInRoot()
        val at = with(compose.density) {
            Offset(((row.left + row.right) / 2).toPx(), ((row.top + row.bottom) / 2).toPx())
        }
        compose.mainClock.autoAdvance = false
        item = null
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onRoot().performTouchInput { click(at) }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(0, taps)
    }

    @Test
    fun aRowOnTheSheetAnswersItsTap() {
        open()
        compose.onNodeWithTag(ROW).performClick()
        compose.waitForIdle()
        assertEquals(1, taps)
        assertEquals("a tap on a row doesn't put the sheet away", ITEM, item)
    }

    @Test
    fun theEntranceRecomposesNothing() {
        open()
        assertTrue("composed once for the opening", contentCompositions in 1..2)
        val settled = contentCompositions
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals("no frame of the slide recomposed the content", settled, contentCompositions)
    }

    private companion object {
        const val ITEM = "Add to playlist"
        const val ROW = "row"
    }
}
