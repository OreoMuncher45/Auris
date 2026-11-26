package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class QueueReconcileTest {
    private fun plan(live: List<String>, liveIncoming: Int, held: List<String>, heldCurrent: Int) =
        QueueReconcile.plan(live, liveIncoming, held, heldCurrent, same = { a, b -> a == b })

    @Test
    fun identicalQueuesNeedNothing() {
        assertEquals(QueueReconcile.Same, plan(listOf("a", "b", "c"), 1, listOf("a", "b", "c"), 1))
    }

    @Test
    fun appendedEntriesAreCopied() {
        assertEquals(QueueReconcile.Append(3), plan(listOf("a", "b", "c", "d"), 1, listOf("a", "b", "c"), 1))
    }

    @Test
    fun entriesRemovedFromTheEndAreTrimmed() {
        assertEquals(QueueReconcile.TrimTo(3), plan(listOf("a", "b", "c"), 1, listOf("a", "b", "c", "d"), 1))
    }

    @Test
    fun editAfterTheIncomingEntryIsReplacedFromThere() {
        assertEquals(QueueReconcile.ReplaceFrom(2), plan(listOf("a", "b", "x", "c"), 1, listOf("a", "b", "c"), 1))
    }

    @Test
    fun removedHistoryRowBails() {
        // A history row removed before the playing track: the next entry still matches,
        // but swapping onto the standby would bring the removed row back.
        assertEquals(QueueReconcile.Bail, plan(listOf("a1", "b", "c"), 1, listOf("a0", "a1", "b", "c"), 2))
    }

    @Test
    fun replacedIncomingEntryBails() {
        assertEquals(QueueReconcile.Bail, plan(listOf("a", "x", "c"), 1, listOf("a", "b", "c"), 1))
    }

    @Test
    fun lateHandoffComparesTheCurrentEntry() {
        // The session already ran on into "b": its current entry is the incoming one.
        assertEquals(QueueReconcile.Same, plan(listOf("a", "b", "c"), 1, listOf("a", "b", "c"), 1))
        // Read as the next entry instead, a late handoff would compare "c" and bail.
        assertEquals(QueueReconcile.Bail, plan(listOf("a", "b", "c"), 2, listOf("a", "b", "c"), 1))
    }

    @Test
    fun missingIncomingEntryBails() {
        assertEquals(QueueReconcile.Bail, plan(listOf("a"), -1, listOf("a", "b"), 1))
    }

    @Test
    fun anotherCopyOfTheIncomingSongAtAnotherIndexBails() {
        // Repeat all wrapping B -> A at index 0; a second A appended and now next is index 2.
        assertEquals(QueueReconcile.Bail, plan(listOf("a", "b", "a"), 2, listOf("a", "b"), 0))
    }

    @Test
    fun aStreamReplacedAfterTheIncomingEntryIsCopiedAcross() {
        val live = listOf("a" to "s", "b" to "s", "c" to "file")
        val held = listOf("a" to "s", "b" to "s", "c" to "s")
        val p = QueueReconcile.plan(live, 1, held, 1, { x, y -> x.first == y.first }, { x, y -> x == y })
        assertEquals(QueueReconcile.ReplaceFrom(2), p)
        // The incoming entry's own playback changed: no handoff.
        val changedIncoming = QueueReconcile.plan(
            listOf("a" to "s", "b" to "file"), 1, listOf("a" to "s", "b" to "s"), 1,
            { x, y -> x.first == y.first }, { x, y -> x == y },
        )
        assertEquals(QueueReconcile.Bail, changedIncoming)
        // A history row's stream changing is not this handoff's business.
        val history = QueueReconcile.plan(
            listOf("a" to "file", "b" to "s"), 1, listOf("a" to "s", "b" to "s"), 1,
            { x, y -> x.first == y.first }, { x, y -> x == y },
        )
        assertEquals(QueueReconcile.Same, history)
    }

    @Test
    fun aHistoryRowWhoseStreamWasReplacedIsRefreshedWithoutStoppingTheHandoff() {
        // (entry, stream): entry 0 finished downloading after the standby copied the queue.
        val held = listOf("e0" to "https://a", "e1" to "https://b", "e2" to "https://c")
        val live = listOf("e0" to "file:///a.opus", "e1" to "https://b", "e2" to "https://c")
        val same = { x: Pair<String, String>, y: Pair<String, String> -> x.first == y.first }
        val samePlayable = { x: Pair<String, String>, y: Pair<String, String> -> x == y }
        assertEquals(QueueReconcile.Same, QueueReconcile.plan(live, 1, held, 1, same, samePlayable))
        assertEquals(listOf(0), QueueReconcile.historyRefresh(live, held, 1, same, samePlayable))
        // Nothing to refresh when the history plays what it did.
        assertEquals(emptyList<Int>(), QueueReconcile.historyRefresh(held, held, 1, same, samePlayable))
    }
}
