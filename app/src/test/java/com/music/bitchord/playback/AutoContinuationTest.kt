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
}
