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
    ) = rootPageKey(nav, sheets, showContinueListening, detail)

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

    @Test fun `the pages that cover the feed keep their order`() {
        // History over Settings over Replay, as on the base branch.
        val nav = RootNav().apply {
            showSettings = true
            showReplay = true
        }
        assertEquals("history", key(nav = nav, sheets = RootSheets().apply { showHistory = true }))
        assertEquals("settings", key(nav = nav))
        assertEquals("replay", key(nav = RootNav().apply { showReplay = true }, detail = playlist))
    }
}
