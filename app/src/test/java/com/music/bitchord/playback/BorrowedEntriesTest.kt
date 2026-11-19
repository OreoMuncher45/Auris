package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A cache entry lent whole to a request that would have resolved a different
 * rendition must never be refilled from that request's upstream, for as long
 * as that request lives.
 */
class BorrowedEntriesTest {

    private val alt = "abcdefghijk#alt2"

    /** The process-wide marks (the file) and one playback request's own (the stream). */
    private val file = BorrowedEntries()
    private val request = BorrowedEntries()

    /** What the two guards in AudioCache together allow. */
    private fun requestMayFill(key: String) = file.mayFill(key) && request.mayFill(key)

    private fun borrow(key: String) {
        file.lend(key)
        request.lend(key)
    }

    @Test
    fun `an entry nobody borrowed may be filled`() {
        assertTrue(requestMayFill(alt))
    }

    @Test
    fun `a borrowed entry that lost its tail is not refilled`() {
        borrow(alt)
        assertFalse(requestMayFill(alt))
    }

    @Test
    fun `a settings change that makes the key another request's own keeps the borrower protected`() {
        borrow(alt)
        // A source is ranked above YouTube again: a later lookup names `#alt2`
        // as its own entry. That lends nothing, and takes nothing back.
        val later = BorrowedEntries()
        assertFalse("the file stays read-only", file.mayFill(alt) && later.mayFill(alt))
        assertFalse("the borrower stays read-only", requestMayFill(alt))
    }

    @Test
    fun `a borrower whose last span was evicted is still not refilled`() {
        borrow(alt)
        // Every span evicted: the file is empty, but this request has already
        // read part of the borrowed rendition.
        assertFalse(requestMayFill(alt))
    }

    @Test
    fun `a borrower whose entry was discarded is still not refilled`() {
        borrow(alt)
        file.forget(alt)
        assertFalse(requestMayFill(alt))
    }

    @Test
    fun `a new request may fill a discarded entry again`() {
        borrow(alt)
        file.forget(alt)
        assertTrue(file.mayFill(alt) && BorrowedEntries().mayFill(alt))
    }

    @Test
    fun `other keys are unaffected`() {
        borrow(alt)
        assertTrue(requestMayFill("abcdefghijk"))
    }

    @Test
    fun `a reopen stays on the lent sibling even when another complete one exists`() {
        val id = "abcdefghijk"
        val noAtmos = "$id#alt2-noatmos"
        borrow(alt)
        // `#alt2` lost a span; `#alt2-noatmos` is still complete. A seek
        // reopens at a nonzero position: the key factory asks the request
        // first, and gets `#alt2` back rather than a fresh sibling choice…
        assertEquals(alt, request.lentFor(id))
        // …and the hole in it is refused rather than refilled.
        assertFalse(requestMayFill(alt))
        // A request that borrowed nothing gets no answer, so the key factory
        // makes its ordinary choice.
        assertNull(BorrowedEntries().lentFor(id))
        assertTrue(requestMayFill(noAtmos))
    }

    @Test
    fun `lentFor matches only that video's keys`() {
        borrow(alt)
        assertNull(request.lentFor("abcdefghij"))
        assertNull(request.lentFor("zzzzzzzzzzz"))
        assertEquals(alt, request.lentFor("abcdefghijk"))
    }
}
