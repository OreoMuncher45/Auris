package com.music.bitchord.ui.screens

import android.app.Application
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.auth.AuthStore
import com.music.bitchord.data.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Ticket 15 (GitHub #61): a settings page opens at the place it was left,
 * from its very first frame. It used to start at the top and scroll there a
 * frame later, so the top of the index — the Discord/Reddit card — flashed
 * every time the listener came back to it.
 *
 * Records the scroll offset every time the page is placed, so a page that
 * shows the top first and then jumps shows up as two placements.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsScrollRestoreTest {

    @get:Rule
    val compose = createComposeRule()

    private val placed = mutableListOf<Int>()
    private lateinit var scroll: ScrollState

    @Before fun install() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        AppSettings.init(app, AuthStore(app))
    }

    private fun frames(n: Int) {
        repeat(n) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
    }

    /** A settings-like page, far taller than the screen. */
    private fun render(page: () -> String, query: () -> String = { "" }) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val state = rememberSettingsScroll(page(), query())
            scroll = state
            Column(
                Modifier
                    .height(600.dp)
                    .layout { measurable, constraints ->
                        val p = measurable.measure(constraints)
                        layout(p.width, p.height) {
                            placed += state.value
                            p.place(0, 0)
                        }
                    }
                    .verticalScroll(state),
            ) {
                repeat(ROWS) { Box(Modifier.fillMaxWidth().height(ROW_HEIGHT)) }
            }
        }
    }

    @Test fun `the index opens at its saved place on the first frame`() {
        AppSettings.saveSettingsScrollOffset("index", 1500)
        render(page = { "index" })
        frames(30)
        assertEquals("placed at the top first, then scrolled", listOf(1500), placed)
    }

    @Test fun `back from a category opens the index at its place on the first frame`() {
        AppSettings.saveSettingsScrollOffset("index", 1500)
        AppSettings.saveSettingsScrollOffset("playback", 0)
        var page by mutableStateOf("index")
        render(page = { page })
        frames(30)
        // Into a category, scrolled a little, and back out.
        page = "playback"
        frames(30)
        placed.clear()
        page = "index"
        frames(30)
        assertEquals("the index flashed its top on the way back", listOf(1500), placed)
    }

    @Test fun `an offset past the end lands on the bottom`() {
        AppSettings.saveSettingsScrollOffset("index", Int.MAX_VALUE / 2)
        render(page = { "index" })
        frames(30)
        // The bottom: as far as the page scrolls, placed there from the start.
        assertEquals(true, scroll.maxValue in 1 until Int.MAX_VALUE / 2)
        assertEquals(listOf(scroll.maxValue), placed.distinct())
    }

    @Test fun `a page opened mid-search starts at the top of the results`() {
        AppSettings.saveSettingsScrollOffset("index", 1500)
        render(page = { "index" }, query = { "dis" })
        frames(30)
        assertEquals(listOf(0), placed.distinct())
    }

    @Test fun `a page never scrolled opens at the top`() {
        AppSettings.saveSettingsScrollOffset("index", 0)
        render(page = { "index" })
        frames(30)
        assertEquals(listOf(0), placed.distinct())
    }

    private companion object {
        const val ROWS = 200
        val ROW_HEIGHT = 50.dp
    }
}
