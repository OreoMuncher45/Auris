package com.music.bitchord.playback

import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song

/**
 * Where the next page of an Auto-started folder goes in [queue]: right behind
 * the folder's last queued track, or null when it should go nowhere.
 *
 * Null when the queue no longer holds the folder, and also when the listener
 * has played past it ([currentIndex] is beyond the folder's last track, e.g.
 * into what AutoPlay appended): rows behind that last track are then history,
 * and a page slipped in there would be "upcoming" tracks nobody hears.
 */
internal fun autoContinuationInsertIndex(queue: List<Song>, sourceId: String, currentIndex: Int): Int? {
    val last = queue.indexOfLast { it.queueTier == QueueTier.CONTEXT && it.playbackSourceId == sourceId }
    if (last < 0 || last < currentIndex) return null
    return last + 1
}

/**
 * Whether a playlist change is the installation of an Auto folder's queue,
 * not some other edit — AutoPlay topping up the queue it replaces, say.
 *
 * [expectedEntryId] is the queue entry the folder's queue was built to start
 * on, at [startIndex]; entry ids are minted per build, so only that queue
 * holds it there. [entryIdAt] reads the player's entry at an index. With no
 * entry id to match, any playlist change counts, as it did before.
 */
internal fun isAutoQueueInstalled(
    expectedEntryId: String?,
    startIndex: Int,
    itemCount: Int,
    entryIdAt: (Int) -> String?,
): Boolean {
    if (expectedEntryId == null) return true
    return startIndex in 0 until itemCount && entryIdAt(startIndex) == expectedEntryId
}

/**
 * [resolved] without the rows at [revoked] indices, and where [start] — an
 * index into [resolved] — lands once they are gone (clamped, so a revoked
 * start falls to the row that took its place).
 *
 * For Auto rows accepted for one account while a later item was still being
 * resolved: if the account changed meanwhile, those rows go, and the rows
 * that belong to no account stay.
 */
internal fun <T> withoutRevoked(resolved: List<T>, revoked: Collection<Int>, start: Int): Pair<List<T>, Int> {
    if (revoked.isEmpty()) return resolved to start
    val kept = ArrayList<T>(resolved.size - revoked.size)
    var keptStart = 0
    resolved.forEachIndexed { index, item ->
        if (index < start && index !in revoked) keptStart++
        if (index !in revoked) kept += item
    }
    return kept to keptStart
}
