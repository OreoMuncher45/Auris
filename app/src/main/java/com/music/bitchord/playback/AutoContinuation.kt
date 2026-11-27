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

/**
 * The queue resolved from the items an Auto controller handed over, built
 * row by row, noting which rows were read for the account session [owner]:
 * search results, playlists, browse folders and the account's own folders.
 *
 * Any later item can suspend on the network, and the account can change
 * meanwhile, even away and back. [finish] therefore checks once everything is
 * resolved: if [owner] is no longer the session, every row read for it goes,
 * and the rows that belong to no account (downloads, music on the device,
 * items the controller handed over complete) stay.
 */
internal class SessionRows<T>(private val owner: ListenerStamp) {
    private val rows = ArrayList<T>()
    private val bound = HashSet<Int>()
    private val boundRequests = HashSet<Int>()

    /** How many rows so far: the index the next one gets. */
    val size: Int get() = rows.size

    /** A row that belongs to no account. */
    fun add(row: T) {
        rows += row
    }

    /** Rows that belong to no account. */
    fun addAll(rows: Collection<T>) {
        this.rows.addAll(rows)
    }

    /**
     * Rows read for [owner]'s account from the handed-over item at [request].
     * The request is noted even when it read nothing, so a stale one is not
     * handed back as it came either (see [staleRequests]).
     */
    fun addForSession(request: Int, rows: Collection<T>) {
        boundRequests += request
        rows.forEach {
            bound += this.rows.size
            this.rows += it
        }
    }

    /**
     * The rows, and where [start] (an index into them) lands, for the session
     * [now]: all of them while it is still [owner], else without those read
     * for [owner] (see [withoutRevoked]).
     */
    fun finish(start: Int, now: ListenerStamp): Pair<List<T>, Int> =
        if (now == owner) rows to start else withoutRevoked(rows, bound, start)

    /** The handed-over items read for [owner], when [now] is another session; empty while it is not. */
    fun staleRequests(now: ListenerStamp): Set<Int> = if (now == owner) emptySet() else boundRequests
}

/**
 * Completes [this] with [rows] while the session they were read for is still
 * the current one, and with [none] otherwise, the check and the completion
 * done as one step inside [whileOwner]: given the completion, it runs it only
 * while that session is current, holding off every account switch until it
 * returns (see [Innertube.whileListener][com.music.bitchord.data.innertube.Innertube.whileListener]),
 * and answers null without running it when another session is current.
 *
 * A recipient's listeners on [this] that run on a direct executor run inside
 * that step: Media3 delivers a library result to its browser that way, so
 * rows read for one session are either handed over before a switch or not at
 * all, never after one. Media3 attaches them in the application-thread task
 * that asked for the result, so complete [this] in a later one.
 */
internal fun <V> com.google.common.util.concurrent.SettableFuture<V>.completeWhile(
    whileOwner: (complete: () -> Boolean) -> Boolean?,
    rows: V,
    none: V,
) {
    whileOwner { set(rows) } ?: set(none)
}
