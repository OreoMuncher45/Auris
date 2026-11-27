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
 *
 * [owner] is null for a folder that belongs to no account (downloads, music
 * on the device): it stays playable across a sign-in or sign-out.
 */
internal class PendingAutoInstall(
    val startEntryId: String?,
    val startIndex: Int,
    val size: Int,
    val owner: ListenerStamp?,
    /** Tells the controller that asked for it that it was refused. */
    val onRefused: () -> Unit = {},
) {
    /** Completed with its entry ids once a session player has installed it, or null if it never will be. */
    val installed = kotlinx.coroutines.CompletableDeferred<HashSet<String>?>()

    /** Whether a queue of [size] (its entry ids by index from [entryAt]) is this one. */
    fun matches(size: Int, entryAt: (Int) -> String?): Boolean =
        size == this.size && entryAt(startIndex) == startEntryId
}

/**
 * For a queue of [size] (its entry ids by index from [entryAt]) arriving to be
 * installed while [pending] waits: null when it is not the pending queue (an
 * edit to the queue it replaces, say: AutoPlay topping that one up),
 * true when it is and the session it was built for is no longer [now] (an
 * account switch, even away and back, landed in between), so it must not be
 * installed, and false when it is and may be. A queue of no account is never
 * stale.
 */
internal fun staleAutoInstall(pending: PendingAutoInstall?, size: Int, entryAt: (Int) -> String?, now: ListenerStamp): Boolean? {
    if (pending == null || !pending.matches(size, entryAt)) return null
    return pending.owner != null && now != pending.owner
}

/**
 * Every Auto queue built and handed to Media3 but not installed or refused
 * yet, each with its own record: a second build landing before the first is
 * installed must not leave the first unrecorded, or it would install as
 * nobody's.
 *
 * A record goes when its queue is installed ([installedOrNull]), refused
 * ([refused]), or replaced by a newer build starting on the same entry; past
 * [capacity] the oldest goes too. A record that goes without an install has
 * its [PendingAutoInstall.installed] completed with null, so nothing waits on
 * it. Main thread only.
 */
internal class PendingAutoInstalls(private val capacity: Int = 4) {
    private val records = ArrayList<PendingAutoInstall>()

    val size: Int get() = records.size

    fun add(pending: PendingAutoInstall) {
        records.removeAll { old ->
            (old.startEntryId != null && old.startEntryId == pending.startEntryId).also { if (it) old.installed.complete(null) }
        }
        records += pending
        while (records.size > capacity) records.removeAt(0).installed.complete(null)
    }

    /** The record [size] and [entryAt] describe, if one is waiting. */
    fun matching(size: Int, entryAt: (Int) -> String?): PendingAutoInstall? =
        records.lastOrNull { it.matches(size, entryAt) }

    /** Whether the queue arriving is a recorded one whose session is no longer [now]. */
    fun refuses(size: Int, entryAt: (Int) -> String?, now: ListenerStamp): PendingAutoInstall? =
        matching(size, entryAt)?.takeIf { staleAutoInstall(it, size, entryAt, now) == true }

    /**
     * The record whose folder the running continuation grows, if any: set
     * when a built queue starts its continuation, cleared with it.
     */
    var following: PendingAutoInstall? = null

    /**
     * Takes [pending] out as refused. True when the continuation running now
     * is its own, so the caller ends it; a refused queue built before a later
     * one must not stop the later one's continuation.
     */
    fun refused(pending: PendingAutoInstall): Boolean {
        records.remove(pending)
        pending.installed.complete(null)
        return (following === pending).also { if (it) following = null }
    }

    /** The record for a queue just installed, taken out; null when it was not a recorded one. */
    fun installedOrNull(size: Int, entryAt: (Int) -> String?): PendingAutoInstall? =
        matching(size, entryAt)?.also { records.remove(it) }

    fun clear() {
        records.forEach { it.installed.complete(null) }
        records.clear()
    }
}

/**
 * The queue resolved from the items an Auto controller handed over, built
 * row by row, noting which rows were read for the account session it was
 * asked under: search results, playlists, browse folders, the account's own
 * folders, and tracks found in its cache.
 *
 * Any later item can suspend on the network, and the account can change
 * meanwhile, even away and back, and again before the answer is handed over.
 * The answer is therefore built both ways ([finish]): with every row while
 * that session is still current, and, once it has ended, without the rows
 * read for it, a cache hit falling back to the item the controller handed
 * over, and keeping the rows that belong to no account (downloads, music on
 * the device, items the controller handed over complete).
 */
internal class SessionRows<T> {
    private val rows = ArrayList<T>()
    private val bound = HashSet<Int>()
    private val boundRequests = HashSet<Int>()
    private val otherwise = HashMap<Int, T>()

    /** How many rows so far: the index the next one gets. */
    val size: Int get() = rows.size

    /** Whether any row or request was read for the session. */
    val anyBound: Boolean get() = bound.isNotEmpty() || boundRequests.isNotEmpty()

    /** A row that belongs to no account. */
    fun add(row: T) {
        rows += row
    }

    /** Rows that belong to no account. */
    fun addAll(rows: Collection<T>) {
        this.rows.addAll(rows)
    }

    /**
     * Rows read for the session from the handed-over item at [request].
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
     * A row whose details came from what was read for the session (its cache)
     * for an item the controller named by id alone: [otherwise], built from
     * the item itself, takes its place once that session has ended.
     */
    fun addForSession(row: T, otherwise: T) {
        this.otherwise[rows.size] = otherwise
        bound += rows.size
        rows += row
    }

    /**
     * The rows, and where [start] (an index into them) lands: all of them
     * while the session is [current], else with every row read for it
     * replaced by its fallback or, without one, gone (see [withoutRevoked]).
     */
    fun finish(start: Int, current: Boolean): Pair<List<T>, Int> {
        if (current) return rows to start
        val replaced = rows.mapIndexed { index, row -> otherwise[index] ?: row }
        return withoutRevoked(replaced, bound - otherwise.keys, start)
    }

    /** The handed-over items read for the session, once it has ended; empty while it is [current]. */
    fun staleRequests(current: Boolean): Set<Int> = if (current) emptySet() else boundRequests
}

/**
 * One Auto answer as read: [value]; whether any of it was read for the
 * account session it was asked under ([bound]); and what to hand over
 * instead once that session has ended, [stale], or null to fail the request
 * so Media3 neither shows nor installs nor plays anything for it.
 */
internal class SessionAnswer<V>(
    val value: V,
    val bound: Boolean,
    val stale: V? = null,
    /** Runs, on the delivering thread, when the session has ended and [value] is not handed over. */
    val onStale: () -> Unit = {},
) {
    /** The same answer in another shape. */
    fun <R> map(transform: (V) -> R): SessionAnswer<R> = SessionAnswer(transform(value), bound, stale?.let(transform), onStale)
}

/** Why an Auto request read for one session was not answered under another. */
internal class StaleSessionException : IllegalStateException("the account changed before the answer was handed over")

/**
 * Completes [this] with [answer], the session check and the completion done
 * as one step inside [whileOwner] when the answer was read for a session:
 * given the completion, it runs it only while that session is current,
 * holding off every account switch until it returns (see
 * [Innertube.whileListener][com.music.bitchord.data.innertube.Innertube.whileListener]),
 * and answers null without running it when another session is current. Then
 * [SessionAnswer.stale] is handed over instead, or the request fails.
 *
 * A recipient's listeners on [this] that run on a direct executor, or post to
 * the thread already running, run inside that step. Media3 hands a library
 * result to its browser that way, and installs a queue it asked for the
 * same way, for Media3 and legacy controllers alike: rows read for one
 * session are handed over, or installed, before a switch or not at all.
 * Media3 attaches them in the application-thread task that asked for the
 * answer, so complete [this] on that thread, in a later task.
 */
internal fun <V> com.google.common.util.concurrent.SettableFuture<V>.deliver(
    answer: SessionAnswer<V>,
    whileOwner: (complete: () -> Boolean) -> Boolean?,
) {
    if (!answer.bound) {
        set(answer.value)
        return
    }
    if (whileOwner { set(answer.value) } != null) return
    answer.onStale()
    val stale = answer.stale
    if (stale != null) set(stale) else setException(StaleSessionException())
}
