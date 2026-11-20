package com.music.bitchord.ui.screens

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.R
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.spotify.SpotifyArtistStats
import com.music.bitchord.ui.AndroidAppUiHost
import com.music.bitchord.ui.AppUi
import com.music.bitchord.ui.theme.ArtworkPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Pins the 3.2 beta artist stats and overflow behavior to the unified shared
 * glass implementation. These are the real composables, not copied test rows.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h1000dp-xxhdpi")
class ArtistPageInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        AppUi.install(AndroidAppUiHost)
    }

    private val palette = ArtworkPalette(
        background = Color.Black,
        wash = Color.DarkGray,
        elevated = Color.DarkGray,
        accent = Color.White,
        onBackground = Color.White,
        onBackgroundVariant = Color.LightGray,
        divider = Color.Gray,
    )

    private val artist = DetailPage(
        browseId = "artist-1",
        title = "Queenadreena",
        subtitle = "Artist",
        thumbnailUrl = null,
        songs = UiState.Success(emptyList()),
        type = BrowseType.ARTIST,
        subscriberCountText = "1.2M subscribers",
        monthlyListenerCount = "3.4M monthly listeners",
    )

    @Test
    fun `beta stats stay stacked under the header and the overflow button opens`() {
        var opened = 0
        compose.setContent {
            MaterialTheme {
                ArtistMetaRow(
                    page = artist,
                    palette = palette,
                    spotifyStats = SpotifyArtistStats(
                        monthlyListeners = 12_345_678,
                        followers = 987_654,
                    ),
                    isAiArtist = true,
                    onOpenMenu = { opened++ },
                )
            }
        }
        compose.waitForIdle()

        val labels = listOf(
            context.getString(R.string.ai_generated),
            context.getString(R.string.spotify_monthly_listeners, "12M"),
            context.getString(R.string.spotify_followers, "988K"),
            context.getString(R.string.subscribers, "1.2M"),
            context.getString(R.string.monthly_listeners, "3.4M"),
        )
        val tops = labels.map { label ->
            compose.onNodeWithText(label).assertIsDisplayed().getUnclippedBoundsInRoot().top
        }
        assertEquals("all five beta chips remain, in their beta order", labels.size, tops.size)
        assertTrue("chips remain stacked, not replaced by one summary", tops.zipWithNext().all { (a, b) -> a < b })

        compose.onNodeWithContentDescription(context.getString(R.string.more)).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `every beta overflow row keeps its own action and shared anchored host`() {
        val albums = HomeShelf("Albums", emptyList())
        val singles = HomeShelf("Singles & EPs", emptyList())
        var expanded by mutableStateOf(false)
        val events = mutableListOf<String>()

        compose.setContent {
            MaterialTheme {
                ArtistOverflowMenu(
                    expanded = expanded,
                    anchorInWindow = { Rect(900f, 100f, 1_000f, 200f) },
                    onDismiss = {
                        events += "dismiss"
                        expanded = false
                    },
                    albumsShelf = albums,
                    singlesShelf = singles,
                    artistIsAi = false,
                    onActiveShelfChange = { shelf ->
                        events += when (shelf) {
                            albums -> "albums"
                            singles -> "singles"
                            else -> "wrong-shelf"
                        }
                    },
                    onToggleAiArtist = { events += "ai" },
                    onHideArtist = { events += "hide" },
                )
            }
        }

        val albumLabel = context.getString(R.string.albums)
        val singlesLabel = context.getString(R.string.singles_eps)
        val aiLabel = context.getString(R.string.mark_as_ai)
        val hideLabel = context.getString(R.string.hide_artist)

        expanded = true
        compose.waitForIdle()
        val orderedLabels = listOf(albumLabel, singlesLabel, aiLabel, hideLabel)
        val tops = orderedLabels.map { label ->
            compose.onNodeWithText(label).assertIsDisplayed().getUnclippedBoundsInRoot().top
        }
        assertTrue("beta row order is Albums, Singles & EPs, AI mark, Hide", tops.zipWithNext().all { (a, b) -> a < b })

        fun tap(label: String, expectedAction: String) {
            expanded = true
            compose.waitForIdle()
            compose.onNodeWithText(label).performClick()
            compose.waitForIdle()
            assertEquals(listOf("dismiss", expectedAction), events)
            events.clear()
        }

        tap(albumLabel, "albums")
        tap(singlesLabel, "singles")
        tap(aiLabel, "ai")
        tap(hideLabel, "hide")
    }

    @Test
    fun `an artist already marked as AI offers to remove that mark`() {
        compose.setContent {
            MaterialTheme {
                ArtistOverflowMenu(
                    expanded = true,
                    anchorInWindow = { null },
                    onDismiss = {},
                    albumsShelf = null,
                    singlesShelf = null,
                    artistIsAi = true,
                    onActiveShelfChange = {},
                    onToggleAiArtist = {},
                    onHideArtist = null,
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText(context.getString(R.string.remove_ai_mark)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.mark_as_ai)).assertDoesNotExist()
    }
}
