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

    @Test
    fun `a device folder's queue is never refused across a sign-in`() {
        val downloads = PendingAutoInstall(startEntryId = "e1", startIndex = 1, size = 3, owner = null)
        assertEquals(false, staleAutoInstall(downloads, entries.size, entries::getOrNull, ListenerStamp("B", 5)))
    }

    // ---- Every built Auto queue keeps its own record until installed or refused ----

    @Test
    fun `a queue built for A then one for B, A's is still refused under B and B's installed`() {
        val records = PendingAutoInstalls()
        val forA = PendingAutoInstall(startEntryId = "a1", startIndex = 0, size = 2, owner = ListenerStamp("A", 4))
        val forB = PendingAutoInstall(startEntryId = "b1", startIndex = 0, size = 2, owner = ListenerStamp("B", 5))
        records.add(forA)
        records.add(forB)
        val queueA = listOf("a1", "a2")
        val queueB = listOf("b1", "b2")
        val now = ListenerStamp("B", 5)
        assertTrue(records.refuses(queueA.size, queueA::getOrNull, now) === forA)
        records.refused(forA)
        assertTrue(forA.installed.isCompleted)
        assertNull(records.refuses(queueB.size, queueB::getOrNull, now))
        assertTrue(records.installedOrNull(queueB.size, queueB::getOrNull) === forB)
        assertEquals(0, records.size)
    }

    @Test
    fun `A's stale answer delivered after B started its continuation leaves B's continuation running`() {
        val records = PendingAutoInstalls()
        val forA = PendingAutoInstall(startEntryId = "a1", startIndex = 0, size = 2, owner = ListenerStamp("A", 4))
        val forB = PendingAutoInstall(startEntryId = "b1", startIndex = 0, size = 2, owner = ListenerStamp("B", 5))
        records.add(forA)
        records.following = forA
        records.add(forB)
        records.following = forB
        // A's answer is handed over late, under B: refused, and B's continuation is not A's to end.
        assertFalse(records.refused(forA))
        assertTrue(records.following === forB)
        // B's own refusal would end it.
        assertTrue(records.refused(forB))
        assertNull(records.following)
    }

    @Test
    fun `a newer build for the same entry replaces the older record, and the oldest goes past capacity`() {
        val records = PendingAutoInstalls(capacity = 2)
        val first = PendingAutoInstall(startEntryId = "x", startIndex = 0, size = 1, owner = ListenerStamp("A", 4))
        val again = PendingAutoInstall(startEntryId = "x", startIndex = 0, size = 1, owner = ListenerStamp("A", 4))
        records.add(first)
        records.add(again)
        assertEquals(1, records.size)
        assertTrue(first.installed.isCompleted)
        records.add(PendingAutoInstall(startEntryId = "y", startIndex = 0, size = 1, owner = null))
        records.add(PendingAutoInstall(startEntryId = "z", startIndex = 0, size = 1, owner = null))
        assertEquals(2, records.size)
        assertTrue(again.installed.isCompleted)
    }

    // ---- An Auto queue resolved row by row, answered for the session or without it ----

    /** Search rows for A, then downloads and a handed-over track, resolved while a later item was out. */
    private fun resolvedForA() = SessionRows<String>().apply {
        addForSession(0, listOf("a-search-0", "a-search-1"))
        addAll(listOf("download"))
        add("handed-over")
    }

    @Test
    fun `search rows read for A go once A's session has ended, and rows of no account stay`() {
        val (kept, start) = resolvedForA().finish(start = 3, current = false)
        assertEquals(listOf("download", "handed-over"), kept)
        assertEquals(1, start)
    }

    @Test
    fun `while the session is current every row stays`() {
        val (kept, start) = resolvedForA().finish(start = 1, current = true)
        assertEquals(listOf("a-search-0", "a-search-1", "download", "handed-over"), kept)
        assertEquals(1, start)
        assertTrue(resolvedForA().staleRequests(current = true).isEmpty())
    }

    @Test
    fun `a search that read nothing for a session since ended is not handed back as it came`() {
        val rows = SessionRows<String>().apply { addForSession(0, emptyList()); add("handed-over") }
        assertEquals(setOf(0), rows.staleRequests(current = false))
        assertEquals(listOf("handed-over"), rows.finish(0, current = false).first)
    }

    @Test
    fun `a track whose details came from A's cache goes back to the item as handed over once A's session ends`() {
        // Read from A's Recents cache, then a later search item suspended while the account switched.
        val rows = SessionRows<String>().apply {
            addForSession("a-cached-details", otherwise = "as-handed-over")
            addForSession(1, listOf("a-search"))
        }
        assertTrue(rows.anyBound)
        assertEquals(listOf("as-handed-over"), rows.finish(0, current = false).first)
        assertEquals(listOf("a-cached-details", "a-search"), rows.finish(0, current = true).first)
    }

    // ---- An answer handed over only while its session is current ----

    /** A recipient's direct listener, the way Media3 attaches its delivery: what it was handed, if anything. */
    private fun <V> com.google.common.util.concurrent.SettableFuture<V>.recipient(onDelivery: () -> Unit = {}): () -> Result<V>? {
        var got: Result<V>? = null
        addListener(
            { got = runCatching { get() }; onDelivery() },
            com.google.common.util.concurrent.MoreExecutors.directExecutor(),
        )
        return { got }
    }

    @Test
    fun `rows read for the current session are handed over`() {
        val answer = com.google.common.util.concurrent.SettableFuture.create<String>()
        val got = answer.recipient()
        answer.deliver(SessionAnswer("a-rows", bound = true, stale = "none")) { complete -> complete() }
        assertEquals("a-rows", got()?.getOrNull())
    }

    @Test
    fun `rows read for A after a switch to B are never handed over`() {
        val innertube = com.music.bitchord.data.innertube.Innertube
        innertube.cookie = "SID=A"
        val a = innertube.listenerStamp()
        innertube.cookie = "SID=B"
        try {
            val answer = com.google.common.util.concurrent.SettableFuture.create<String>()
            val got = answer.recipient()
            var staleRan = false
            answer.deliver(SessionAnswer("a-rows", bound = true, stale = "none", onStale = { staleRan = true })) { complete ->
                innertube.whileListener(a, complete)
            }
            assertEquals("none", got()?.getOrNull())
            assertTrue(staleRan)
        } finally {
            innertube.cookie = null
        }
    }

    @Test
    fun `a stale answer with nothing to hand over instead fails the request`() {
        val answer = com.google.common.util.concurrent.SettableFuture.create<String>()
        val got = answer.recipient()
        answer.deliver(SessionAnswer("a-queue", bound = true)) { null }
        assertTrue(got()?.exceptionOrNull()?.cause is StaleSessionException)
    }

    @Test
    fun `an answer of no account is handed over whoever is signed in`() {
        val answer = com.google.common.util.concurrent.SettableFuture.create<String>()
        answer.deliver(SessionAnswer("downloads", bound = false)) { null }
        assertEquals("downloads", answer.get())
    }

    @Test
    fun `a switch during the delivery waits on the session lock until the rows are handed over`() {
        val innertube = com.music.bitchord.data.innertube.Innertube
        innertube.cookie = "SID=A"
        val a = innertube.listenerStamp()
        try {
            val answer = com.google.common.util.concurrent.SettableFuture.create<String>()
            var blockedOnLock = false
            var stampDuringDelivery: Any? = null
            lateinit var switcher: Thread
            val got = answer.recipient {
                // The switch is asked for while the recipient is being handed the rows.
                switcher = Thread { innertube.cookie = "SID=B" }.also { it.start() }
                // Seen blocked on the lock, within a bound; a switcher that
                // finished first, or never got there, fails the test.
                val deadline = System.nanoTime() + 5_000_000_000L
                while (System.nanoTime() < deadline && switcher.isAlive && switcher.state != Thread.State.BLOCKED) Thread.yield()
                blockedOnLock = switcher.state == Thread.State.BLOCKED
                stampDuringDelivery = innertube.listenerStamp()
            }
            answer.deliver(SessionAnswer("a-rows", bound = true, stale = "none")) { complete -> innertube.whileListener(a, complete) }
            switcher.join(5_000)
            assertEquals("a-rows", got()?.getOrNull())
            assertTrue("the switcher was not seen blocked on the session lock during delivery", blockedOnLock)
            assertEquals(a, stampDuringDelivery)
            assertFalse(switcher.isAlive)
            assertFalse(innertube.listenerStamp() == a)
        } finally {
            innertube.cookie = null
        }
    }
}
