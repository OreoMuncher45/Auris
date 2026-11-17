package com.music.bitchord.ui

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [followContinuations] is how a playlist's later pages reach its open page.
 * The page chain is serial (each token is in the page before it), so the
 * floor is one round trip per page. These tests hold it to that floor: the
 * reader's work on one page and the first screen's head start must not add
 * to it, and it must still only ever have one request out at a time.
 *
 * Virtual time: a fetch is [RTT] ms of network, a delivery is [WORK] ms of
 * Main being busy with the merge and publish.
 */
class FollowContinuationsTest {

    private data class Page(val n: Int, val next: String?)

    /** A playlist of [pages] continuation pages after the first. */
    private class Chain(val pages: Int) {
        var inFlight = 0
        var maxInFlight = 0
        var requests = 0
        suspend fun fetch(token: String): Page {
            requests++
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            delay(RTT)
            inFlight--
            val n = token.removePrefix("t").toInt()
            return Page(n, if (n < pages) "t${n + 1}" else null)
        }
    }

    @Test
    fun `the full list costs one round trip per page and nothing more`() = runTest {
        val chain = Chain(pages = 5)
        val delivered = mutableListOf<Int>()
        followContinuations(
            token = "t1",
            fetch = chain::fetch,
            nextToken = { it.next },
            holdFirstMillis = HOLD,
        ) { batch ->
            delay(WORK)
            delivered += batch.map { it.n }
            true
        }
        assertEquals(listOf(1, 2, 3, 4, 5), delivered)
        // Serial and waiting on the reader: HOLD + 5 * (RTT + WORK) = 2650.
        // Pipelined: 5 * RTT, plus the last page's own WORK = 2100.
        assertEquals(5 * RTT + WORK, currentTime)
        assertEquals("one request out at a time", 1, chain.maxInFlight)
        assertEquals(5, chain.requests)
    }

    @Test
    fun `pages that land while the reader is busy go up together`() = runTest {
        val chain = Chain(pages = 6)
        val batches = mutableListOf<List<Int>>()
        followContinuations("t1", chain::fetch, { it.next }) { batch ->
            batches += batch.map { it.n }
            delay(3 * RTT) // a long frame stall
            true
        }
        assertEquals((1..6).toList(), batches.flatten())
        assertTrue("fewer publishes than pages: $batches", batches.size < 6)
        assertEquals(1, chain.maxInFlight)
    }

    @Test
    fun `the first delivery still waits for the first screen`() = runTest {
        val chain = Chain(pages = 1)
        var deliveredAt = -1L
        followContinuations("t1", { token -> delay(10); chain.fetch(token) }, { it.next }, holdFirstMillis = 2_000) {
            deliveredAt = currentTime
            true
        }
        assertEquals(2_000L, deliveredAt)
    }

    @Test
    fun `stops asking once the reader says stop`() = runTest {
        val chain = Chain(pages = 50)
        var calls = 0
        followContinuations("t1", chain::fetch, { it.next }) {
            calls++
            false
        }
        assertEquals(1, calls)
        // The page after the one delivered may already be on its way; nothing after it.
        assertTrue("requests=${chain.requests}", chain.requests <= 2)
    }

    @Test
    fun `a failed page ends the chain with what arrived`() = runTest {
        val chain = Chain(pages = 5)
        val delivered = mutableListOf<Int>()
        followContinuations(
            token = "t1",
            fetch = { token -> if (token == "t3") null else chain.fetch(token) },
            nextToken = { it.next },
        ) { batch ->
            delivered += batch.map { it.n }
            true
        }
        assertEquals(listOf(1, 2), delivered)
    }

    private companion object {
        const val RTT = 400L
        const val WORK = 100L
        const val HOLD = 150L
    }
}
