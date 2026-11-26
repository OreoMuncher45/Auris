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
        // Still the newest search, which is how the view model knows to ask
        // it again rather than leave the page loading.
        assertTrue(ownership.isNewest(ticket))
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

    @Test
    fun `a cached library answer is shown only to the listener it was answered for`() {
        val key = searchCacheKey("love", SearchFilter.LIBRARY, SearchSource.YOUTUBE)
        assertTrue(cachedSearchUsable(key, answeredFor = "A#0", now = "A#0"))
        // The same cache key after a scope or account change (a new stamp).
        assertFalse(cachedSearchUsable(key, answeredFor = "A#0", now = "A#1"))
        assertFalse(cachedSearchUsable(key, answeredFor = null, now = "A#0"))
        // A catalogue answer is anyone's.
        val catalogue = searchCacheKey("love", SearchFilter.SONGS, SearchSource.YOUTUBE)
        assertTrue(cachedSearchUsable(catalogue, answeredFor = null, now = "B#3"))
    }

    @Test
    fun `a newest search that lost ownership to a credentials change is asked again`() {
        val ticket = ownership.issue(SearchFilter.LIBRARY)
        generation++ // credentials moved, no newer search
        assertTrue(shouldReissueSearch(ownership.owns(ticket), ownership.isNewest(ticket)))
        // Superseded by a newer search: that one answers, not this.
        val newer = ownership.issue(SearchFilter.LIBRARY)
        assertFalse(shouldReissueSearch(ownership.owns(ticket), ownership.isNewest(ticket)))
        assertFalse(shouldReissueSearch(ownership.owns(newer), ownership.isNewest(newer)))
    }

    @Test
    fun `a load-more that lost ownership releases the flag only while its search is still shown`() {
        // Ownership lost before the merge: nothing settled, the search is still the one shown.
        val beforeMerge = ownership.issue(SearchFilter.LIBRARY)
        generation++
        assertTrue(loadMoreReleasesFlag(settledWhileOwned = false, stillShown = true, ownsNow = ownership.owns(beforeMerge)))
        // Ownership lost during the merge: owned at the first check, not after it.
        val duringMerge = ownership.issue(SearchFilter.LIBRARY)
        assertTrue(ownership.owns(duringMerge))
        generation++
        assertTrue(loadMoreReleasesFlag(settledWhileOwned = false, stillShown = true, ownsNow = ownership.owns(duringMerge)))
        // A newer search started meanwhile: it manages its own flag.
        val older = ownership.issue(SearchFilter.LIBRARY)
        ownership.issue(SearchFilter.LIBRARY)
        assertFalse(loadMoreReleasesFlag(settledWhileOwned = false, stillShown = false, ownsNow = ownership.owns(older)))
        // Published (or failed) while still owned: released as always.
        assertTrue(loadMoreReleasesFlag(settledWhileOwned = true, stillShown = false, ownsNow = true))
    }
}
