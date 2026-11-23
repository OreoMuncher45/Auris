package com.music.bitchord.playback

import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoBrowseContextsTest {
    private fun song(id: String) = Song(videoId = id, title = id, artist = "A", thumbnailUrl = null)

    private fun context(
        vararg ids: String,
        listener: String? = "c1",
        continuation: String? = null,
        sourceId: String = "VLPL1",
    ) = AutoBrowseContext(
        songs = ids.map(::song),
        continuation = continuation,
        listener = listener,
        source = QueueSource("Mix", PlaybackSourceType.BROWSE, sourceId),
    )

    private fun counter(): () -> String {
        var n = 0
        return { "t${n++}" }
    }

    @Test
    fun aTapResolvesToItsListingAndPosition() {
        val store = AutoBrowseContexts(newToken = counter())
        val ctx = context("a", "b", "c", continuation = "next")
        val token = store.register(ctx)
        val found = store.select(AutoBrowseContexts.mediaId(token, 1), "c1")
        found as AutoBrowseContexts.Selection.Found
        assertEquals(ctx, found.context)
        assertEquals(1, found.index)
        assertEquals("b", found.song.videoId)
    }

    @Test
    fun relistingTheSameFolderKeepsItsToken() {
        val store = AutoBrowseContexts(newToken = counter())
        val first = store.register(context("a", "b"))
        val second = store.register(context("a", "b"))
        assertEquals(first, second)
        assertEquals(1, store.size)
        assertNotEquals(first, store.register(context("b", "a")))
    }

    @Test
    fun theStoreIsBoundedAndEvictsTheLeastRecentlyListed() {
        val store = AutoBrowseContexts(capacity = 2, newToken = counter())
        val a = store.register(context("a"))
        val b = store.register(context("b"))
        // Listing A again makes B the eldest.
        store.register(context("a"))
        val c = store.register(context("c"))
        assertEquals(2, store.size)
        assertTrue(store.select(AutoBrowseContexts.mediaId(a, 0), "c1") is AutoBrowseContexts.Selection.Found)
        assertEquals(AutoBrowseContexts.Selection.Expired, store.select(AutoBrowseContexts.mediaId(b, 0), "c1"))
        assertTrue(store.select(AutoBrowseContexts.mediaId(c, 0), "c1") is AutoBrowseContexts.Selection.Found)
    }

    @Test
    fun aDefaultStoreHoldsThirtyTwoListings() {
        val store = AutoBrowseContexts(newToken = counter())
        repeat(100) { store.register(context("s$it")) }
        assertEquals(AutoBrowseContexts.DEFAULT_CAPACITY, store.size)
    }

    @Test
    fun anUnknownTokenIsExpiredNotInvalid() {
        val store = AutoBrowseContexts()
        assertEquals(AutoBrowseContexts.Selection.Expired, store.select("auto-browse:gone:0", "c1"))
    }

    @Test
    fun anAccountListingIsRefusedAfterTheAccountChanges() {
        val store = AutoBrowseContexts()
        val token = store.register(context("a", listener = "c1"))
        val id = AutoBrowseContexts.mediaId(token, 0)
        assertEquals(AutoBrowseContexts.Selection.AccountChanged, store.select(id, "c2"))
        assertEquals(AutoBrowseContexts.Selection.AccountChanged, store.select(id, null))
    }

    @Test
    fun aDeviceListingSurvivesSignInAndSignOut() {
        val store = AutoBrowseContexts()
        val token = store.register(context("a", listener = null))
        val id = AutoBrowseContexts.mediaId(token, 0)
        assertTrue(store.select(id, null) is AutoBrowseContexts.Selection.Found)
        assertTrue(store.select(id, "c2") is AutoBrowseContexts.Selection.Found)
    }

    @Test
    fun aGuestNetworkListingPlaysSignedOutButNotAfterSignIn() {
        val store = AutoBrowseContexts()
        val token = store.register(context("a", listener = AutoBrowseContexts.owner(null)))
        val id = AutoBrowseContexts.mediaId(token, 0)
        assertTrue(store.select(id, null) is AutoBrowseContexts.Selection.Found)
        assertEquals(AutoBrowseContexts.Selection.AccountChanged, store.select(id, "c1"))
    }

    @Test
    fun malformedIdsAndOutOfRangeIndicesAreInvalid() {
        val store = AutoBrowseContexts(newToken = counter())
        val token = store.register(context("a"))
        assertEquals(AutoBrowseContexts.Selection.Invalid, store.select("a", "c1"))
        assertEquals(AutoBrowseContexts.Selection.Invalid, store.select("auto-browse:$token", "c1"))
        assertEquals(AutoBrowseContexts.Selection.Invalid, store.select("auto-browse:$token:x", "c1"))
        assertEquals(AutoBrowseContexts.Selection.Invalid, store.select("auto-browse:$token:-1", "c1"))
        assertEquals(AutoBrowseContexts.Selection.Invalid, store.select("auto-browse:$token:1", "c1"))
        assertEquals(AutoBrowseContexts.Selection.Invalid, store.select("auto-browse:$token:0:9", "c1"))
        assertNull(AutoBrowseContexts.parse("auto-browse::0"))
    }

    @Test
    fun theSelectedItemPrefersStartIndexThenTheOnlyItemThenABrowseRow() {
        val ids = listOf("x", "auto-browse:t:0", "y")
        assertEquals("y", AutoBrowseContexts.pickSelected(ids, 2) { it })
        assertEquals("auto-browse:t:0", AutoBrowseContexts.pickSelected(ids, -1) { it })
        assertEquals("only", AutoBrowseContexts.pickSelected(listOf("only"), 5) { it })
        assertNull(AutoBrowseContexts.pickSelected(listOf("x", "y"), 9) { it })
        assertFalse(AutoBrowseContexts.isAutoBrowseId(null))
    }
}
