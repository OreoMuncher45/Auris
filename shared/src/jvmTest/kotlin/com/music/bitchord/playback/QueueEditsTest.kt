package com.music.bitchord.playback

import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueueEditsTest {
    private fun song(id: String, tier: QueueTier = QueueTier.CONTEXT) =
        Song(videoId = id, title = id, artist = "", thumbnailUrl = null, queueTier = tier, queueEntryId = "q-$id")

    private fun ids(list: List<Song>) = list.map { it.videoId }

    private fun apply(timeline: List<Song>, plan: List<Pair<Int, Song>>): List<Song> =
        timeline.toMutableList().apply { plan.forEach { (at, s) -> add(at, s) } }

    private fun remove(timeline: List<Song>, indices: Collection<Int>): List<Song> =
        timeline.filterIndexed { i, _ -> i !in indices }

    @Test
    fun undoPutsOneRowBackWhereItWas() {
        val timeline = listOf(song("a"), song("b"), song("c"), song("d"))
        val removal = QueueEdits.capture(timeline, listOf(2))
        val after = remove(timeline, listOf(2))
        assertEquals(listOf("a", "b", "c", "d"), ids(apply(after, QueueEdits.restorePlan(removal, after, 0))))
    }

    @Test
    fun undoRestoresScatteredRowsInOrder() {
        val timeline = listOf("a", "b", "c", "d", "e", "f").map { song(it) }
        val picked = listOf(4, 1, 2)
        val removal = QueueEdits.capture(timeline, picked)
        val after = remove(timeline, picked)
        assertEquals(ids(timeline), ids(apply(after, QueueEdits.restorePlan(removal, after, 0))))
    }

    @Test
    fun undoFollowsTheNextRowWhenTheQueueMoved() {
        val timeline = listOf("a", "b", "c", "d").map { song(it) }
        val removal = QueueEdits.capture(timeline, listOf(2))
        // Removed c, then a new song went in at the head of the queue.
        val after = listOf(song("a"), song("x"), song("b"), song("d"))
        assertEquals(listOf("a", "x", "b", "c", "d"), ids(apply(after, QueueEdits.restorePlan(removal, after, 0))))
    }

    @Test
    fun undoNeverRestoresIntoHistory() {
        val timeline = listOf("a", "b", "c", "d").map { song(it) }
        val removal = QueueEdits.capture(timeline, listOf(1))
        // Playback has since moved on to d; c, b's anchor, is history now.
        val after = listOf(song("a"), song("c"), song("d"))
        val plan = QueueEdits.restorePlan(removal, after, 2)
        assertEquals(listOf(3), plan.map { it.first })
    }

    @Test
    fun removalRunsAreLastFirstAndContiguous() {
        assertEquals(listOf(7..8, 4..4, 1..2), QueueEdits.removalRuns(listOf(1, 2, 4, 7, 8)))
    }

    @Test
    fun clearTakesOnlyUpcomingQueuedRows() {
        val timeline = listOf(
            song("p", QueueTier.USER_QUEUE),
            song("now"),
            song("u1", QueueTier.USER_QUEUE),
            song("u2", QueueTier.USER_QUEUE),
            song("c1"),
            song("auto", QueueTier.AUTOPLAY),
        )
        assertEquals(listOf(2, 3), QueueEdits.userQueueIndices(timeline, 1))
    }

    @Test
    fun playNextMovesRowsToTheHeadAsQueued() {
        val timeline = listOf(
            song("now"),
            song("u1", QueueTier.USER_QUEUE),
            song("c1"),
            song("c2"),
            song("auto", QueueTier.AUTOPLAY),
        )
        val move = QueueEdits.moveToUserQueue(timeline, 0, listOf(4, 2), toHead = true)!!
        assertEquals(listOf(2, 4), move.remove)
        assertEquals(1, move.insertAt)
        assertEquals(listOf("c1", "auto"), ids(move.songs))
        assertEquals(listOf(QueueTier.USER_QUEUE, QueueTier.USER_QUEUE), move.songs.map { it.queueTier })
        assertEquals(listOf("q-c1", "q-auto"), move.songs.map { it.queueEntryId })
    }

    @Test
    fun addToQueueMovesARowToTheEndOfTheQueue() {
        val timeline = listOf(song("now"), song("u1", QueueTier.USER_QUEUE), song("c1"), song("c2"))
        val move = QueueEdits.moveToUserQueue(timeline, 0, listOf(3), toHead = false)!!
        // Removing c2 leaves [now, u1, c1]; the queue's end is before c1.
        assertEquals(2, move.insertAt)
    }

    @Test
    fun moveIsNullWhenTheRowIsAlreadyThere() {
        val timeline = listOf(song("now"), song("u1", QueueTier.USER_QUEUE), song("c1"))
        assertNull(QueueEdits.moveToUserQueue(timeline, 0, listOf(1), toHead = true))
        assertNull(QueueEdits.moveToUserQueue(timeline, 0, listOf(0), toHead = true))
    }

    @Test
    fun aContextRowAtTheHeadIsStillRetagged() {
        val timeline = listOf(song("now"), song("c1"), song("c2"))
        val move = QueueEdits.moveToUserQueue(timeline, 0, listOf(1), toHead = true)!!
        assertEquals(1, move.insertAt)
        assertEquals(QueueTier.USER_QUEUE, move.songs.single().queueTier)
    }
}
