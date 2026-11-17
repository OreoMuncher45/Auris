/*
 * The catalogue card at the top of Settings, driven by touch.
 *
 * The card exists because the switch was hard to find, so the tests check what
 * finding it depends on: it names both services, shows the catalogue in force,
 * opens the picker when tapped, and still turns up for the words people search
 * for.
 *
 * In `ui.apple` and named `*InteractionTest` so the aarch64 exclusion in
 * `app/build.gradle.kts` covers it: Robolectric needs Conscrypt's x86_64
 * native library, so this runs on CI.
 */

package com.music.bitchord.ui.apple

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.ui.screens.MusicCatalogueSettings
import com.music.bitchord.ui.screens.SettingsSearch
import com.music.bitchord.ui.screens.SettingsSheets
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
// The plain Application, as the other interaction tests use: the card needs
// no app state beyond AppSettings' in-memory default.
@Config(
    sdk = [34],
    application = Application::class,
    qualifiers = "w411dp-h891dp-xhdpi",
)
class MusicCatalogueCardInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val sheets = SettingsSheets()

    private fun setCard(query: String = "") {
        compose.setContent {
            MusicCatalogueSettings(search = SettingsSearch(query), sheets = sheets)
        }
    }

    @Test
    fun `the card names both services and the one in force`() {
        setCard()
        compose.onNodeWithText("MUSIC CATALOGUE").assertIsDisplayed()
        compose.onNodeWithText("Spotify or YouTube Music").assertIsDisplayed()
        compose.onNodeWithText("Where Home and Search come from").assertIsDisplayed()
        // The default catalogue, as the value on the right.
        compose.onNodeWithText("YouTube Music").assertIsDisplayed()
    }

    @Test
    fun `tapping the card opens the picker`() {
        setCard()
        assertFalse(sheets.pickingHomeProvider)
        compose.onNodeWithText("Spotify or YouTube Music").performClick()
        compose.waitForIdle()
        assertTrue(sheets.pickingHomeProvider)
    }

    @Test
    fun `a search for a service still finds the card`() {
        setCard(query = "spotify")
        compose.onNodeWithText("Spotify or YouTube Music").assertIsDisplayed()
    }

    @Test
    fun `an unrelated search leaves the card out`() {
        setCard(query = "equalizer")
        compose.onNodeWithText("Spotify or YouTube Music").assertDoesNotExist()
    }

    // Native graphics, so the text is measured with real font metrics: the
    // default legacy mode fakes them, and a test of where a line breaks
    // would be measuring nothing.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun `the title stays whole on a narrow screen at a large font size`() {
        // 320dp wide at twice the font size: the title naming both services
        // wraps rather than losing its end to an ellipsis.
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                Box(Modifier.width(320.dp)) {
                    MusicCatalogueSettings(search = SettingsSearch(""), sheets = sheets)
                }
            }
        }
        val node = compose.onNodeWithText("Spotify or YouTube Music").fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        val layout = layouts.single()
        val lines = (0 until layout.lineCount).map { line ->
            layout.layoutInput.text.text.substring(layout.getLineStart(line), layout.getLineEnd(line, visibleEnd = true))
        }
        assertFalse("title was cut short: $lines", layout.hasVisualOverflow)
        assertTrue("title should wrap at this size: $lines", layout.lineCount > 1)
    }
}
