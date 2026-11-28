package com.music.bitchord.desktop

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.SearchFilter

/**
 * The Search page's state: the query, what it found, and the typeahead under the field.
 *
 * Held together because they move together. The sidebar's field, the page and the typeahead
 * loaders all read and write them, and as loose locals in [BitChordDesktopApp] each one was a
 * capture of its own in every lambda that touched it.
 */
@Stable
internal class DesktopSearchState {
    var query by mutableStateOf("")
    var filter by mutableStateOf(SearchFilter.ALL)
    var rows by mutableStateOf<List<SearchResult>>(emptyList())

    /**
     * Whether anything has been searched for since the field was last emptied: until then the
     * page shows recent searches rather than a result set.
     */
    var committed by mutableStateOf(false)
    var scrollReset by mutableStateOf(0)
    var focusRequested by mutableStateOf(false)

    /** What YouTube thinks is being typed. */
    var suggestions by mutableStateOf<List<String>>(emptyList())

    /** Playable rows for the half-typed query, shown under the text completions. */
    var typeahead by mutableStateOf<List<SearchResult>>(emptyList())

    /** False while the query is being set *by* the app. */
    var typing by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    /**
     * The newest search asked for. Plain, not snapshot state: nothing draws it, it only decides
     * whether an answer may still land.
     */
    private var newestRequest = 0L

    /**
     * Whether the rows on screen, or the newest search still running, are a Library answer. Set
     * from the search that produced them rather than read off [filter]: a filter tapped while a
     * Library search runs starts nothing (the search in flight blocks it), and that search still
     * lands its rows under the catalogue pill. Plain, like [newestRequest]: nothing draws them.
     */
    private var rowsPrivate = false
    private var inFlightPrivate = false

    /**
     * Starts a search: every one before it is superseded. A Library search also remembers the
     * [Innertube.listenerGeneration] it was asked under, since it is the account's own songs.
     */
    fun begin(filter: SearchFilter, generation: Long = Innertube.listenerGeneration): DesktopSearchTicket {
        inFlightPrivate = filter.requiresAuth
        return DesktopSearchTicket(++newestRequest, if (filter.requiresAuth) generation else null)
    }

    /** Whether no search has been asked for since [ticket]'s: it decides the [loading] flag. */
    fun isNewest(ticket: DesktopSearchTicket): Boolean = ticket.id == newestRequest

    /**
     * Whether [ticket]'s answer may still be shown: it is the newest search and, for Library, the
     * credentials have not moved under it. A Library search can lose only the second — a cookie
     * or channel change under the same account — and is then still the one that owns [loading].
     */
    fun owns(ticket: DesktopSearchTicket, generation: Long = Innertube.listenerGeneration): Boolean =
        isNewest(ticket) && (ticket.credentials == null || ticket.credentials == generation)

    /** Puts [ticket]'s answer on screen, remembering whether it is the account's own. */
    fun show(ticket: DesktopSearchTicket, found: List<SearchResult>) {
        rows = found
        rowsPrivate = ticket.credentials != null
    }

    /** The newest search is over, however it ended. */
    fun finish(ticket: DesktopSearchTicket) {
        if (!isNewest(ticket)) return
        loading = false
        inFlightPrivate = false
    }

    /**
     * The listener changed. A Library search, on screen or in flight, was the leaving account's:
     * it is superseded and its rows dropped, whichever pill is selected now, and signed out a
     * Library filter goes back to All, since Library is no longer offered. A catalogue search is
     * nobody's in particular and stays.
     */
    fun onListenerChanged(signedIn: Boolean) {
        val privateInFlight = loading && inFlightPrivate
        if (!filter.requiresAuth && !rowsPrivate && !privateInFlight) return
        newestRequest++
        loading = false
        inFlightPrivate = false
        rows = emptyList()
        rowsPrivate = false
        committed = false
        error = null
        if (!signedIn && filter.requiresAuth) filter = SearchFilter.ALL
    }
}

/** One desktop search as it was asked: its place in line, and for Library its listener. */
internal data class DesktopSearchTicket(val id: Long, val credentials: Long?)
