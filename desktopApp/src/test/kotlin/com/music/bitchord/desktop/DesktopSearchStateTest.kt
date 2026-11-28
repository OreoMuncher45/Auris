package com.music.bitchord.desktop

import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A desktop Library search is the signed-in account's own songs: one still in flight, or on
 * screen, when the listener changes must not be shown to whoever is listening next.
 */
class DesktopSearchStateTest {

    private fun row() = SearchResult.Track(Song(videoId = "v", title = "t", artist = "a", thumbnailUrl = null))

    @Test
    fun `sign-out during an in-flight library search publishes nothing`() {
        val state = DesktopSearchState().apply { filter = SearchFilter.LIBRARY }
        val ticket = state.begin(SearchFilter.LIBRARY, generation = 1)
        state.onListenerChanged(signedIn = false)
        assertFalse(state.owns(ticket, generation = 2))
        // Even were the credentials to read the same, the request itself was superseded.
        assertFalse(state.owns(ticket, generation = 1))
        assertEquals(SearchFilter.ALL, state.filter)
    }

    @Test
    fun `account switch A to B to A during a library search publishes nothing`() {
        val state = DesktopSearchState().apply { filter = SearchFilter.LIBRARY }
        val ticket = state.begin(SearchFilter.LIBRARY, generation = 1)
        state.onListenerChanged(signedIn = true) // A -> B
        state.onListenerChanged(signedIn = true) // B -> A
        assertFalse(state.owns(ticket, generation = 3))
        assertEquals(SearchFilter.LIBRARY, state.filter)
    }

    @Test
    fun `credentials that moved under a library search drop its answer`() {
        val state = DesktopSearchState()
        val ticket = state.begin(SearchFilter.LIBRARY, generation = 1)
        assertTrue(state.owns(ticket, generation = 1))
        assertFalse(state.owns(ticket, generation = 2))
    }

    @Test
    fun `library rows on screen are cleared when the listener changes`() {
        val state = DesktopSearchState().apply {
            filter = SearchFilter.LIBRARY
            rows = listOf(row())
            committed = true
            loading = true
        }
        state.onListenerChanged(signedIn = true)
        assertTrue(state.rows.isEmpty())
        assertFalse(state.committed)
        assertFalse(state.loading)
    }

    @Test
    fun `a catalogue search survives a listener change`() {
        val state = DesktopSearchState().apply {
            filter = SearchFilter.SONGS
            rows = listOf(row())
            committed = true
        }
        val ticket = state.begin(SearchFilter.SONGS, generation = 1)
        state.onListenerChanged(signedIn = false)
        assertTrue(state.owns(ticket, generation = 2))
        assertEquals(1, state.rows.size)
    }

    @Test
    fun `a newer search supersedes an older one`() {
        val state = DesktopSearchState()
        val old = state.begin(SearchFilter.SONGS, generation = 1)
        val new = state.begin(SearchFilter.SONGS, generation = 1)
        assertFalse(state.owns(old, generation = 1))
        assertTrue(state.owns(new, generation = 1))
    }

    @Test
    fun `library rows shown under a catalogue pill are still cleared on a listener change`() {
        val state = DesktopSearchState().apply { filter = SearchFilter.LIBRARY }
        val ticket = state.begin(SearchFilter.LIBRARY, generation = 1)
        // The pill is moved while the Library search runs; its rows still land.
        state.filter = SearchFilter.ALL
        state.show(ticket, listOf(row()))
        state.committed = true
        state.onListenerChanged(signedIn = true)
        assertTrue(state.rows.isEmpty())
        assertFalse(state.committed)
        assertEquals(SearchFilter.ALL, state.filter)
    }

    @Test
    fun `a library search in flight under a catalogue pill is dropped on a listener change`() {
        val state = DesktopSearchState()
        val ticket = state.begin(SearchFilter.LIBRARY, generation = 1)
        state.loading = true
        state.filter = SearchFilter.SONGS
        state.onListenerChanged(signedIn = false)
        assertFalse(state.owns(ticket, generation = 1))
        assertFalse(state.loading)
        assertEquals(SearchFilter.SONGS, state.filter)
    }

    @Test
    fun `a library search that only lost its credentials still clears loading`() {
        val state = DesktopSearchState()
        val ticket = state.begin(SearchFilter.LIBRARY, generation = 1)
        state.loading = true
        // A cookie or channel change under the same account: no listener-change call.
        assertFalse(state.owns(ticket, generation = 2))
        assertTrue(state.isNewest(ticket))
        state.finish(ticket)
        assertFalse(state.loading)
    }

    @Test
    fun `a superseded search leaves loading to the one that replaced it`() {
        val state = DesktopSearchState()
        val old = state.begin(SearchFilter.SONGS, generation = 1)
        state.begin(SearchFilter.SONGS, generation = 1)
        state.loading = true
        state.finish(old)
        assertTrue(state.loading)
    }
}
