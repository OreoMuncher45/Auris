package com.music.bitchord

import android.app.Application
import android.content.ComponentName
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.auth.AuthStore
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.PlaybackService
import com.music.bitchord.ui.AndroidAppUiHost
import com.music.bitchord.ui.AppUi
import com.music.bitchord.ui.MainViewModel
import com.music.bitchord.ui.components.backdrop.backdrops.rememberLayerBackdrop
import com.music.bitchord.ui.player.AndroidPlayerHost
import com.music.bitchord.ui.player.PlayerPlatform
import com.music.bitchord.ui.theme.BitChordTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Ticket 13: Settings opened from an album or playlist page has to stay open.
 *
 * Renders the real [BitChordApp] under Robolectric with a playlist open, taps
 * the account button (signed out, it opens Settings directly; signed in, the
 * account sheet's Settings row runs the same `showSettings = true`), and then
 * has the open page publish an update the way [MainViewModel] does all the
 * time behind a page that is on screen: a continuation page of tracks, a
 * like status, a download state, a Spotify row matched. Each of those is a new
 * [DetailPage] copy in the stack, not a new page.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsOverDetailTest {

    @get:Rule
    val compose = createComposeRule()

    @Before fun install() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        PlayerPlatform.install(AndroidPlayerHost(app))
        AppUi.install(AndroidAppUiHost)
        AppSettings.init(app, AuthStore(app))
        Shadows.shadowOf(app).declareComponentUnbindable(ComponentName(app, PlaybackService::class.java))
    }

    private fun song(i: Int) = Song(videoId = "v$i", title = "Track $i", artist = "Artist", thumbnailUrl = null)

    private fun page(id: String, title: String, songs: Int) = DetailPage(
        browseId = id,
        title = title,
        subtitle = "Someone",
        thumbnailUrl = null,
        songs = UiState.Success(List(songs) { song(it) }),
        type = BrowseType.PLAYLIST,
    )

    @Suppress("UNCHECKED_CAST")
    private fun MainViewModel.stack(): MutableStateFlow<List<DetailPage>> =
        MainViewModel::class.java.getDeclaredField("_detailStack")
            .apply { isAccessible = true }
            .get(this) as MutableStateFlow<List<DetailPage>>

    /** [n] frames, each settled: effects launched by one run before the next. */
    private fun frames(n: Int) {
        repeat(n) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
    }

    /** The app with one playlist open; returns its view model. */
    private fun openPlaylist(): MainViewModel {
        var vm: MainViewModel? = null
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val viewModel: MainViewModel = viewModel()
            vm = viewModel
            BitChordTheme(darkTheme = true) {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.fillMaxSize()) {
                    BitChordApp(
                        darkTheme = true,
                        windowWidth = 411.dp,
                        windowHeight = 891.dp,
                        appBackdrop = backdrop,
                        viewModel = viewModel,
                    )
                }
            }
        }
        frames(1)
        val viewModel = vm!!
        viewModel.stack().value = listOf(page(PLAYLIST, "A playlist", 20))
        frames(60)
        return viewModel
    }

    private fun tapSettings() {
        compose.onNodeWithContentDescription(SWITCH_ACCOUNT).performClick()
    }

    private fun settingsShown() =
        compose.onAllNodesWithText(SETTINGS_SEARCH_HINT).fetchSemanticsNodes().isNotEmpty()

    /** The open page publishes in place: the same page, more tracks. */
    private fun MainViewModel.publish(songs: Int) {
        stack().value = stack().value.map { it.copy(songs = UiState.Success(List(songs) { song(it) })) }
    }

    @Test fun `settings stays open when the page under it publishes after the push`() {
        val vm = openPlaylist()
        tapSettings()
        frames(60)
        assertEquals("Settings opened over the playlist", true, settingsShown())
        vm.publish(40)
        frames(60)
        assertEquals("Settings closed by the playlist's own update", true, settingsShown())
        compose.onAllNodesWithText(SETTINGS_SEARCH_HINT).onFirst().assertIsDisplayed()
    }

    @Test fun `settings stays open when the page publishes during the push`() {
        val vm = openPlaylist()
        tapSettings()
        // Mid-slide: the page's next tracks land while Settings comes in.
        frames(3)
        vm.publish(40)
        frames(60)
        assertEquals("Settings swallowed by an update during its push", true, settingsShown())
    }

    @Test fun `a page pushed over settings still comes up on top`() {
        val vm = openPlaylist()
        tapSettings()
        frames(60)
        vm.stack().value = vm.stack().value + page("VLother", "Another playlist", 5)
        frames(60)
        assertEquals("Settings left over a pushed page", false, settingsShown())
        compose.onAllNodesWithText("Another playlist").onFirst().assertIsDisplayed()
    }

    @Test fun `back from settings returns to the playlist`() {
        val vm = openPlaylist()
        tapSettings()
        frames(30)
        vm.publish(40)
        frames(30)
        compose.onNodeWithContentDescription(BACK).performClick()
        frames(60)
        assertEquals(false, settingsShown())
        assertEquals(1, vm.stack().value.size)
        compose.onAllNodesWithText("A playlist").onFirst().assertIsDisplayed()
    }

    @Test fun `settings opens over listening history`() {
        openPlaylist()
        // Back to the Library tab, then its history page.
        compose.onNodeWithContentDescription(BACK).performClick()
        frames(30)
        compose.onAllNodesWithText(LIBRARY).onFirst().performClick()
        frames(30)
        compose.onNodeWithContentDescription(LISTENING_HISTORY).performClick()
        frames(30)
        tapSettings()
        frames(60)
        assertEquals("Settings did not open over History", true, settingsShown())
        compose.onNodeWithContentDescription(BACK).performClick()
        frames(60)
        assertEquals(false, settingsShown())
        compose.onNodeWithContentDescription(LISTENING_HISTORY).assertDoesNotExist()
    }

    private companion object {
        const val PLAYLIST = "VLPLtest"
        const val SWITCH_ACCOUNT = "Switch account"
        const val SETTINGS_SEARCH_HINT = "Search settings"
        const val BACK = "Back"
        const val LIBRARY = "Library"
        const val LISTENING_HISTORY = "Listening history"
    }
}
