package com.music.bitchord

import android.app.Application
import android.content.ComponentName
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.music.bitchord.ui.components.LocalAppBackdrop
import com.music.bitchord.ui.components.LocalLiquidGlassEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
 * Ticket 13 follow-up: with liquid glass's folding bar, a page opened over the
 * Search tab is not the Search page. The bar's search form (the field, held
 * unfolded, with its own back) belongs to the Search page only: kept up over
 * an album opened from the results, the bar never folded there, and back
 * left Search altogether instead of returning to the results.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class SearchFoldOverPageTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun install() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        PlayerPlatform.install(AndroidPlayerHost(app))
        AppUi.install(AndroidAppUiHost)
        AppSettings.init(app, AuthStore(app))
        Shadows.shadowOf(app).declareComponentUnbindable(ComponentName(app, PlaybackService::class.java))
    }

    @Suppress("UNCHECKED_CAST")
    private fun MainViewModel.stack(): MutableStateFlow<List<DetailPage>> =
        MainViewModel::class.java.getDeclaredField("_detailStack")
            .apply { isAccessible = true }
            .get(this) as MutableStateFlow<List<DetailPage>>

    private fun frames(n: Int) {
        repeat(n) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
    }

    private fun searchFieldShown() =
        compose.onAllNodesWithText(SEARCH_HINT).fetchSemanticsNodes().isNotEmpty()

    @Test fun `an album opened from search drops the search form and back returns to search`() {
        var vm: MainViewModel? = null
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val viewModel: MainViewModel = viewModel()
            vm = viewModel
            BitChordTheme(darkTheme = true) {
                val backdrop = rememberLayerBackdrop()
                // Liquid glass on, as MainActivity provides it: its folding
                // bar is the default bar there.
                CompositionLocalProvider(
                    LocalLiquidGlassEnabled provides true,
                    LocalAppBackdrop provides backdrop,
                ) {
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
        }
        frames(30)
        compose.onAllNodesWithContentDescription(SEARCH).onFirst().performClick()
        frames(30)
        assertEquals("the Search page shows its field", true, searchFieldShown())
        // Into keyboard mode, as tapping the field does.
        compose.onAllNodesWithText(SEARCH_HINT).onFirst().performClick()
        frames(30)

        val viewModel = vm!!
        viewModel.stack().value = listOf(
            DetailPage(
                browseId = "VLPLtest",
                title = "A playlist",
                subtitle = "Someone",
                thumbnailUrl = null,
                songs = UiState.Success(List(20) { Song(videoId = "v$it", title = "Track $it", artist = "A", thumbnailUrl = null) }),
                type = BrowseType.PLAYLIST,
            ),
        )
        frames(60)
        assertEquals("the search form stayed up over the playlist", false, searchFieldShown())

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        frames(60)
        assertEquals("back left the playlist open", 0, viewModel.stack().value.size)
        assertEquals("back did not return to the Search page", true, searchFieldShown())
    }

    private companion object {
        const val SEARCH = "Search"
        const val SEARCH_HINT = "Artists, Songs, Lyrics and More"
    }
}
