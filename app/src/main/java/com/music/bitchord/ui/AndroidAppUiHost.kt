package com.music.bitchord.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.LibraryViewType
import com.music.bitchord.download.Downloads
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** The shared pages' settings and downloads, answered from the phone's own stores. */
object AndroidAppUiHost : AppUiHost {
    override val swipeToPlayNext = AppSettings.swipeToPlayNext
    override val homeRecentsViewType = AppSettings.homeRecentsViewType
    override fun setHomeRecentsViewType(value: LibraryViewType) = AppSettings.setHomeRecentsViewType(value)
    override val libraryPlaylistsViewType = AppSettings.libraryPlaylistsViewType
    override fun setLibraryPlaylistsViewType(value: LibraryViewType) =
        AppSettings.setLibraryPlaylistsViewType(value)
    override val pinnedPlaylists = AppSettings.pinnedPlaylists
    override val librarySort = AppSettings.librarySort

    /**
     * [Downloads.saved]'s ids, mirrored once into snapshot state for every row
     * to read.
     *
     * Every track row asks this whether to draw its downloaded mark, and it
     * used to answer with a `collectAsStateWithLifecycle` of its own — so each
     * row on screen set up a coroutine and a lifecycle observer on the same
     * flow, and tore them down again as it scrolled off. One collector here,
     * for the life of the process, writes a state each row merely reads: a
     * row scrolling in costs a snapshot read, and a finished download still
     * redraws exactly the rows that read it.
     *
     * Created on first read, which is always from composition and so on the
     * main thread, where [MainScope] also writes it.
     */
    private val savedIds: State<Set<String>> by lazy(LazyThreadSafetyMode.NONE) {
        val state = mutableStateOf(Downloads.saved.value.keys, referentialEqualityPolicy())
        MainScope().launch { Downloads.saved.collect { state.value = it.keys } }
        state
    }

    @Composable
    override fun downloadedIds(): Set<String> = savedIds.value
}
