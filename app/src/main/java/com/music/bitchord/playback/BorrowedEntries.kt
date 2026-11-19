package com.music.bitchord.playback

import java.util.concurrent.ConcurrentHashMap

/**
 * Cache entries [AudioCache] lent whole to a request that would have resolved
 * a different rendition — a plain request reading a JioSaavn AAC copy filed
 * under `#alt2`, say — and which no upstream may therefore write into.
 *
 * The entry was complete when it was lent, but nothing pins it: the evictor
 * can drop its unread tail mid-play. CacheDataSource would then fill the hole
 * from the borrowing request's own upstream, which can resolve something else,
 * and write it into the middle of the borrowed file — or hand those bytes to a
 * reader already part-way through the borrowed rendition. Refusing the fetch
 * ends the read instead; recovery discards the entry and the next open picks a
 * key afresh.
 *
 * Used at two scopes. [AudioCache] keeps one for the whole process, which
 * protects the *file*: its mark goes only when [AudioCache.discard] removes the
 * entry, never because a later lookup named the key as its own or because the
 * entry emptied — which request is still reading cannot be seen from there.
 * Each playback request keeps another for its own lifetime, which protects the
 * *stream*: that request never refills a key it was lent, even once the file
 * mark has gone. Keeping a mark too long costs one recoverable refusal and a
 * re-download.
 */
internal class BorrowedEntries {

    /** Each lent key, to the token of the copy that was lent ([NO_TOKEN] when not tracked). */
    private val keys = ConcurrentHashMap<String, Long>()

    /**
     * [key] has just been lent to a request that is not its own. A request's
     * own set records [token], which stamps the copy it was lent — see
     * [tokenOf]. The first token recorded for a key stands for the request's
     * whole life.
     */
    fun lend(key: String, token: Long = NO_TOKEN) {
        keys.putIfAbsent(key, token)
    }

    /**
     * The token of the copy of [key] this request was lent, or null when it
     * was not lent one. A copy discarded and refilled by another request
     * carries a different token (or none), so a borrower can tell its bytes
     * are no longer the ones it started reading.
     */
    fun tokenOf(key: String): Long? = if (keys.isEmpty()) null else keys[key]

    /**
     * Whether an upstream fetch may write into [key]. The emptiness check
     * keeps an ordinary fetch, with nothing lent, to a field read.
     */
    fun mayFill(key: String): Boolean = keys.isEmpty() || !keys.containsKey(key)

    /**
     * The key lent for [videoId], if any — for a per-request set, which holds
     * a key or two, so the scan is free. The plain key or one of its `#`
     * siblings.
     */
    fun lentFor(videoId: String): String? {
        if (keys.isEmpty()) return null
        return keys.keys.firstOrNull { it == videoId || it.startsWith("$videoId#") }
    }

    /** The entry [key] has been removed; it may be filled again. */
    fun forget(key: String) {
        keys.remove(key)
    }

    companion object {
        const val NO_TOKEN = 0L
    }
}
