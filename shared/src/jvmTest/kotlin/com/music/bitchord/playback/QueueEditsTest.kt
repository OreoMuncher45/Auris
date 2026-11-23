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

    // ---- Edits planned from a stale snapshot are resolved against the live queue by entry ----

    private fun bare(id: String) =
        Song(videoId = id, title = id, artist = "", thumbnailUrl = null, queueEntryId = null)

    @Test
    fun aRowIsFoundAfterTheQueueGrewInFrontOfIt() {
        val snapshot = listOf("now", "a", "b", "c").map { song(it) }
        // A song went in at the head of the queue, and AutoPlay appended one.
        val live = listOf("now", "x", "a", "b", "c", "auto").map { song(it) }
        val targets = QueueEdits.targetsAt(snapshot, listOf(2))
        assertEquals(listOf(3), QueueEdits.resolve(targets, live))
    }

    @Test
    fun aRowIsFoundAfterTheQueueShrankAndARemovedRowIsDropped() {
        val snapshot = listOf("now", "a", "b", "c", "d").map { song(it) }
        // a and c have gone (a crossfade handoff moved past a; c was taken out).
        val live = listOf("now", "b", "d").map { song(it) }
        val targets = QueueEdits.targetsAt(snapshot, listOf(1, 2, 3, 4))
        assertEquals(listOf(1, 2), QueueEdits.resolve(targets, live))
    }

    @Test
    fun theSameLengthWithDifferentRowsEditsNothingByMistake() {
        val snapshot = listOf("now", "a", "b", "c").map { song(it) }
        // An Auto continuation replaced the upcoming rows; the length is the same.
        val live = listOf("now", "p", "q", "r").map { song(it) }
        assertEquals(emptyList(), QueueEdits.resolve(QueueEdits.targetsAt(snapshot, listOf(1, 3)), live))
        // Reordered but the same rows: each is found where it is now.
        val reordered = listOf("now", "c", "a", "b").map { song(it) }
        assertEquals(listOf(1, 3), QueueEdits.resolve(QueueEdits.targetsAt(snapshot, listOf(2, 3)), reordered))
    }

    @Test
    fun indicesOutsideTheSnapshotAreIgnored() {
        val snapshot = listOf(song("now"), song("a"))
        assertEquals(emptyList(), QueueEdits.targetsAt(snapshot, listOf(-1, 2, 9)))
    }

    @Test
    fun aRowWithoutAnEntryIdIsTakenOnlyWhereItWasSeen() {
        val snapshot = listOf(bare("now"), bare("a"), bare("b"), bare("a"))
        assertEquals(listOf(3), QueueEdits.resolve(QueueEdits.targetsAt(snapshot, listOf(3)), snapshot))
        // Shifted by one: which "a" was meant can't be told, so neither is touched.
        val shifted = listOf(bare("now"), bare("x"), bare("a"), bare("b"), bare("a"))
        assertEquals(emptyList(), QueueEdits.resolve(QueueEdits.targetsAt(snapshot, listOf(3)), shifted))
        // The picked (last) "a" was removed: the other copy is not it.
        val removed = listOf(bare("now"), bare("a"), bare("b"))
        assertEquals(emptyList(), QueueEdits.resolve(QueueEdits.targetsAt(snapshot, listOf(3)), removed))
        // A row with an entry id at that index is a different row.
        val replaced = listOf(bare("now"), song("a"), bare("b"))
        assertEquals(emptyList(), QueueEdits.resolve(QueueEdits.targetsAt(snapshot, listOf(1)), replaced))
    }

    @Test
    fun multiSelectRemovalOnAStaleSnapshotRemovesOnlyTheTickedRows() {
        val snapshot = listOf("now", "a", "b", "c", "d", "e").map { song(it) }
        // Two songs were queued at the head and AutoPlay appended one since the ticks.
        val live = listOf("now", "x", "y", "a", "b", "c", "d", "e", "auto").map { song(it) }
        val picked = QueueEdits.resolve(QueueEdits.targetsAt(snapshot, listOf(1, 2, 4)), live)
        assertEquals(listOf(3, 4, 6), picked)
        val removal = QueueEdits.capture(live, picked)
        val after = remove(live, picked)
        assertEquals(listOf("now", "x", "y", "c", "e", "auto"), ids(after))
        assertEquals(ids(live), ids(apply(after, QueueEdits.restorePlan(removal, after, 0))))
    }

    @Test
    fun undoRestoresByIdentityAfterTheQueueChangedAgain() {
        val live = listOf("now", "a", "b", "c", "d", "e").map { song(it) }
        val removal = QueueEdits.capture(live, listOf(1, 2, 4))
        // After the removal: [now, c, e]; then two songs went in at the head
        // and AutoPlay appended one — the same queue length as before by chance.
        val after = listOf("now", "x", "y", "c", "e", "auto").map { song(it) }
        val restored = apply(after, QueueEdits.restorePlan(removal, after, 0))
        assertEquals(listOf("now", "x", "y", "a", "b", "c", "d", "e", "auto"), ids(restored))
    }

    @Test
    fun undoDoesNotAddAnEntryThatIsAlreadyBack() {
        val live = listOf("now", "a", "b", "c").map { song(it) }
        val removal = QueueEdits.capture(live, listOf(1, 2))
        // a came back by some other path before Undo.
        val after = listOf("now", "a", "c").map { song(it) }
        val plan = QueueEdits.restorePlan(removal, after, 0)
        assertEquals(listOf("b"), plan.map { it.second.videoId })
        assertEquals(listOf("now", "a", "b", "c"), ids(apply(after, plan)))
    }

    @Test
    fun undoKeepsTheRowsInOrderWhenTheirAnchorsMoved() {
        val live = listOf("now", "a", "b", "c", "d").map { song(it) }
        // a and c are removed; a's anchor is b, c's is d.
        val removal = QueueEdits.capture(live, listOf(1, 3))
        // d was moved ahead of b since: a still goes in front of b, and c,
        // whose anchor is now earlier, follows a rather than jumping ahead of it.
        val after = listOf("now", "d", "b").map { song(it) }
        val restored = ids(apply(after, QueueEdits.restorePlan(removal, after, 0)))
        assertEquals(listOf("now", "d", "a", "c", "b"), restored)
    }

    @Test
    fun undoOfAThousandRowsIsPlannedInOnePass() {
        val live = (0 until 5000).map { song("s$it") }
        val picked = (1 until 5000 step 2).toList()
        val removal = QueueEdits.capture(live, picked)
        val after = remove(live, picked)
        assertEquals(ids(live), ids(apply(after, QueueEdits.restorePlan(removal, after, 0))))
    }

    @Test
    fun undoKeepsConsecutiveRowsInOrderWhenTheirAnchorIsHistory() {
        val live = listOf("now", "a", "b", "c", "d").map { song(it) }
        val removal = QueueEdits.capture(live, listOf(1, 2))
        // Playback moved on to c, the rows' anchor: both fall back to their
        // old positions, clamped after the playhead, and stay a then b.
        val after = listOf("now", "c", "d").map { song(it) }
        assertEquals(listOf("now", "c", "a", "b", "d"), ids(apply(after, QueueEdits.restorePlan(removal, after, 1))))
    }
}
