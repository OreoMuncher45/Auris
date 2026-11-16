package com.music.bitchord.desktop

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
}
