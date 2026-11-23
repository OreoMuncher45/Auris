package com.music.bitchord.ui

import com.music.bitchord.data.model.SearchFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Library search answers from the signed-in account, so an answer still in
 * flight when the listener changes must never be published. The listener
 * generation is driven by hand here the way [com.music.bitchord.data.innertube.Innertube]
 * bumps it on every cookie or channel change; the view model supersedes
 * the search on sign-out and on every switch.
 */
class SearchOwnershipTest {

    private var generation = 0L
    private val ownership = SearchOwnership { generation }

    /** What the view model does on a change of listener. */
    private fun listenerChanged() {
        generation++
        ownership.supersede()
    }

    @Test
    fun `an unchallenged library search publishes`() {
        val ticket = ownership.issue(SearchFilter.LIBRARY)
        assertTrue(ownership.owns(ticket))
    }

    @Test
    fun `sign-out during an in-flight library search publishes nothing`() {
        val ticket = ownership.issue(SearchFilter.LIBRARY)
        listenerChanged() // signOut: cookie cleared, search superseded
        assertFalse(ownership.owns(ticket))
    }

    @Test
    fun `account switch A to B during a library search publishes nothing`() {
        val ticket = ownership.issue(SearchFilter.LIBRARY)
        listenerChanged() // A -> B
        assertFalse(ownership.owns(ticket))
    }

    @Test
    fun `account switch A to B to A during a library search publishes nothing`() {
        val ticket = ownership.issue(SearchFilter.LIBRARY)
        listenerChanged() // A -> B
        listenerChanged() // B -> A: same listener key as when it was asked
        assertFalse(ownership.owns(ticket))
    }

    @Test
    fun `credentials changed without a supersede still drop a library answer`() {
        // A candidate session under validation moves the generation before
        // the view model has adopted anyone.
        val ticket = ownership.issue(SearchFilter.LIBRARY)
        generation++
        assertFalse(ownership.owns(ticket))
    }

    @Test
    fun `the search re-asked after a switch belongs to the new listener`() {
        ownership.issue(SearchFilter.LIBRARY)
        listenerChanged()
        val again = ownership.issue(SearchFilter.LIBRARY)
        assertTrue(ownership.owns(again))
    }

    @Test
    fun `a catalogue search is not tied to the listener`() {
        val ticket = ownership.issue(SearchFilter.SONGS)
        generation++
        assertTrue(ownership.owns(ticket))
    }

    @Test
    fun `a newer search supersedes an older one`() {
        val old = ownership.issue(SearchFilter.SONGS)
        val new = ownership.issue(SearchFilter.ALBUMS)
        assertFalse(ownership.owns(old))
        assertTrue(ownership.owns(new))
    }

    @Test
    fun `only library answers are evicted from the search cache on a switch`() {
        assertTrue(isAccountPrivateSearchKey(searchCacheKey("coldplay", SearchFilter.LIBRARY, SearchSource.YOUTUBE)))
        assertTrue(isAccountPrivateSearchKey(searchCacheKey("a:b", SearchFilter.LIBRARY, SearchSource.YOUTUBE)))
        assertFalse(isAccountPrivateSearchKey(searchCacheKey("coldplay", SearchFilter.SONGS, SearchSource.YOUTUBE)))
        assertFalse(isAccountPrivateSearchKey(searchCacheKey("LIBRARY", SearchFilter.ALL, SearchSource.SPOTIFY)))
    }
}
