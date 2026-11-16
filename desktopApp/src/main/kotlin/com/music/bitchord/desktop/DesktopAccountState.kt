package com.music.bitchord.desktop

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
