package com.music.bitchord.data.sources.mornye

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
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
    fun `an invalidation fences the questions still running`() = runBlocking {
        // A reinstall or a settings change while the old script is still
        // answering: a caller after it must ask the new one, and the old
        // answer must not be kept for the cache's lifetime.
        val memo = SflxSearchMemo<String>(ttlMs = 120_000)
        val asked = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()

        val before = async {
            memo.once("amazon|stream|x") {
                asked.incrementAndGet()
                started.complete(Unit)
                gate.await()
                Result.success("old script")
            }
        }
        started.await()
        memo.forgetMatching("amazon|")
        val after = memo.once("amazon|stream|x") {
            asked.incrementAndGet()
            Result.success("new script")
        }
        gate.complete(Unit)

        assertEquals("the caller after the change asked afresh", "new script", after.getOrNull())
        assertEquals("the caller before it still hears its answer", "old script", before.await().getOrNull())
        assertEquals(2, asked.get())
        delay(50)
        assertEquals("the old answer was not kept over the new one", "new script", memo.cached("amazon|stream|x"))
    }

    @Test
    fun `a caller that straddles an invalidation is neither joined nor kept`() = runBlocking {
        // Missed the cache, paused, and reached the board after an
        // invalidation: there was no flight for the invalidation to fence.
        val memo = SflxSearchMemo<String>(ttlMs = 120_000)
        val asked = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        memo.beforeRegister = {
            memo.beforeRegister = null
            memo.forgetMatching("amazon|")
        }

        val straddler = async {
            memo.once("amazon|stream|x") {
                asked.incrementAndGet()
                started.complete(Unit)
                gate.await()
                Result.success("asked before the change")
            }
        }
        started.await()
        // Bounded: a later caller that wrongly joined the straddler would wait
        // on its gate forever, and a regression should fail, not hang.
        val later = try {
            kotlinx.coroutines.withTimeout(5_000) {
                memo.once("amazon|stream|x") {
                    asked.incrementAndGet()
                    Result.success("asked after the change")
                }
            }
        } finally {
            gate.complete(Unit)
        }

        assertEquals("the later caller did not join the straddler", "asked after the change", later.getOrNull())
        assertEquals("asked before the change", straddler.await().getOrNull())
        assertEquals(2, asked.get())
        assertEquals("the straddler's answer was not kept", "asked after the change", memo.cached("amazon|stream|x"))
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

    /**
     * The test above, without its luck. A finished question has to be off
     * the board before its caller hears the answer: otherwise a caller that
     * asks again straight away — after a forget — finds the finished flight
     * and is handed its old answer without anything being asked.
     *
     * In the app the caller resumes on another thread, so the window was a
     * few instructions wide and the test above failed once in a while. Here
     * the caller is made to be waiting before the first answer exists — the
     * answer is held on a gate until the caller has suspended on it — and it
     * waits unconfined, which resumes it inside the completion itself, before
     * the memo's next line runs: the window is always open.
     */
    @Test
    fun `a question asked as soon as the last answer lands is asked again`() = runBlocking {
        val memo = SflxSearchMemo<String>(ttlMs = 120_000)
        val asked = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        var again: String? = null
        // Runs up to its first suspension right here, so by the time the
        // builder returns the caller is already waiting on the first flight.
        val caller = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            memo.once("tidal|q|5") {
                gate.await()
                asked.incrementAndGet()
                Result.success("first")
            }
            // Still inside the first answer's completion.
            memo.forgetMatching("tidal|")
            again = memo.once("tidal|q|5") {
                asked.incrementAndGet()
                Result.success("second")
            }.getOrNull()
        }
        gate.complete(Unit)
        caller.join()
        assertEquals("the forgotten question was asked again", 2, asked.get())
        assertEquals("second", again)
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
