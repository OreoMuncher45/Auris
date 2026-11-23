package com.music.bitchord.playback

import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song

/**
 * The queue screen's edits — remove with Undo, and moving rows to the head
 * (or the end) of Next in queue — worked out on a plain timeline, so the
 * player only ever sees ordinary removes and inserts.
 *
 * Written for BitChord's queue swipe, Undo and multi-select (9ce299497,
 * bcc743d49, dfa2940a1, 3f5d80c3d, 5c772d635). BitChord does this in its
 * service because its controllers see a window of the queue; Auris's
 * controller sees the whole timeline, so the same edits are planned here.
 */
object QueueEdits {

    /** One removed row, and the row that followed it, so Undo can put it back in its place. */
    data class Removed(val originalIndex: Int, val song: Song, val anchor: String?)

    /** What a removal took out, oldest position first. */
    data class Removal(val rows: List<Removed>)

    /** How a row is found again after the timeline has moved on. */
    fun identity(song: Song): String = identity(song.videoId, song.queueEntryId)

    /** [identity] from a row's raw ids, for callers that have not built a [Song]. */
    fun identity(videoId: String, queueEntryId: String?): String =
        queueEntryId?.let { "e:$it" } ?: "v:$videoId"

    /** A row as the screen saw it: where it was, and which entry it was. */
    data class Target(val index: Int, val song: Song)

    /** The rows at [indices] of the [snapshot] the screen drew them from. */
    fun targetsAt(snapshot: List<Song>, indices: Collection<Int>): List<Target> =
        indices.mapNotNull { i -> snapshot.getOrNull(i)?.let { Target(i, it) } }

    /**
     * Where [targets] are in the [live] queue now, ascending and distinct.
     *
     * Rows are matched by [identity], never by index: the queue may have grown,
     * shrunk or been rebuilt since the screen drew it. A row that is no longer
     * there is dropped rather than replaced by whatever took its place. An
     * identity that occurs more than once (only possible for rows without an
     * entry id) is trusted only where the screen saw it.
     *
     * One pass to index [live], then one lookup per target.
     */
    fun resolve(targets: Collection<Target>, live: List<Song>): List<Int> {
        if (targets.isEmpty()) return emptyList()
        val byIdentity = HashMap<String, Int>(live.size * 2)
        live.forEachIndexed { i, song ->
            val key = identity(song)
            byIdentity[key] = if (byIdentity.containsKey(key)) AMBIGUOUS else i
        }
        val found = java.util.TreeSet<Int>()
        for (target in targets) {
            val key = identity(target.song)
            val at = byIdentity[key] ?: continue
            if (at != AMBIGUOUS) {
                found += at
            } else if (live.getOrNull(target.index)?.let(::identity) == key) {
                found += target.index
            }
        }
        return found.toList()
    }

    private const val AMBIGUOUS = -1

    /**
     * The rows at [indices] (any order, duplicates ignored), each with the
     * identity of the next row that is *not* being removed.
     */
    fun capture(timeline: List<Song>, indices: Collection<Int>): Removal {
        val removed = indices.filter { it in timeline.indices }.toSortedSet()
        val rows = removed.map { index ->
            var next = index + 1
            while (next in removed) next++
            Removed(index, timeline[index], timeline.getOrNull(next)?.let(::identity))
        }
        return Removal(rows)
    }

    /**
     * Contiguous runs of [indices], last run first, as half-open ranges — the
     * order to remove them in so the earlier indices stay valid, and one call
     * per run rather than one per row.
     */
    fun removalRuns(indices: Collection<Int>): List<IntRange> {
        val sorted = indices.toSortedSet().toList()
        val runs = ArrayList<IntRange>()
        var end = sorted.size
        while (end > 0) {
            var start = end - 1
            while (start > 0 && sorted[start - 1] == sorted[start] - 1) start--
            runs += sorted[start]..sorted[end - 1]
            end = start
        }
        return runs
    }

    /**
     * Where each removed row goes back, as (index, song) pairs to insert one
     * after another into [timeline] as it is now.
     *
     * A row returns in front of the row that followed it when that row is
     * still upcoming; otherwise at its old position. Either way never before
     * what is playing now — Undo restores upcoming songs, it does not rewrite
     * history — and the rows keep their order among themselves. A row whose
     * entry is already back in the queue is not added twice.
     */
    fun restorePlan(removal: Removal, timeline: List<Song>, currentIndex: Int): List<Pair<Int, Song>> =
        restorePlanByIdentity(removal, timeline.map(::identity), currentIndex)

    /**
     * [restorePlan] against the live queue's identities alone, so the player
     * does not have to rebuild every row as a [Song] to Undo.
     *
     * Linear: the live queue is indexed once, and each row's slot is worked
     * out against it rather than against a list grown one insert at a time.
     */
    fun restorePlanByIdentity(removal: Removal, live: List<String>, currentIndex: Int): List<Pair<Int, Song>> {
        val floor = (currentIndex + 1).coerceIn(0, live.size)
        val firstAt = HashMap<String, Int>(live.size * 2)
        // Anchors only count when upcoming, so only upcoming rows are indexed;
        // the first occurrence wins, as a scan from the playhead would find.
        for (i in live.size - 1 downTo floor) firstAt[live[i]] = i
        val present = live.toHashSet()
        val plan = ArrayList<Pair<Int, Song>>(removal.rows.size)
        var lastSlot = floor
        for (row in removal.rows) {
            val key = identity(row.song)
            if (row.song.queueEntryId != null && key in present) continue
            val restored = plan.size
            // A slot is a position in [live]; the rows already restored sit
            // in front of it, which is what turns it into an insert index.
            val slot = (row.anchor?.let(firstAt::get) ?: (row.originalIndex - restored))
                .coerceIn(floor, live.size)
                .coerceAtLeast(lastSlot)
            lastSlot = slot
            plan += (slot + restored) to row.song
        }
        return plan
    }

    /** The upcoming Next in queue rows, which Clear takes out. */
    fun userQueueIndices(timeline: List<Song>, currentIndex: Int): List<Int> =
        ((currentIndex + 1) until timeline.size).filter { timeline[it].queueTier == QueueTier.USER_QUEUE }

    /** A move of rows into Next in queue: what to remove, where they land, and as what. */
    data class Move(val remove: List<Int>, val insertAt: Int, val songs: List<Song>)

    /**
     * Moves the upcoming rows at [indices] into Next in queue — to its head
     * when [toHead], to its end otherwise — keeping their order and their
     * entry ids, retagged as queued by hand. Null when there is nothing to do:
     * nothing upcoming was picked, or the one row is already where it would go.
     */
    fun moveToUserQueue(timeline: List<Song>, currentIndex: Int, indices: Collection<Int>, toHead: Boolean): Move? {
        val picked = indices.filter { it > currentIndex && it in timeline.indices }.toSortedSet().toList()
        if (picked.isEmpty()) return null
        val pickedSet = picked.toHashSet()
        // Every picked row is after the playhead, so the playhead keeps its index.
        val remaining = timeline.filterIndexed { i, _ -> i !in pickedSet }
        val insertAt = QueueTimeline.findUserQueueInsertionIndex(remaining, currentIndex, isNext = toHead)
        val songs = picked.map { timeline[it].copy(queueTier = QueueTier.USER_QUEUE) }
        // Already there, already queued by hand: the move would change nothing.
        val unchanged = picked.withIndex().all { (k, index) ->
            index == insertAt + k && timeline[index].queueTier == QueueTier.USER_QUEUE
        }
        if (unchanged) return null
        return Move(picked, insertAt, songs)
    }
}
