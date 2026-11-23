package com.music.bitchord.playback

import androidx.media3.common.MediaItem
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
internal fun autoContinuationInsertIndex(
    queue: List<Song>,
    sourceId: String,
    currentIndex: Int,
    /**
     * The queue entries the folder was installed as, and those this
     * continuation added. A new queue carrying the same source id (the same
     * playlist started again from the phone) mints new entry ids, so its rows
     * are not this folder's. Null matches on the source id alone.
     */
    folderEntries: Set<String>? = null,
): Int? {
    val last = queue.indexOfLast {
        it.queueTier == QueueTier.CONTEXT && it.playbackSourceId == sourceId &&
            (folderEntries == null || it.queueEntryId in folderEntries)
    }
    if (last < 0) return null
    // Play Next entries queued right behind the folder stay ahead of its next
    // page: the listener asked for them next.
    var end = last + 1
    while (end < queue.size && queue[end].queueTier == QueueTier.USER_QUEUE) end++
    if (currentIndex >= end) return null
    return end
}

/** A queue row's identity: its entry id, minted per queue build, or its media id without one. */
internal fun MediaItem.queueKey(): String = queueEntryId ?: mediaId

/** One page an Auto folder's continuation added: its entries, right after [anchor]'s. */
internal class ContinuationInsert(val anchor: String, val entries: List<String>)

/**
 * Whether [active] is [original] with nothing changed but the folder pages in
 * [inserts] added where the continuation put them. Entries are queue entry
 * keys, AutoPlay's left out. Moving or removing an added row is an edit like
 * any other, which comparing the queue with the added rows taken out could
 * not see.
 */
internal fun queueOnlyGrewByContinuation(
    original: List<String>,
    active: List<String>,
    inserts: List<ContinuationInsert>,
): Boolean {
    if (inserts.isEmpty()) return active == original
    val expected = ArrayList<String>(original.size + inserts.sumOf { it.entries.size })
    expected.addAll(original)
    for (insert in inserts) {
        val at = expected.indexOf(insert.anchor)
        if (at < 0) return false
        expected.addAll(at + 1, insert.entries)
    }
    return expected == active
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
