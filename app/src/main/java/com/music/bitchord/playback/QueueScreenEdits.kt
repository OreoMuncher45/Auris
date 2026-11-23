package com.music.bitchord.playback

import androidx.media3.common.Player
import com.music.bitchord.data.model.Song

/**
 * The queue screen's edits, carried out on the player as plain removes and
 * inserts. The plans are [QueueEdits]'; this only applies them.
 *
 * Every edit plans against the player's queue as it is at that moment, never
 * the screen's copy of it: AutoPlay, a crossfade handoff or an Auto
 * continuation can change the queue between the screen drawing a row and the
 * listener acting on it, even to a queue of the same length. The screen's rows
 * are found again by entry identity ([QueueEdits.resolve]); a row that is gone
 * is left alone rather than mistaken for whatever now sits at its index.
 */

/**
 * The player's queue now. [known] (the screen's copy) only saves rebuilding a
 * [Song] for a row whose entry and tier it already has; it never decides
 * which rows there are or where they sit.
 */
private fun Player.liveTimeline(known: List<Song>?): List<Song> {
    val count = mediaItemCount
    val cache = known?.takeIf { it.isNotEmpty() }?.let { songs ->
        HashMap<String, Song>(songs.size * 2).apply { for (s in songs) put(QueueEdits.identity(s), s) }
    }
    return List(count) { i ->
        val item = getMediaItemAt(i)
        val cached = cache?.get(QueueEdits.identity(item.mediaId, item.queueEntryId))
        if (cached != null && cached.queueTier == item.queueTier) cached else item.toSong()
    }
}

/** The player's queue now, as identities only: all that Undo needs to place rows. */
private fun Player.liveIdentities(): List<String> =
    List(mediaItemCount) { i -> getMediaItemAt(i).let { QueueEdits.identity(it.mediaId, it.queueEntryId) } }

/** One call per contiguous run, last run first, so earlier indices stay put. */
private fun Player.removeRuns(indices: Collection<Int>) {
    for (run in QueueEdits.removalRuns(indices)) {
        if (run.first == run.last) removeMediaItem(run.first) else removeMediaItems(run.first, run.last + 1)
    }
}

/**
 * Removes the upcoming rows the screen showed as [targets] and returns what
 * Undo needs to put them back, or null when none of them is still upcoming.
 */
fun Player.removeQueueRows(targets: List<QueueEdits.Target>, known: List<Song>? = null): QueueEdits.Removal? {
    if (targets.isEmpty()) return null
    val live = liveTimeline(known)
    val current = currentMediaItemIndex
    val valid = QueueEdits.resolve(targets, live).filter { it > current }
    if (valid.isEmpty()) return null
    val removal = QueueEdits.capture(live, valid)
    removeRuns(valid)
    return removal
}

/** Clear, undoably: the upcoming Next in queue rows, and nothing else. */
fun Player.clearUserQueueRows(known: List<Song>? = null): QueueEdits.Removal? {
    val live = liveTimeline(known)
    val indices = QueueEdits.userQueueIndices(live, currentMediaItemIndex)
    if (indices.isEmpty()) return null
    val removal = QueueEdits.capture(live, indices)
    removeRuns(indices)
    return removal
}

/**
 * Undo: the removed rows back where they were, against the queue as it is
 * now, placed by the identity of the rows that followed them. Consecutive
 * rows go in as one insert.
 */
fun Player.restoreQueueRows(removal: QueueEdits.Removal) {
    val plan = QueueEdits.restorePlanByIdentity(removal, liveIdentities(), currentMediaItemIndex)
    var k = 0
    while (k < plan.size) {
        var end = k + 1
        while (end < plan.size && plan[end].first == plan[end - 1].first + 1) end++
        val at = plan[k].first.coerceIn(0, mediaItemCount)
        if (end - k == 1) {
            addMediaItem(at, plan[k].second.toMediaItem())
        } else {
            addMediaItems(at, plan.subList(k, end).map { it.second.toMediaItem() })
        }
        k = end
    }
}

/**
 * Moves the upcoming rows the screen showed as [targets] into Next in queue —
 * the same entries, not copies — to its head when [toHead], to its end
 * otherwise. False when there was nothing to move.
 */
fun Player.moveQueueRowsToUser(
    targets: List<QueueEdits.Target>,
    toHead: Boolean,
    known: List<Song>? = null,
): Boolean {
    if (targets.isEmpty()) return false
    val live = liveTimeline(known)
    val current = currentMediaItemIndex
    val move = QueueEdits.moveToUserQueue(live, current, QueueEdits.resolve(targets, live), toHead)
        ?: return false
    removeRuns(move.remove)
    addMediaItems(move.insertAt.coerceIn(0, mediaItemCount), move.songs.map { it.toMediaItem() })
    return true
}
