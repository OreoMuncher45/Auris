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
 * [resolved] without the rows at [revoked] indices, and where [start] — an
 * index into [resolved] — lands once they are gone: a revoked start falls to
 * the row that took its place, or to the last kept row when none did. Empty
 * when nothing survives; the caller then has no row to start.
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
    return kept to if (kept.isEmpty()) 0 else keptStart.coerceAtMost(kept.lastIndex)
}

/**
 * An Auto queue handed to Media3 and not yet installed: the entry it starts
 * on, its length, and the session it was built for. Media3 installs it on the
 * main thread some time after the build, through the session player's
 * setMediaItems.
 */
internal class PendingAutoInstall(
    val startEntryId: String?,
    val startIndex: Int,
    val size: Int,
    val owner: ListenerStamp,
    /** Tells the controller that asked for it that it was refused. */
    val onRefused: () -> Unit = {},
) {
    /** Completed with its entry ids once a session player has installed it. */
    val installed = kotlinx.coroutines.CompletableDeferred<HashSet<String>?>()
}

/**
 * For a queue of [size] (its entry ids by index from [entryAt]) arriving to be
 * installed while [pending] waits: null when it is not the pending queue (an
 * edit to the queue it replaces, say: AutoPlay topping that one up),
 * true when it is and the session it was built for is no longer [now] (an
 * account switch, even away and back, landed in between), so it must not be
 * installed, and false when it is and may be.
 */
internal fun staleAutoInstall(pending: PendingAutoInstall?, size: Int, entryAt: (Int) -> String?, now: ListenerStamp): Boolean? {
    if (pending == null || size != pending.size || entryAt(pending.startIndex) != pending.startEntryId) return null
    return now != pending.owner
}
