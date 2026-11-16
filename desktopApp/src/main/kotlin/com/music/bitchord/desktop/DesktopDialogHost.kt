package com.music.bitchord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import com.music.bitchord.data.model.PlaylistPrivacy
import com.music.bitchord.data.model.UserPlaylist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the window's dialogs can ask it to do; each is the window's function of the same name. */
internal class DesktopDialogActions(
    val signIn: suspend (cookie: String, label: String, sourceProfile: String?) -> Unit,
    val openSettings: () -> Unit,
    val addToAccountPlaylist: (UserPlaylist) -> Unit,
    val addToPlaylist: (DesktopPlaylist) -> Unit,
    val createPlaylist: (title: String, privacy: PlaylistPrivacy) -> Unit,
    val closePlaylistDialogs: () -> Unit,
    val renameOpenPlaylist: (String) -> Unit,
    val deleteOpenPlaylist: () -> Unit,
)

/**
 * The dialogs over the window: the account switcher and sign-in, the playlist pickers and edits,
 * the download queue, the scrobbling and Discord prompts, Listen Together, the output device and
 * the signal chain. Each shows while its flag on [overlays] is set.
 *
 * Lifted out of [BitChordDesktopApp] as it was, in the same order, so they stack as before. The
 * local functions at the top keep the moved code reading as it did, each one a call into
 * [actions]; [scope] is the window's, so a sign-in outlives its dialog as it did.
 */
@Composable
internal fun DesktopDialogHost(
    overlays: DesktopOverlays,
    settings: DesktopAppSettings,
    account: DesktopAccountState,
    library: DesktopLibraryData,
    pages: DesktopPagesState,
    scope: CoroutineScope,
    interactiveSignInBrowser: DesktopBrowserSignIn.Browser?,
    accountPlaylists: List<UserPlaylist>,
    playback: DesktopPlaybackState,
    playbackEngine: DesktopPlaybackEngine,
    /** Read where an engine readout is drawn, so it follows the engine; see DesktopSettingsHost. */
    playbackClock: State<DesktopPlaybackState>,
    sourceConfigs: List<DesktopSourceConfig>,
    actions: DesktopDialogActions,
) {
    suspend fun signIn(cookie: String, label: String, sourceProfile: String? = null) =
        actions.signIn(cookie, label, sourceProfile)
    fun openSettings() = actions.openSettings()
    fun addToAccountPlaylist(playlist: UserPlaylist) = actions.addToAccountPlaylist(playlist)
    fun addToPlaylist(playlist: DesktopPlaylist) = actions.addToPlaylist(playlist)
    fun createPlaylist(title: String, privacy: PlaylistPrivacy) = actions.createPlaylist(title, privacy)
    fun closePlaylistDialogs() = actions.closePlaylistDialogs()
    fun renameOpenPlaylist(title: String) = actions.renameOpenPlaylist(title)
    fun deleteOpenPlaylist() = actions.deleteOpenPlaylist()

    if (overlays.accounts) {
        DesktopAccountSelector(
            accounts = account.accounts,
            activeAccountId = account.activeAccountId,
            activeProfileId = account.activeProfileId,
            busy = account.signInBusy != null,
            onSelect = { picked, profile ->
                DesktopAccounts.select(picked.accountId, profile.profileId)
                account.activeAccountId = picked.accountId
                account.activeProfileId = profile.profileId
            },
            onAddAccount = { overlays.accounts = false; overlays.signIn = true },
            onRemoveAccount = { removed ->
                scope.launch {
                    account.accounts = withContext(Dispatchers.IO) {
                        DesktopAccounts.remove(removed.accountId)
                        DesktopAccounts.accounts()
                    }
                    account.activeAccountId = DesktopAccounts.activeAccountId()
                    account.activeProfileId = DesktopAccounts.activeProfileId()
                }
            },
            onOpenSettings = { overlays.accounts = false; openSettings() },
            onDismiss = { overlays.accounts = false },
        )
    }

    if (overlays.signIn) {
        DesktopSignInDialog(
            busy = account.signInBusy,
            error = account.signInError,
            interactiveBrowser = interactiveSignInBrowser,
            onBrowserSignIn = { browser ->
                account.browserSignInJob?.cancel()
                account.browserSignInJob = scope.launch {
                    account.signInError = null
                    account.signInBusy = browser.label
                    try {
                        val cookie = DesktopBrowserSignIn.capture(browser)
                        signIn(cookie, browser.label)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        account.signInError = failure.message ?: "Could not complete browser sign-in."
                    } finally {
                        account.signInBusy = null
                        account.browserSignInJob = null
                    }
                }
            },
            onImport = { profile ->
                scope.launch {
                    when (val found = withContext(Dispatchers.IO) { DesktopBrowserCookies.read(profile) }) {
                        is DesktopBrowserCookies.Result.Session ->
            signIn(found.cookie, profile.label, profile.database.toString())
                        DesktopBrowserCookies.Result.SignedOut ->
                            account.signInError = "${profile.label} is not signed in."
                        is DesktopBrowserCookies.Result.Unavailable ->
                            account.signInError = "${profile.label}: ${found.reason}."
                    }
                }
            },
            onPaste = { pasted ->
                scope.launch {
                    if (!DesktopBrowserCookies.hasSigningSecret(pasted)) {
                        account.signInError = "That cookie has no signing secret in it — copy the whole header."
                    } else {
                        signIn(pasted, "the cookie you pasted")
                    }
                }
            },
            onDismiss = {
                account.browserSignInJob?.cancel()
                overlays.signIn = false
                account.signInError = null
            },
        )
    }
    if (overlays.playlistDialog || library.playlistTarget != null) {
        DesktopPlaylistDialog(
            song = library.playlistTarget,
            accountPlaylists = accountPlaylists,
            localPlaylists = library.playlists,
            signedIn = account.youtubeSignedIn,
            canUseAccount = library.playlistTarget
                ?.let { DesktopSearchClient.isVideoId(it.videoId) } ?: true,
            busy = library.playlistBusy,
            error = library.playlistError,
            onPickAccount = ::addToAccountPlaylist,
            onPickLocal = ::addToPlaylist,
            onCreate = ::createPlaylist,
            onDismiss = ::closePlaylistDialogs,
        )
    }
    if (overlays.rename) {
        DesktopRenamePlaylistDialog(
            current = pages.openedCollection?.title.orEmpty(),
            busy = library.playlistBusy,
            error = library.playlistError,
            onRename = ::renameOpenPlaylist,
            onDismiss = ::closePlaylistDialogs,
        )
    }
    if (overlays.delete) {
        DesktopDeletePlaylistDialog(
            title = pages.openedCollection?.title.orEmpty(),
            busy = library.playlistBusy,
            error = library.playlistError,
            onDelete = ::deleteOpenPlaylist,
            onDismiss = ::closePlaylistDialogs,
        )
    }
    if (overlays.downloadManager) {
        DesktopDownloadManagerDialog(onDismiss = { overlays.downloadManager = false })
    }
    if (overlays.lastfmLogin) {
        DesktopLastfmLoginDialog(onDismiss = { overlays.lastfmLogin = false })
    }
    if (overlays.listenBrainzToken) {
        DesktopListenBrainzTokenDialog(onDismiss = { overlays.listenBrainzToken = false })
    }
    if (overlays.discordToken) {
        DesktopDiscordTokenDialog(onDismiss = { overlays.discordToken = false })
    }
    if (overlays.listenTogether) {
        DesktopListenTogetherDialog(
            autoplayEnabled = settings.autoplay,
            onDismiss = { overlays.listenTogether = false },
        )
    }
    if (overlays.audioOutput) {
        DesktopAudioOutputDialog(onDismiss = { overlays.audioOutput = false })
    }
    if (overlays.pipeline) {
        DesktopAudioPipelineDialog(
            format = playback.streamFormat,
            sourceName = playback.streamSourceId?.let { id ->
                sourceConfigs.firstOrNull { it.id == id }?.displayName
            },
            pipeline = playbackClock.value.let { playbackEngine.pipeline() },
            onDismiss = { overlays.pipeline = false },
        )
    }
}
