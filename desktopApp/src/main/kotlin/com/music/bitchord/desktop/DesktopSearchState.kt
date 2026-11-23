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
     * Starts a search: every one before it is superseded. A Library search also remembers the
     * [Innertube.listenerGeneration] it was asked under, since it is the account's own songs.
     */
    fun begin(filter: SearchFilter, generation: Long = Innertube.listenerGeneration): DesktopSearchTicket =
        DesktopSearchTicket(++newestRequest, if (filter.requiresAuth) generation else null)

    /** Whether [ticket]'s answer may still be shown. */
    fun owns(ticket: DesktopSearchTicket, generation: Long = Innertube.listenerGeneration): Boolean =
        ticket.id == newestRequest && (ticket.credentials == null || ticket.credentials == generation)

    /**
     * The listener changed. A Library search, on screen or in flight, was the leaving account's:
     * it is superseded and its rows dropped, and signed out the filter goes back to All, since
     * Library is no longer offered. A catalogue search is nobody's in particular and stays.
     */
    fun onListenerChanged(signedIn: Boolean) {
        if (!filter.requiresAuth) return
        newestRequest++
        loading = false
        rows = emptyList()
        committed = false
        error = null
        if (!signedIn) filter = SearchFilter.ALL
    }
}

/** One desktop search as it was asked: its place in line, and for Library its listener. */
internal data class DesktopSearchTicket(val id: Long, val credentials: Long?)
