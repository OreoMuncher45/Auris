package com.music.bitchord.playback

import androidx.media3.common.Player
import com.music.bitchord.data.model.Song

/**
 * The queue screen's edits, carried out on the player as plain removes and
 * inserts. The plans are [QueueEdits]'; this only applies them.
 *
 * [timeline] is the queue as the screen last saw it. It is used only when it
 * still matches the player's length; otherwise the player is read afresh.
 */
private fun Player.liveTimeline(timeline: List<Song>?): List<Song> =
    timeline?.takeIf { it.size == mediaItemCount }
        ?: (0 until mediaItemCount).map { getMediaItemAt(it).toSong() }

/** One call per contiguous run, last run first, so earlier indices stay put. */
private fun Player.removeRuns(indices: Collection<Int>) {
    for (run in QueueEdits.removalRuns(indices)) {
        if (run.first == run.last) removeMediaItem(run.first) else removeMediaItems(run.first, run.last + 1)
    }
}

/**
 * Removes the upcoming rows at [indices] and returns what Undo needs to put
 * them back, or null when none of them could be removed.
 */
fun Player.removeQueueRows(indices: Collection<Int>, timeline: List<Song>? = null): QueueEdits.Removal? {
    val current = currentMediaItemIndex
    val valid = indices.filter { it > current && it < mediaItemCount }
    if (valid.isEmpty()) return null
    val removal = QueueEdits.capture(liveTimeline(timeline), valid)
    removeRuns(valid)
    return removal
}

/** Clear, undoably: the upcoming Next in queue rows, and nothing else. */
fun Player.clearUserQueueRows(timeline: List<Song>? = null): QueueEdits.Removal? {
    val live = liveTimeline(timeline)
    val indices = QueueEdits.userQueueIndices(live, currentMediaItemIndex)
    if (indices.isEmpty()) return null
    val removal = QueueEdits.capture(live, indices)
    removeRuns(indices)
    return removal
}

/** Undo: the removed rows back where they were, against the queue as it is now. */
fun Player.restoreQueueRows(removal: QueueEdits.Removal) {
    val plan = QueueEdits.restorePlan(removal, liveTimeline(null), currentMediaItemIndex)
    for ((at, song) in plan) addMediaItem(at.coerceIn(0, mediaItemCount), song.toMediaItem())
}

/**
 * Moves the upcoming rows at [indices] into Next in queue — the same entries,
 * not copies — to its head when [toHead], to its end otherwise.
 * False when there was nothing to move.
 */
fun Player.moveQueueRowsToUser(indices: Collection<Int>, toHead: Boolean, timeline: List<Song>? = null): Boolean {
    val move = QueueEdits.moveToUserQueue(liveTimeline(timeline), currentMediaItemIndex, indices, toHead)
        ?: return false
    removeRuns(move.remove)
    addMediaItems(move.insertAt.coerceIn(0, mediaItemCount), move.songs.map { it.toMediaItem() })
    return true
}
