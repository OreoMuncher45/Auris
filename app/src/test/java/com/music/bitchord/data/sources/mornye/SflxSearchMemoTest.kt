package com.music.bitchord.data.sources.mornye

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The questions the host has already asked, and the ones running now.
 *
 * A search is not free: the extension walks its own catalogue in JavaScript
 * and answers with kilobytes of JSON. On a device log, one track's worth of
 * looking up ran the extension's search eight times in twelve seconds — the
 * same question, eight times, from the search screen, the row warming, the
 * playback resolve, the upgrade and the next-track preparation.
 *
 * So the memo keeps an answer for a moment and makes a question asked while
 * another is running wait for that one. These are the four behaviours that
 * has to have, and the two it must not lose: a failure is not an answer, and
 * a caller who goes away does not take the answer with it.
 */
class SflxSearchMemoTest {

    /** A clock the test moves by hand. */
    private class Clock(var now: Long = 1_000L) {
        fun advance(ms: Long) { now += ms }
        fun read(): Long = now
    }

    @Test
    fun `the same question asked twice at once is asked once`() = runBlocking {
        val memo = SflxSearchMemo<String>(ttlMs = 120_000)
        val asked = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()

        val first = launch {
            memo.once("tidal|heavy moon|5") {
                asked.incrementAndGet()
                gate.await()
                Result.success("Heavy Moon")
            }
        }
        // The second caller arrives while the first question is still running.
        val second = async {
            memo.once("tidal|heavy moon|5") {
                asked.incrementAndGet()
                Result.success("something else")
            }
        }
        delay(50)
        gate.complete(Unit)
        first.join()
        assertEquals("Heavy Moon", second.await().getOrNull())
        assertEquals("the extension was asked once", 1, asked.get())
    }

    @Test
    fun `an answer is kept for the next caller`() = runBlocking {
        val memo = SflxSearchMemo<String>(ttlMs = 120_000)
        val asked = AtomicInteger(0)

        val first = memo.once("tidal|heavy moon|5") {
            asked.incrementAndGet()
            Result.success("Heavy Moon")
        }.getOrNull()
        val second = memo.once("tidal|heavy moon|5") {
            asked.incrementAndGet()
            Result.success("something else")
        }.getOrNull()

        assertEquals("Heavy Moon", first)
        assertEquals("Heavy Moon", second)
        assertEquals("the extension was not asked again", 1, asked.get())
    }

    @Test
    fun `an answer goes stale`() = runBlocking {
        val clock = Clock()
        val memo = SflxSearchMemo<String>(ttlMs = 120_000, clock = clock::read)
        val asked = AtomicInteger(0)

        memo.once("tidal|heavy moon|5") {
            asked.incrementAndGet()
            Result.success("Heavy Moon")
        }.getOrNull()

        // Still fresh.
        clock.advance(119_000)
        memo.once("tidal|heavy moon|5") {
            asked.incrementAndGet()
            Result.success("something else")
        }.getOrNull()
        assertEquals(1, asked.get())

        // Past its time: the catalogue may have changed.
        clock.advance(2_000)
        memo.once("tidal|heavy moon|5") {
            asked.incrementAndGet()
            Result.success("something else")
        }.getOrNull()
        assertEquals("the stale answer was not used", 2, asked.get())
    }

    @Test
    fun `forgetting one extension's answers leaves the others alone`() = runBlocking {
        val memo = SflxSearchMemo<String>(ttlMs = 120_000)
        val asked = AtomicInteger(0)

        memo.once("tidal|heavy moon|5") {
            asked.incrementAndGet()
            Result.success("tidal")
        }.getOrNull()
        memo.once("qobuz|heavy moon|5") {
            asked.incrementAndGet()
            Result.success("qobuz")
        }.getOrNull()

        memo.forgetMatching("tidal|")

        assertEquals(null, memo.cached("tidal|heavy moon|5"))
        assertEquals("qobuz", memo.cached("qobuz|heavy moon|5"))

        memo.once("tidal|heavy moon|5") {
            asked.incrementAndGet()
            Result.success("tidal again")
        }.getOrNull()
        assertEquals("only the forgotten one was asked again", 3, asked.get())
    }

    @Test
    fun `a failure is not an answer`() = runBlocking {
        val memo = SflxSearchMemo<String>(ttlMs = 120_000)
        val asked = AtomicInteger(0)

        val failed = memo.once("tidal|heavy moon|5") {
            asked.incrementAndGet()
            Result.failure(SflxException("the extension failed"))
        }
        assertTrue(failed.isFailure)
        assertEquals(null, memo.cached("tidal|heavy moon|5"))

        // Asked again: a failure says nothing about what the answer is.
        val second = memo.once("tidal|heavy moon|5") {
            asked.incrementAndGet()
            Result.success("Heavy Moon")
        }
        assertEquals("Heavy Moon", second.getOrNull())
        assertEquals(2, asked.get())
    }

    @Test
    fun `a caller who goes away does not take the answer with it`() = runBlocking {
        val memo = SflxSearchMemo<String>(ttlMs = 120_000)
        val asked = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()

        val first = launch {
            memo.once("tidal|heavy moon|5") {
                asked.incrementAndGet()
                gate.await()
                Result.success("Heavy Moon")
            }
        }
        delay(50)
        // The first caller walks away — the screen was closed, the track was
        // skipped. The question itself does not: it runs in the host's own
        // scope, because somebody else may be waiting for the same answer.
        first.cancel()
        gate.complete(Unit)
        delay(50)

        val second = memo.once("tidal|heavy moon|5") {
            asked.incrementAndGet()
            Result.success("something else")
        }
        assertEquals("Heavy Moon", second.getOrNull())
        assertEquals("the extension was asked once", 1, asked.get())
    }

    @Test
    fun `an answer is taken from the cache without asking`() = runBlocking {
        val memo = SflxSearchMemo<String>(ttlMs = 120_000)
        val asked = AtomicInteger(0)

        memo.once("tidal|heavy moon|5") {
            asked.incrementAndGet()
            Result.success("Heavy Moon")
        }.getOrNull()

        assertEquals("Heavy Moon", memo.cached("tidal|heavy moon|5"))
        assertNull(memo.cached("tidal|another track|5"))
        assertEquals(1, asked.get())
    }
}
