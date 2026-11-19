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

    private val keys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** [key] has just been lent to a request that is not its own. */
    fun lend(key: String) {
        keys.add(key)
    }

    /**
     * Whether an upstream fetch may write into [key]. The emptiness check
     * keeps an ordinary fetch, with nothing lent, to a field read.
     */
    fun mayFill(key: String): Boolean = keys.isEmpty() || key !in keys

    /** The entry [key] has been removed; it may be filled again. */
    fun forget(key: String) {
        keys.remove(key)
    }
}
