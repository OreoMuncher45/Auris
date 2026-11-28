package com.music.bitchord.ui

import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.model.SearchFilter
import java.util.concurrent.atomic.AtomicLong

/**
 * One search as it was asked for: its place in line, and — for a filter that
 * answers from the signed-in account — the [Innertube.listenerGeneration] it
 * was asked under.
 */
internal data class SearchTicket(val id: Long, val credentials: Long?)

/**
 * Decides whether a search answer may still be put on screen.
 *
 * An answer belongs to the newest search asked for, and nothing older. A
 * Library search is also the account's own songs, so it belongs to the
 * listener it was asked as: a sign-out or account switch while it is in
 * flight makes it someone else's answer. The generation is compared rather
 * than the listener key because a switch A→B→A lands back on the same key
 * with B's request in between.
 */
internal class SearchOwnership(
    private val listenerGeneration: () -> Long = { Innertube.listenerGeneration },
) {
    private val newest = AtomicLong(0L)

    /** A new search: every one before it is superseded. */
    fun issue(filter: SearchFilter): SearchTicket =
        SearchTicket(newest.incrementAndGet(), if (filter.requiresAuth) listenerGeneration() else null)

    /** Nothing asked for so far may land any more. */
    fun supersede() {
        newest.incrementAndGet()
    }

    /** Whether no search has been asked for since [ticket]'s. */
    fun isNewest(ticket: SearchTicket): Boolean = ticket.id == newest.get()

    fun owns(ticket: SearchTicket): Boolean =
        ticket.id == newest.get() && (ticket.credentials == null || ticket.credentials == listenerGeneration())
}

/**
 * The search cache's key for one answer. The source is in it: Spotify's answer
 * and YouTube Music's answer to the same words are different answers, and one
 * of them must never be shown for the other.
 */
internal fun searchCacheKey(query: String, filter: SearchFilter, source: SearchSource): String =
    "${source.name}:${filter.name}:$query"

/**
 * Whether a [searchCacheKey] holds an answer from the signed-in account — one
 * that must not outlive the listener it was asked as.
 */
/**
 * Whether a cached answer under [key] may be shown now. A catalogue answer is
 * anyone's; an account-private one only for the listener it was answered for
 * ([answeredFor], null when that was never recorded), and never across a
 * session-scope or account change that left the cache key itself unchanged.
 */
internal fun <S> cachedSearchUsable(key: String, answeredFor: S?, now: S): Boolean =
    !isAccountPrivateSearchKey(key) || (answeredFor != null && answeredFor == now)

/**
 * A search that lost ownership while still the newest one asked: only the
 * credentials moved under it, and nothing else will ask again, so it is
 * asked again as the listener now signing requests.
 */
internal fun shouldReissueSearch(owns: Boolean, isNewest: Boolean): Boolean = !owns && isNewest

/**
 * Whether a finished load-more releases the loading-more flag. It does when
 * it settled while still owning its search (published, or failed), and when
 * its search is still the one shown but lost ownership, before or during the
 * merge, since nothing else would clear it. A newer search started meanwhile
 * keeps its own flag.
 */
internal fun loadMoreReleasesFlag(settledWhileOwned: Boolean, stillShown: Boolean, ownsNow: Boolean): Boolean =
    settledWhileOwned || (stillShown && !ownsNow)

internal fun isAccountPrivateSearchKey(key: String): Boolean {
    val filter = key.substringAfter(':', "").substringBefore(':')
    return SearchFilter.entries.any { it.requiresAuth && it.name == filter }
}
