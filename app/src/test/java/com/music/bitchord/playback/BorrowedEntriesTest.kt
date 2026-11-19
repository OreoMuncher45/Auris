package com.music.bitchord.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A cache entry lent whole to a request that would have resolved a different
 * rendition must never be refilled from that request's upstream.
 */
class BorrowedEntriesTest {

    private val alt = "abcdefghijk#alt2"

    @Test
    fun `an entry nobody borrowed may be filled`() {
        assertTrue(BorrowedEntries().mayFill(alt) { true })
    }

    @Test
    fun `a borrowed entry that lost its tail is not refilled`() {
        val borrowed = BorrowedEntries().apply { lend(alt) }
        assertFalse(borrowed.mayFill(alt) { true })
    }

    @Test
    fun `a settings change that makes the key a request's own keeps the borrower protected`() {
        val borrowed = BorrowedEntries().apply { lend(alt) }
        // A source is ranked above YouTube again: the next key lookup names
        // `#alt2` as its own entry. The earlier borrower is still reading it,
        // and a span is evicted under it.
        val ownKeyNow = alt
        assertFalse(borrowed.mayFill(ownKeyNow) { true })
    }

    @Test
    fun `once the entry is empty there is no file to seam, and it may be filled`() {
        val borrowed = BorrowedEntries().apply { lend(alt) }
        assertTrue(borrowed.mayFill(alt) { false })
        // And the mark is gone, so later fills do not ask again.
        assertTrue(borrowed.mayFill(alt) { error("not asked for an unlent key") })
    }

    @Test
    fun `a discarded entry may be filled again`() {
        val borrowed = BorrowedEntries().apply { lend(alt) }
        borrowed.forget(alt)
        assertTrue(borrowed.mayFill(alt) { true })
    }

    @Test
    fun `an unlent key never reads the cache`() {
        val borrowed = BorrowedEntries().apply { lend(alt) }
        assertTrue(borrowed.mayFill("abcdefghijk") { error("cache read for an unlent key") })
    }
}
