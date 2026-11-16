package com.music.bitchord.desktop

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Job

/**
 * The signed-in Google accounts and the session in force, as [BitChordDesktopApp] tracks them.
 *
 * Held in one place for the same reason as [DesktopAppSettings]: as loose locals each was a
 * capture of its own in every lambda and local function that touched it. Initial values are
 * read once, when the window opens, as before.
 */
@Stable
internal class DesktopAccountState {
    var accounts by mutableStateOf(DesktopAccounts.accounts())
    var activeAccountId by mutableStateOf(DesktopAccounts.activeAccountId())
    var activeProfileId by mutableStateOf(DesktopAccounts.activeProfileId())
    var signInBusy by mutableStateOf<String?>(null)
    var signInError by mutableStateOf<String?>(null)
    var browserSignInJob by mutableStateOf<Job?>(null)

    /** Whether YouTube is actually accepting the session, which is not the same as having an account saved. */
    var youtubeSignedIn by mutableStateOf(false)

    /** Set by an edit that the library feed has not caught up with yet. */
    var libraryStale by mutableStateOf(false)

    /**
     * Bumped when the session changes underneath, so the loaders keyed on it re-run without
     * anything they are keyed on having to change to something and back.
     */
    var sessionRevision by mutableStateOf(0)
}

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
