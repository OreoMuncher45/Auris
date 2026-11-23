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
    fun `page is dropped when the queue no longer holds the folder`() {
        assertNull(autoContinuationInsertIndex(queue, "other", currentIndex = 0))
    }

    @Test
    fun `an edit to the queue being replaced is not the installation`() {
        // AutoPlay tops up the old queue before Media3 installs the folder's.
        val old = listOf("old-1", "old-2", "old-autoplay")
        assertFalse(isAutoQueueInstalled("folder-start", 1, old.size) { old[it] })
    }

    @Test
    fun `the folder's queue is installed once its start entry is in place`() {
        val installed = listOf("folder-0", "folder-start", "folder-2")
        assertTrue(isAutoQueueInstalled("folder-start", 1, installed.size) { installed[it] })
    }

    @Test
    fun `a queue too short to hold the start index is not the installation`() {
        assertFalse(isAutoQueueInstalled("folder-start", 3, 1) { "folder-start" })
    }

    @Test
    fun `without an entry id any playlist change counts`() {
        assertTrue(isAutoQueueInstalled(null, 0, 0) { null })
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
}
