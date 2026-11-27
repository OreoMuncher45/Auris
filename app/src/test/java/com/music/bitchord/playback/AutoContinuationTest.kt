package com.music.bitchord.playback

import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoContinuationTest {
    private fun song(id: String, sourceId: String?, tier: QueueTier = QueueTier.CONTEXT) = Song(
        videoId = id,
        title = id,
        artist = "",
        thumbnailUrl = null,
        queueTier = tier,
        playbackSourceId = sourceId,
    )

    // Folder of three, then two AutoPlay tracks.
    private val queue = listOf(
        song("a", "folder"),
        song("b", "folder"),
        song("c", "folder"),
        song("x", null, QueueTier.AUTOPLAY),
        song("y", null, QueueTier.AUTOPLAY),
    )

    @Test
    fun `page goes behind the folder while it is still playing`() {
        assertEquals(3, autoContinuationInsertIndex(queue, "folder", currentIndex = 0))
        assertEquals(3, autoContinuationInsertIndex(queue, "folder", currentIndex = 2))
    }

    @Test
    fun `page is dropped once playback has moved past the folder`() {
        assertNull(autoContinuationInsertIndex(queue, "folder", currentIndex = 3))
        assertNull(autoContinuationInsertIndex(queue, "folder", currentIndex = 4))
    }

    @Test
    fun `play next entries behind the folder stay ahead of its next page`() {
        val withNext = listOf(
            song("a", "folder"),
            song("p", null, QueueTier.USER_QUEUE),
            song("q", null, QueueTier.USER_QUEUE),
            song("x", null, QueueTier.AUTOPLAY),
        )
        assertEquals(3, autoContinuationInsertIndex(withNext, "folder", currentIndex = 0))
        // Still owed while the listener plays the queued entries.
        assertEquals(3, autoContinuationInsertIndex(withNext, "folder", currentIndex = 2))
        assertNull(autoContinuationInsertIndex(withNext, "folder", currentIndex = 3))
    }

    @Test
    fun `a new queue with the same source id is not the folder`() {
        val replaced = listOf(
            song("a", "folder").copy(queueEntryId = "new-1"),
            song("b", "folder").copy(queueEntryId = "new-2"),
        )
        assertNull(autoContinuationInsertIndex(replaced, "folder", 0, folderEntries = setOf("old-1", "old-2")))
        assertEquals(2, autoContinuationInsertIndex(replaced, "folder", 0, folderEntries = setOf("new-1", "new-2")))
    }

    @Test
    fun `a station accepts a queue that only grew by folder pages`() {
        val original = listOf("a", "b")
        val page = ContinuationInsert(anchor = "b", entries = listOf("c", "d"))
        assertTrue(queueOnlyGrewByContinuation(original, listOf("a", "b", "c", "d"), listOf(page)))
        assertTrue(queueOnlyGrewByContinuation(original, original, emptyList()))
    }

    @Test
    fun `a station sees a moved or removed folder page row as an edit`() {
        val original = listOf("a", "b", "q")
        val page = ContinuationInsert(anchor = "b", entries = listOf("c", "d"))
        assertTrue(queueOnlyGrewByContinuation(original, listOf("a", "b", "c", "d", "q"), listOf(page)))
        // "d" moved ahead of "c": taking the page's rows out would hide it.
        assertFalse(queueOnlyGrewByContinuation(original, listOf("a", "b", "d", "c", "q"), listOf(page)))
        assertFalse(queueOnlyGrewByContinuation(original, listOf("a", "b", "c", "q", "d"), listOf(page)))
        assertFalse(queueOnlyGrewByContinuation(original, listOf("a", "b", "c", "q"), listOf(page)))
    }

    @Test
    fun `page is dropped when the queue no longer holds the folder`() {
        assertNull(autoContinuationInsertIndex(queue, "other", currentIndex = 0))
    }

    @Test
    fun `rows revoked after an account switch go and the start follows its row`() {
        // An Auto row accepted for the old account at 1, then device tracks.
        val (kept, start) = withoutRevoked(listOf("dl-0", "account-row", "dl-2", "dl-3"), setOf(1), start = 3)
        assertEquals(listOf("dl-0", "dl-2", "dl-3"), kept)
        assertEquals(2, start)
    }

    @Test
    fun `a revoked start falls to the row that took its place`() {
        val (kept, start) = withoutRevoked(listOf("a", "revoked", "b"), setOf(1), start = 1)
        assertEquals(listOf("a", "b"), kept)
        assertEquals(1, start)
    }

    @Test
    fun `nothing revoked leaves the resolution as it was`() {
        val resolved = listOf("a", "b")
        val (kept, start) = withoutRevoked(resolved, emptySet(), start = 1)
        assertTrue(kept === resolved)
        assertEquals(1, start)
    }

    @Test
    fun `a revoked last start falls back to the last kept row`() {
        // [public, account], playing the account row, which is revoked.
        val (kept, start) = withoutRevoked(listOf("public", "account"), setOf(1), start = 1)
        assertEquals(listOf("public"), kept)
        assertEquals(0, start)
    }

    @Test
    fun `nothing kept leaves no row to start`() {
        val (kept, start) = withoutRevoked(listOf("account"), setOf(0), start = 0)
        assertTrue(kept.isEmpty())
        assertEquals(0, start)
    }

    // ---- An Auto queue rechecked when Media3 installs it ----

    private val entries = listOf("e0", "e1", "e2")
    private val builtFor = ListenerStamp("A", generation = 4)
    private val pending = PendingAutoInstall(startEntryId = "e1", startIndex = 1, size = 3, owner = builtFor)

    @Test
    fun `an Auto queue installed under the session it was built for is installed`() {
        assertEquals(false, staleAutoInstall(pending, entries.size, entries::getOrNull, builtFor))
    }

    @Test
    fun `an Auto queue whose account changed before install is refused`() {
        assertEquals(true, staleAutoInstall(pending, entries.size, entries::getOrNull, ListenerStamp("B", 5)))
    }

    @Test
    fun `an Auto queue is refused after a switch away and back before install`() {
        assertEquals(true, staleAutoInstall(pending, entries.size, entries::getOrNull, ListenerStamp("A", 6)))
    }

    @Test
    fun `an edit to the queue being replaced is not the installation`() {
        // AutoPlay tops up the old queue before Media3 installs the folder's.
        val old = listOf("old-1", "old-2", "old-autoplay")
        assertNull(staleAutoInstall(pending, old.size, old::getOrNull, builtFor))
    }

    @Test
    fun `another queue arriving meanwhile is not the pending one`() {
        assertNull(staleAutoInstall(pending, 2, listOf("x", "y")::getOrNull, ListenerStamp("B", 5)))
        assertNull(staleAutoInstall(null, entries.size, entries::getOrNull, ListenerStamp("B", 5)))
    }

    // ---- An Auto queue resolved row by row, checked once everything is ----

    private val a = ListenerStamp("A", generation = 4)

    /** Search rows for A, then downloads and a handed-over track, resolved while a later item was out. */
    private fun resolvedForA() = SessionRows<String>(a).apply {
        addForSession(0, listOf("a-search-0", "a-search-1"))
        addAll(listOf("download"))
        add("handed-over")
    }

    @Test
    fun `search rows read for A go after a switch to B while a later item was out, and rows of no account stay`() {
        val (kept, start) = resolvedForA().finish(start = 3, now = ListenerStamp("B", 5))
        assertEquals(listOf("download", "handed-over"), kept)
        assertEquals(1, start)
    }

    @Test
    fun `search rows read for A go after a switch away and back to A`() {
        val (kept, _) = resolvedForA().finish(start = 0, now = ListenerStamp("A", 6))
        assertEquals(listOf("download", "handed-over"), kept)
    }

    @Test
    fun `with no switch every row stays`() {
        val (kept, start) = resolvedForA().finish(start = 1, now = a)
        assertEquals(listOf("a-search-0", "a-search-1", "download", "handed-over"), kept)
        assertEquals(1, start)
        assertTrue(resolvedForA().staleRequests(a).isEmpty())
    }

    @Test
    fun `a search that went stale before reading anything is not handed back as it came`() {
        // The search was asked under A and the switch landed before it read anything.
        val rows = SessionRows<String>(a).apply { addForSession(0, emptyList()); add("handed-over") }
        assertEquals(setOf(0), rows.staleRequests(ListenerStamp("B", 5)))
        assertEquals(listOf("handed-over"), rows.finish(0, ListenerStamp("B", 5)).first)
    }

    // ---- An answer checked again once it is delivered ----

    @Test
    fun `a switch after the answer was checked but before it is delivered makes the recipient ask again`() {
        var session = a
        var stale = 0
        val answer = com.google.common.util.concurrent.SettableFuture.create<String>()
        val delivered = answer.recheckedFor(a, { session }) { stale++ }
        // The answer was checked under A; the switch lands before Media3 delivers it.
        session = ListenerStamp("B", 5)
        answer.set("a-rows")
        assertEquals("a-rows", delivered.get())
        assertEquals(1, stale)
    }

    @Test
    fun `a switch during the delivery is followed by asking again`() {
        var session = a
        var stale = 0
        val answer = com.google.common.util.concurrent.SettableFuture.create<String>()
        val delivered = answer.recheckedFor(a, { session }) { stale++ }
        // The recipient's own listener runs first; the switch lands while it delivers.
        delivered.addListener({ session = ListenerStamp("A", 6) }, com.google.common.util.concurrent.MoreExecutors.directExecutor())
        answer.set("a-rows")
        assertEquals(1, stale)
    }

    @Test
    fun `an answer delivered with no switch is not asked again`() {
        var stale = 0
        val answer = com.google.common.util.concurrent.SettableFuture.create<String>()
        val delivered = answer.recheckedFor(a, { a }) { stale++ }
        answer.set("a-rows")
        assertEquals("a-rows", delivered.get())
        assertEquals(0, stale)
    }

    @Test
    fun `cancelling the delivered answer cancels the search and asks nothing again`() {
        var stale = 0
        val answer = com.google.common.util.concurrent.SettableFuture.create<String>()
        val delivered = answer.recheckedFor(a, { ListenerStamp("B", 5) }) { stale++ }
        delivered.cancel(false)
        assertTrue(answer.isCancelled)
        assertEquals(0, stale)
    }
}
