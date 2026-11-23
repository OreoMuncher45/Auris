package com.music.bitchord.playback.smart

import java.util.concurrent.ConcurrentHashMap

/**
 * The analyses [TrackAnalyzer] keeps in memory, bounded to the newest few.
 *
 * An analysis carries several envelope curves and a vocal mask, and the
 * Automix measures every track it plays, so an unbounded map left an evening's
 * worth of numbers in the heap for tracks nobody was looking at any more.
 * Everything a whole-track pass produces is on disk as well, so a track that
 * comes round again is restored rather than measured a second time.
 *
 * Reads are lock-free: [get] is called several times per tick from the
 * playback thread. Writes and trims take one short lock over a set of at most
 * [maxEntries] + 1 ids, so the recency order and the map never disagree.
 *
 * Recency is the order of writes, not reads: the tracks worth keeping are the
 * ones measured or restored most recently, which are the current and queued
 * ones.
 */
internal class AnalysisResultCache(private val maxEntries: Int) {

    private val results = ConcurrentHashMap<String, TrackAnalysis>()

    /** Oldest first. Guarded by itself. */
    private val order = LinkedHashSet<String>()

    operator fun get(trackId: String): TrackAnalysis? = results[trackId]

    fun containsKey(trackId: String): Boolean = results.containsKey(trackId)

    val size: Int get() = results.size

    /**
     * Stores [analysis] and returns the ids that had to make room for it.
     * An id for which [pinned] is true is never evicted.
     */
    fun put(trackId: String, analysis: TrackAnalysis, pinned: (String) -> Boolean): List<String> =
        synchronized(order) {
            results[trackId] = analysis
            touch(trackId)
            trimLocked(maxEntries, pinned)
        }

    /**
     * Stores [analysis] unless something is already there. Returns the value
     * now cached and the ids evicted to make room.
     */
    fun putIfAbsent(
        trackId: String,
        analysis: TrackAnalysis,
        pinned: (String) -> Boolean,
    ): Pair<TrackAnalysis, List<String>> = synchronized(order) {
        val existing = results.putIfAbsent(trackId, analysis)
        if (existing != null) return existing to emptyList()
        touch(trackId)
        analysis to trimLocked(maxEntries, pinned)
    }

    /** Drops the oldest unpinned results until at most [max] remain. Returns their ids. */
    fun trimTo(max: Int, pinned: (String) -> Boolean): List<String> =
        synchronized(order) { trimLocked(max, pinned) }

    /**
     * Removes [trackId]'s result only if it is still this very [analysis] —
     * by identity: [TrackAnalysis] is a data class, and an equal result
     * measured since is a replacement, not the one being removed. Returns
     * whether it did.
     */
    fun remove(trackId: String, analysis: TrackAnalysis): Boolean = synchronized(order) {
        (results[trackId] === analysis).also { same ->
            if (same) {
                results.remove(trackId)
                order.remove(trackId)
            }
        }
    }

    fun clear() = synchronized(order) {
        results.clear()
        order.clear()
    }

    private fun touch(trackId: String) {
        order.remove(trackId)
        order.add(trackId)
    }

    private fun trimLocked(max: Int, pinned: (String) -> Boolean): List<String> {
        if (results.size <= max) return emptyList()
        val evicted = ArrayList<String>(results.size - max)
        val iterator = order.iterator()
        while (results.size > max && iterator.hasNext()) {
            val trackId = iterator.next()
            // A pass in flight owns its result: taking it away now would have
            // the pass publish over an empty slot and start again.
            if (pinned(trackId)) continue
            iterator.remove()
            results.remove(trackId)
            evicted += trackId
        }
        return evicted
    }
}
