package com.music.bitchord

import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.UiState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [rootPageKey]: which page the root shows when several are set at once.
 * A detail page has to win over the pages it can be opened from, or opening
 * a playlist from one of them leaves that page on screen.
 */
class RootPageKeyTest {
    private val playlist = DetailPage(
        browseId = "VLplaylist",
        title = "A playlist",
        subtitle = "",
        thumbnailUrl = null,
        songs = UiState.Loading,
    )

    private fun key(
        nav: RootNav = RootNav(),
        sheets: RootSheets = RootSheets(),
        showContinueListening: Boolean = false,
        detail: DetailPage? = null,
        stackSize: Int = if (detail == null) 0 else 1,
    ) = rootPageKey(nav, sheets, showContinueListening, detail, stackSize)

    @Test fun `the selected tab when nothing is open over it`() {
        assertEquals("tab:0", key())
        assertEquals("tab:3", key(nav = RootNav().apply { selectedTab = 3 }))
    }

    @Test fun `Genres on its own`() {
        assertEquals("genres", key(nav = RootNav().apply { showGenres = true }))
    }

    @Test fun `a playlist opened from Genres is shown over Genres`() {
        val nav = RootNav().apply { showGenres = true }
        assertEquals("VLplaylist", key(nav = nav, detail = playlist))
    }

    @Test fun `a page opened from the Library grid is shown over the grid`() {
        val sheets = RootSheets().apply {
            libraryShowAll = HomeShelf(title = "Shelf", items = emptyList())
        }
        assertEquals("library_show_all", key(sheets = sheets))
        assertEquals("VLplaylist", key(sheets = sheets, detail = playlist))
    }

    @Test fun `Settings still wins over a detail page and over Genres`() {
        val nav = RootNav().apply {
            showSettings = true
            showGenres = true
        }
        assertEquals("settings", key(nav = nav, detail = playlist))
    }

    @Test fun `Settings wins over every page the account button is on`() {
        // Ticket 13: each of these drew the account button, and each one
        // outranked Settings, so tapping it changed nothing until back.
        val nav = RootNav().apply {
            showSettings = true
            showReplay = true
        }
        val pages: List<Pair<String, RootSheets.() -> Unit>> = listOf(
            "history" to { showHistory = true },
            "flow" to { showFlow = true },
            "quick picks" to { showQuickPicks = true },
            "the Library grid" to { libraryShowAll = HomeShelf(title = "Shelf", items = emptyList()) },
            "Spotify from Home" to { showSpotify = true },
        )
        pages.forEach { (name, open) ->
            assertEquals("Settings over $name", "settings", key(nav = nav, sheets = RootSheets().apply(open)))
        }
        assertEquals("settings", key(nav = nav, showContinueListening = true))
        assertEquals("settings", key(nav = nav))
        assertEquals("replay", key(nav = RootNav().apply { showReplay = true }, detail = playlist))
    }

    @Test fun `the pages Settings opens win over Settings, over a release page too`() {
        val nav = RootNav().apply {
            showSettings = true
            showAccountScrobbling = true
        }
        val spotify = RootSheets().apply {
            showSpotify = true
            spotifyFromSettings = true
        }
        assertEquals("spotify", key(nav = nav, sheets = spotify, detail = playlist))
        assertEquals("account_scrobbling", key(nav = nav, detail = playlist))
        val replay = RootNav().apply {
            showSettings = true
            showReplay = true
            settingsSubScreen = "replay"
        }
        assertEquals("replay", key(nav = replay, detail = playlist))
    }

    @Test fun `Spotify from Home stays under its own playlists`() {
        val sheets = RootSheets().apply { showSpotify = true }
        assertEquals("spotify", key(sheets = sheets))
        assertEquals("VLplaylist", key(sheets = sheets, detail = playlist))
    }

    @Test fun `closing Settings takes its pages with it`() {
        // A page pushed from the player while the equalizer was open used to
        // stay hidden under the equalizer once Settings itself had closed.
        val sheets = RootSheets().apply {
            showSpotify = true
            spotifyFromSettings = true
            showDiscord = true
        }
        val nav = RootNav().apply {
            showSettings = true
            settingsCategory = "appearance"
            showAccountScrobbling = true
            showEqualizer = true
            showSources = true
            showListenTogether = true
            settingsSubScreen = "replay"
            showReplay = true
        }
        nav.closeSettings(sheets)
        assertEquals("VLplaylist", key(nav = nav, sheets = sheets, detail = playlist))
        assertEquals(null, nav.settingsCategory)
        assertEquals(null, nav.settingsSubScreen)
        assertEquals(false, sheets.showSpotify)
        assertEquals(false, sheets.showDiscord)
    }

    @Test fun `closing Settings leaves Spotify from Home open`() {
        val sheets = RootSheets().apply { showSpotify = true }
        RootNav().apply { showSettings = true }.closeSettings(sheets)
        assertEquals("spotify", key(sheets = sheets))
    }

    @Test fun `a page pushed over Settings on History comes up over History`() {
        // Settings over History, then a release page pushed: Settings closes
        // and the release page, not History, is on top; back returns to it.
        val nav = RootNav().apply { showSettings = true }
        val sheets = RootSheets().apply { showHistory = true }
        nav.closeSettings(sheets)
        assertEquals("VLplaylist", key(nav = nav, sheets = sheets, detail = playlist))
        assertEquals("history", key(nav = nav, sheets = sheets))
        listOf<RootSheets.() -> Unit>({ showFlow = true }, { showQuickPicks = true }).forEach { open ->
            assertEquals("VLplaylist", key(sheets = RootSheets().apply(open), detail = playlist))
        }
        assertEquals("VLplaylist", key(showContinueListening = true, detail = playlist))
    }

    @Test fun `a page pushed after Replay comes up over it, one Replay was opened over stays under`() {
        var depth = 1
        val nav = RootNav().apply { stackDepth = { depth } }
        // Replay from the player menu, over an open album.
        nav.showReplay = true
        assertEquals("replay", key(nav = nav, detail = playlist, stackSize = 1))
        // "Go to album" from the player while Replay is up.
        depth = 2
        assertEquals("VLplaylist", key(nav = nav, detail = playlist, stackSize = 2))
        // Back pops it: Replay again.
        assertEquals("replay", key(nav = nav, detail = playlist, stackSize = 1))
    }

    @Test fun `the audiobook pages and an Apple podcast give way to a page pushed after them`() {
        val nav = RootNav().apply { stackDepth = { 0 } }
        val sheets = RootSheets().apply { stackDepth = { 0 } }
        nav.showAudiobooks = true
        assertEquals("audiobooks", key(nav = nav))
        assertEquals("VLplaylist", key(nav = nav, detail = playlist))
        nav.showAudiobooks = false
        nav.itunesPodcastId = "123"
        assertEquals("itunespodcast", key(nav = nav))
        assertEquals("VLplaylist", key(nav = nav, detail = playlist))
        nav.itunesPodcastId = null
        sheets.openAudiobook = com.music.bitchord.data.audiobook.Audiobook(id = "b", title = "A book", author = "", coverUrl = null, language = null, totalSeconds = null, sectionCount = 1, genres = emptyList())
        assertEquals("audiobook", key(sheets = sheets))
        assertEquals("VLplaylist", key(sheets = sheets, detail = playlist))
    }

    @Test fun `the depth survives a restore`() {
        val nav = RootNav().apply {
            stackDepth = { 1 }
            showReplay = true
        }
        @Suppress("UNCHECKED_CAST")
        val saved = with(RootNav.Saver) { androidx.compose.runtime.saveable.SaverScope { true }.save(nav) }!!
        val restored = RootNav.Saver.restore(saved)!!
        assertEquals(1, restored.replayDepth)
        assertEquals("VLplaylist", key(nav = restored, detail = playlist, stackSize = 2))
    }

    @Test fun `Replay opened again over a pushed album comes back on top`() {
        var depth = 1
        val nav = RootNav().apply { stackDepth = { depth } }
        nav.showReplay = true
        depth = 2
        assertEquals("VLplaylist", key(nav = nav, detail = playlist, stackSize = 2))
        // The player menu's Replay, with the album still over it.
        nav.showReplay = true
        assertEquals("replay", key(nav = nav, detail = playlist, stackSize = 2))
    }
}
