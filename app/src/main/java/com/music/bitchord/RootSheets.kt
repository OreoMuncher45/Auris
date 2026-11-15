package com.music.bitchord

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.music.bitchord.auth.WebSessionMode
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.UserPlaylist
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.mornye.InstalledSflx
import com.music.bitchord.ui.components.BrowseTarget
import com.music.bitchord.ui.components.HeldItem
import com.music.bitchord.ui.screens.DiscordDialog

/**
 * Which of the app root's sheets, dialogs, menus and pages-over-the-feed is
 * open, and what it is open on.
 *
 * A holder rather than one `remember { mutableStateOf(...) }` per flag in
 * [BitChordApp], for the reason [PodcastUiState] gives: that composable's
 * content lambda captures every local it mentions, and dex can only call a
 * constructor 254 arguments wide (see `tools/check_constructor_widths.py`).
 * One holder is one capture, however many fields it carries.
 *
 * None of this survives the process — these were all plain `remember`s. The
 * flags that are restored after process death stay `rememberSaveable` in
 * [BitChordApp].
 */
@Stable
internal class RootSheets {
    /**
     * What the in-app browser is open for, or null while it is closed —
     * signing in, or picking a channel in YouTube Music's own Accounts list.
     */
    var webSession by mutableStateOf<WebSessionMode?>(null)
    var openAudiobook by mutableStateOf<com.music.bitchord.data.audiobook.Audiobook?>(null)
    var showSpotifyCanvasAuth by mutableStateOf(false)
    var showSpotifyLogin by mutableStateOf(false)
    // Hosted here rather than inside SourcesScreen so its frosted card has
    // something to blur: that screen is drawn inside the `hazeSource` subtree,
    // and a haze effect sampling the layer it is itself part of renders with no
    // background at all. Hosting it here also puts the scrim over the tab bar
    // and the mini player, like every other alert in the app.
    var editingSource by mutableStateOf<SourceConfig?>(null)
    /**
     * The install-from-link sheet, and the extension whose own settings are open.
     *
     * Held here rather than inside the sources screen for the same reason every
     * other alert is: the screen sits inside the subtree that carries
     * `hazeSource`, so a frosted card drawn from within it comes out with no
     * background. See [SourcesScreen]'s note on `onEditSource`.
     */
    var installingExtension by mutableStateOf(false)
    var editingExtension by mutableStateOf<InstalledSflx?>(null)
    var confirmJioSaavn by mutableStateOf(false)
    var editingPartyServer by mutableStateOf(false)
    var showHistory by mutableStateOf(false)
    // A Library shelf's "Show all" — the shelf it was opened from, so its own
    // cards can be laid out again as a full-screen grid. See [LibraryGridPage].
    var libraryShowAll by mutableStateOf<HomeShelf?>(null)
    var detailActiveShelf by mutableStateOf<HomeShelf?>(null)
    var librarySortMenuOpen by mutableStateOf(false)
    var showLyricsSources by mutableStateOf(false)
    var showAppLanguage by mutableStateOf(false)
    var showTranslationLanguage by mutableStateOf(false)
    var showAccountSelector by mutableStateOf(false)
    var showListenBrainzLogin by mutableStateOf(false)
    var showLastfmLogin by mutableStateOf(false)
    var showWebDavEditor by mutableStateOf(false)
    var showSmbEditor by mutableStateOf(false)
    /**
     * Whether the download manager is open.
     *
     * Not [rememberSaveable]: the list behind it does not survive the process
     * either (see [com.music.bitchord.download.DownloadSession]), and a sheet
     * restored over an empty one would be a manager with nothing to manage.
     */
    var showDownloadManager by mutableStateOf(false)
    // Discord Rich Presence: its own page under Account & integrations, its own
    // full-screen sign-in, and one slot for whichever of its alerts is open.
    // The alerts live out here rather than on the page because their scrim has
    // to cover the tab bar and mini player, which are drawn after it.
    var showDiscord by mutableStateOf(false)
    var showSpotify by mutableStateOf(false)
    var showDiscordLogin by mutableStateOf(false)
    var discordDialog by mutableStateOf<DiscordDialog?>(null)
    var songActions by mutableStateOf<Song?>(null)
    var showLyricsOffset by mutableStateOf(false)
    /**
     * Whether the Automix panel is up over the player.
     *
     * Held here rather than inside the player because two different things
     * open it: a hold on the handoff in the seek bar, and the row in the
     * track menu. Both are outside the player's own state, and the panel has
     * to know that the menu closed behind it.
     */
    var automixPanelOpen by mutableStateOf(false)
    var showSing by mutableStateOf(false)
    /**
     * Whether the track menu that is up was opened from the player.
     *
     * Its copy of the menu carries rows nothing else offers — a sleep timer,
     * the track log, share — and until now "opened from the player" and "the
     * player is on screen" were the same sentence, because the player was a
     * sheet and nothing else could be up behind it. On a tablet the player is
     * never *the* thing on screen: it is always beside whatever is, so the
     * question has to be answered by whoever opened the menu.
     */
    var menuFromPlayer by mutableStateOf(false)
    /**
     * The row or card that was held to open the track menu, or null when it
     * was opened any other way. Non-null lifts that item into
     * [HeldContextMenu]; null keeps the bottom sheet — which is what the ⋮ on
     * the very same row still opens.
     */
    var songMenuOrigin by mutableStateOf<HeldItem?>(null)
    /**
     * Where the player's three-dot button sits, in the sheet window's own
     * coordinates.
     *
     * The menu that answers the button hangs off the button, so the host has
     * to know where the button is. The player is drawn in the sheet's dialog
     * window, and the menu is drawn in that same window — the glass samples
     * the artwork behind it, and a menu in another window would refract a
     * different picture from the one it is sitting on.
     */
    var playerMenuAnchor by mutableStateOf<androidx.compose.ui.unit.IntRect?>(null)
    // Whether the player's album/artist lookup (below, for the current track)
    // is still in flight — read by the long-press sheet so it can show a
    // loading row instead of the two just being absent while it waits.
    var linksLoading by mutableStateOf(false)
    // What the playlist picker is adding, or null when it's closed: the
    // tracks, and the release's own name when the flow started from one.
    // Separate from [songActions] so the menu can close behind it — the picker
    // is the next step, not a second sheet stacked on the first.
    var playlistTarget by mutableStateOf<List<Song>?>(null)
    var playlistTargetTitle by mutableStateOf<String?>(null)
    var playlistTargetArtwork by mutableStateOf<String?>(null)
    // The picker opened from the Library tab, where there is no track and
    // creating the playlist is the whole errand.
    var creatingPlaylist by mutableStateOf(false)
    // Which track is being put into a folder, or null when the folder picker is
    // shut. Separate from [songActions] for the same reason as [playlistTarget]:
    // the menu closes behind it rather than two sheets stacking.
    var folderTarget by mutableStateOf<Song?>(null)
    // The picker opened from the Library's "+" tile, where there is no track
    // and making the folder is the whole errand.
    var creatingFolder by mutableStateOf(false)
    var showSpotifyImportDialog by mutableStateOf(false)
    // Which album or playlist the collection menu is open on, or null when it
    // is shut. One slot for every surface that can open it — the shelves on
    // three tabs, the search rows, the artist page's carousels, the release
    // page's own overflow — because only one of them can be held at a time.
    var browseActions by mutableStateOf<BrowseTarget?>(null)
    /** The card held to open [browseActions] — see [songMenuOrigin]. */
    var browseMenuOrigin by mutableStateOf<HeldItem?>(null)
    /** Rename asked for from the popup, which hands it on to the sheet's form. */
    var browseRenameInSheet by mutableStateOf(false)
    /** The playlist being rearranged, or null when the reorder sheet is shut. */
    var reorderTarget by mutableStateOf<UserPlaylist?>(null)
    /** Its entries as YouTube has them now — fetched fresh when the sheet opens. */
    var reorderEntries by mutableStateOf<UiState<List<Song>>>(UiState.Loading)
    var reorderSaving by mutableStateOf(false)
    // Flow: unlock gate + temp playlist + tuner.
    var showFlow by mutableStateOf(false)
    // The whole Quick Picks set, opened from the arrow at the end of its
    // heading on Home. It is a section of Home rather than a tab of its own.
    var showQuickPicks by mutableStateOf(false)
    var showFlowSave by mutableStateOf(false)
    var flowSaveName by mutableStateOf("")
    var showUpdateDialog by mutableStateOf(false)
    // Free-app credit popup: once ever, reopenable from Settings → Support.
    var showSupportPopup by mutableStateOf(false)
    var showAnnouncementDialog by mutableStateOf(false)
    var songSortMenuOpen by mutableStateOf(false)
}
