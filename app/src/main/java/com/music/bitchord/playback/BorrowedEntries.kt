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
 * and write it into the middle of the borrowed file. Refusing the fetch ends
 * the read instead; recovery discards the entry and the next open picks a key
 * afresh.
 *
 * The mark is process-wide and outlives the borrow on purpose. Which request is
 * still reading an entry is not visible from here, and a settings change can
 * make the same key a later request's *own* — un-marking it then would strip
 * the protection from the reader still holding the borrow. So the mark only
 * goes when the entry does: [forget] on discard, or once the entry holds no
 * bytes at all, when there is no file left to put a seam in. The cost of
 * keeping it too long is one recoverable refusal and a re-download.
 */
internal class BorrowedEntries {

    private val keys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** [key] has just been lent to a request that is not its own. */
    fun lend(key: String) {
        keys.add(key)
    }

    /**
     * Whether an upstream fetch may write into [key]. [holdsBytes] is asked
     * only for a lent key, so an ordinary fetch costs one set lookup.
     */
    inline fun mayFill(key: String, holdsBytes: (String) -> Boolean): Boolean {
        if (!isLent(key)) return true
        if (holdsBytes(key)) return false
        forget(key)
        return true
    }

    fun isLent(key: String): Boolean = keys.isNotEmpty() && key in keys

    /** The entry [key] is gone or being thrown away; it may be filled again. */
    fun forget(key: String) {
        keys.remove(key)
    }
}
