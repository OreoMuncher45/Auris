package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class QueueReconcileTest {
    private fun plan(live: List<String>, liveIncoming: Int, held: List<String>, heldCurrent: Int) =
        QueueReconcile.plan(live, liveIncoming, held, heldCurrent) { a, b -> a == b }

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
}
