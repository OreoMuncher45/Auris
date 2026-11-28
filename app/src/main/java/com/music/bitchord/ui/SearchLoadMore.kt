package com.music.bitchord.ui

/**
 * One load-more of the shown search's next page: fetch, merge, publish, and
 * recover when the search lost ownership while it ran.
 *
 * Ownership is checked after the fetch and again after the merge (which
 * suspends). Lost to a newer search, the page is dropped and the newer
 * search's loading flag is left alone. Lost with this search still shown and
 * still the newest (only the credentials moved), the page is dropped and the
 * search is asked again ([Host.reissue]), which gives it a ticket that can
 * page; nothing else would ask again, so every later page would fail too.
 *
 * Kept apart from the view model so the recovery can be run against a fake
 * repository with controlled suspension points.
 */
internal class SearchLoadMore<Session : Any, Row>(
    private val ownership: SearchOwnership,
    private val host: Host<Session, Row>,
) {
    data class Page<Row>(val rows: List<Row>, val continuation: String?)

    interface Host<Session : Any, Row> {
        /** The search on screen, or null. */
        val shown: Session?
        fun ticketOf(session: Session): SearchTicket
        fun continuationOf(session: Session): String?
        var loadingMore: Boolean
        suspend fun fetch(session: Session, token: String): Result<Page<Row>>
        fun currentRows(): List<Row>
        suspend fun merge(current: List<Row>, page: List<Row>): List<Row>
        fun publish(session: Session, merged: List<Row>, continuation: String?)
        /** Asks the shown search again as the listener now signing requests. */
        fun reissue()
    }

    /**
     * Claims the loading flag for [session]'s next page and returns its token,
     * or null when there is nothing to load or a load-more is already running.
     * Call on the main thread, then [run] with the token.
     */
    fun begin(session: Session): String? {
        if (host.loadingMore) return null
        val token = host.continuationOf(session) ?: return null
        host.loadingMore = true
        return token
    }

    suspend fun run(session: Session, token: String) {
        val ticket = host.ticketOf(session)
        val next = host.fetch(session, token)
        var settled = false
        if (host.shown == session && ownership.owns(ticket)) {
            var owns = true
            next.onSuccess { page ->
                val merged = host.merge(host.currentRows(), page.rows)
                // The merge suspended; a search started meanwhile owns the list now.
                owns = host.shown == session && ownership.owns(ticket)
                if (owns) host.publish(session, merged, page.continuation)
            }
            settled = owns
        }
        val stillShown = host.shown == session
        val ownsNow = ownership.owns(ticket)
        if (stillShown && shouldReissueSearch(ownsNow, ownership.isNewest(ticket))) {
            host.reissue()
        } else if (loadMoreReleasesFlag(settled, stillShown, ownsNow)) {
            host.loadingMore = false
        }
    }
}
