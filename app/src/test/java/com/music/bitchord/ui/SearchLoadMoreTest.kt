package com.music.bitchord.ui

import com.music.bitchord.data.model.SearchFilter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recovery of a load-more whose search lost ownership (6FGo): run against
 * a fake repository whose fetch and merge suspend until the test lets them go.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchLoadMoreTest {
    private data class Session(val query: String, val ticket: SearchTicket, val continuation: String?)

    private var generation = 1L
    private val ownership = SearchOwnership { generation }

    private inner class FakeHost : SearchLoadMore.Host<Session, String> {
        override var shown: Session? = null
        override var loadingMore = false
        var rows = listOf<String>()
        val fetched = mutableListOf<String>()
        var reissues = 0
        /** Set to hold the next fetch, or merge, until completed. */
        var fetchGate: CompletableDeferred<Unit>? = null
        var mergeGate: CompletableDeferred<Unit>? = null
        /** What the repository answers for a token. */
        val pages = mutableMapOf<String, SearchLoadMore.Page<String>>()

        override fun ticketOf(session: Session) = session.ticket
        override fun continuationOf(session: Session) = session.continuation
        override suspend fun fetch(session: Session, token: String): Result<SearchLoadMore.Page<String>> {
            fetched += token
            fetchGate?.await()
            return Result.success(pages.getValue(token))
        }
        override fun currentRows() = rows
        override suspend fun merge(current: List<String>, page: List<String>): List<String> {
            mergeGate?.await()
            return current + page
        }
        override fun publish(session: Session, merged: List<String>, continuation: String?) {
            rows = merged
            shown = session.copy(continuation = continuation)
        }
        // The view model's reissue: a fresh ticket under the listener now
        // signing, and the first page answered again with a new token.
        override fun reissue() {
            reissues++
            val old = shown!!
            loadingMore = false
            rows = listOf("first page (again)")
            shown = Session(old.query, ownership.issue(SearchFilter.LIBRARY), "t2")
        }
    }

    private fun host(): FakeHost = FakeHost().apply {
        shown = Session("q", ownership.issue(SearchFilter.LIBRARY), "t1")
        rows = listOf("first page")
        pages["t1"] = SearchLoadMore.Page(listOf("stale row"), "t1-next")
        pages["t2"] = SearchLoadMore.Page(listOf("fresh row"), "t2-next")
    }

    private suspend fun FakeHost.loadMore(loadMore: SearchLoadMore<Session, String>) {
        val session = shown!!
        val token = loadMore.begin(session) ?: error("nothing to load")
        loadMore.run(session, token)
    }

    @Test
    fun ownershipLostWhileTheFetchIsInFlightDropsThePageReissuesAndThenPagesOnTheNewToken() = runTest {
        val h = host()
        val loadMore = SearchLoadMore(ownership, h)
        h.fetchGate = CompletableDeferred()
        val job = async { h.loadMore(loadMore) }
        runCurrent()
        generation++ // the listener's credentials move before the first ownership check
        h.fetchGate!!.complete(Unit)
        job.await()

        assertFalse("the stale continuation is discarded", "stale row" in h.rows)
        assertEquals(1, h.reissues)
        assertEquals(listOf("first page (again)"), h.rows)
        // A later load-more pages on the reissued search's own token, and publishes.
        h.fetchGate = null
        h.loadMore(loadMore)
        assertEquals(listOf("t1", "t2"), h.fetched)
        assertEquals(listOf("first page (again)", "fresh row"), h.rows)
        assertEquals("t2-next", h.shown!!.continuation)
        assertFalse(h.loadingMore)
    }

    @Test
    fun ownershipLostDuringTheMergeDropsThePageReissuesAndThenPagesOnTheNewToken() = runTest {
        val h = host()
        val loadMore = SearchLoadMore(ownership, h)
        h.mergeGate = CompletableDeferred()
        val job = async { h.loadMore(loadMore) }
        runCurrent()
        generation++ // after the first check, before the merge completes
        h.mergeGate!!.complete(Unit)
        job.await()

        assertFalse("the stale continuation is discarded", "stale row" in h.rows)
        assertEquals(1, h.reissues)
        h.mergeGate = null
        h.loadMore(loadMore)
        assertEquals(listOf("first page (again)", "fresh row"), h.rows)
        assertEquals("t2-next", h.shown!!.continuation)
    }

    @Test
    fun aNewerSearchStartedDuringTheFetchOrTheMergeIsLeftAlone() = runTest {
        for (during in listOf("fetch", "merge")) {
            val h = host()
            val loadMore = SearchLoadMore(ownership, h)
            val gate = CompletableDeferred<Unit>()
            if (during == "fetch") h.fetchGate = gate else h.mergeGate = gate
            val job = async { h.loadMore(loadMore) }
            runCurrent()
            // A newer search: its own ticket, its own first page, its own load-more running.
            val newer = Session("newer", ownership.issue(SearchFilter.LIBRARY), "n1")
            h.shown = newer
            h.rows = listOf("newer first page")
            h.loadingMore = true
            gate.complete(Unit)
            job.await()

            assertEquals(during, listOf("newer first page"), h.rows)
            assertEquals(during, newer, h.shown)
            assertEquals(during, 0, h.reissues)
            assertTrue("$during: the newer search's flag is kept", h.loadingMore)
        }
    }

    @Test
    fun nothingToLoadOrALoadAlreadyRunningClaimsNothing() {
        val h = host()
        val loadMore = SearchLoadMore(ownership, h)
        assertNull(loadMore.begin(h.shown!!.copy(continuation = null)))
        h.loadingMore = true
        assertNull(loadMore.begin(h.shown!!))
    }
}
