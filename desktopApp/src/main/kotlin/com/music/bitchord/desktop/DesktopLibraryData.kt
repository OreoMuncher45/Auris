package com.music.bitchord.desktop

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Job

/**
 * The listener's own songs on this computer: likes, dislikes, downloads and local playlists, and
 * the add-to-playlist edit in progress.
 *
 * Read from [DesktopPersistence] once, when the window opens, as the loose locals they replace
 * were.
 */
@Stable
internal class DesktopLibraryData(persistence: DesktopPersistence) {
    var likedIds by mutableStateOf(persistence.likedIds())

    /** In-flight tail of the Liked Music chain; replaced whenever the library is fetched again. */
    var likedSyncJob by mutableStateOf<Job?>(null)
    var dislikedIds by mutableStateOf(persistence.dislikedIds())
    var downloads by mutableStateOf(persistence.downloads())
    var playlists by mutableStateOf(persistence.playlists())

    /** The song the add-to-playlist dialog is open for, if it is open for one. */
    var playlistTarget by mutableStateOf<Song?>(null)

    // One busy flag and one message for every playlist edit: only one of them can be in flight,
    // because each is driven by a dialog that is modal.
    var playlistBusy by mutableStateOf(false)
    var playlistError by mutableStateOf<String?>(null)
}
