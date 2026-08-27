package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UserPlaylist
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.ui.icons.BitChordIcons

/**
 * The album or playlist a long-press is acting on.
 *
 * Deliberately not one of the browse models: every surface in the app describes
 * a release with a different type — a [com.music.bitchord.data.model.ShelfItem]
 * on the home feed, a [com.music.bitchord.data.model.BrowseItem] in search, a
 * [com.music.bitchord.data.model.DetailPage] on the page itself, a plain album
 * name on the Local Music tab — and the menu needs the same five things from
 * all of them.
 */
data class BrowseTarget(
    /**
     * What to fetch the track list with, or null when there is nothing to
     * fetch: a Local Music album is a grouping of rows already on screen, not
     * a page anything can be asked for.
     */
    val browseId: String?,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String? = null,
    val type: BrowseType = BrowseType.OTHER,
    /**
     * The tracks already in hand. Empty for a card whose page was never
     * opened — the queue actions then fetch the listing themselves, which is
     * why they are offered either way.
     */
    val songs: List<Song> = emptyList(),
    /**
     * Set when this is one of the account's own playlists, which is the only
     * case where renaming and deleting are things that can be done to it.
     *
     * Filled in where the sheet is raised rather than by whatever built the
     * target: only the playlist's own page states who made it, so for a card
     * this has to be sent for and arrives after the sheet is already up. Left
     * null by every caller, and null is also the honest answer while the
     * question is still out.
     */
    val playlist: UserPlaylist? = null,
    /**
     * False when the menu was opened from the page it would otherwise navigate
     * to — the release page's own overflow. Play, Shuffle and Open are already
     * on that page, so there they are left off the sheet.
     */
    val fromCard: Boolean = true,
    /**
     * The id this release is recorded under in [com.music.bitchord.download.Downloads.collections],
     * when it was downloaded whole — set so [onDelete] in the caller can offer
     * "Delete download" alongside (or instead of) deleting the account's own
     * copy.
     *
     * Left for the caller to resolve rather than derived here: a card off the
     * Local Music screen has no browse id to derive it from at all (see
     * `albumEntries` in `LocalMusicScreen`), while a real page's id needs
     * `Downloads.recordIdOf` run on it first for a downloaded playlist. Both are
     * questions about the download record, not about what this sheet is.
     */
    val downloadId: String? = null,
)

/**
 * Long-press menu for an album or playlist — the collection-level counterpart
 * to [SongActionsSheet].
 *
 * Every action is optional, and which ones a caller passes is how the same
 * sheet serves a card on the home feed and the page that card opens. A card
 * has no other way to play its album without navigating to it, so it gets Play
 * and Shuffle; the page's own header already carries both, so there they are
 * null and the sheet is the two queue rows and nothing else.
 *
 * The queue rows are always offered. They are the reason this menu exists: a
 * release is exactly the kind of thing someone wants *after* what is playing
 * rather than instead of it, and until now the only way to queue one was to
 * open it and long-press its tracks one at a time.
 *
 * Deleting asks a second time, in place. A playlist is the only thing in this
 * app whose loss can't be undone by tapping the same row again, and a
 * mis-tapped card in a shelf is exactly how it would happen.
 */
@Composable
fun BrowseActionsSheet(
    target: BrowseTarget,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    modifier: Modifier = Modifier,
    onPlay: (() -> Unit)? = null,
    onShuffle: (() -> Unit)? = null,
    /** Null where the sheet was opened from the page it would navigate to. */
    onOpen: (() -> Unit)? = null,
    onDownloadAll: (() -> Unit)? = null,
    onRename: ((String) -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    /**
     * Removes the files this release was downloaded as, when it was downloaded
     * whole — see [BrowseTarget.downloadId]. Independent of [onDelete]: that one
     * deletes the playlist from the account, this one only ever touches what's
     * on the device, so both can be offered together for an owned playlist that
     * also happens to be downloaded.
     */
    onDeleteDownload: (() -> Unit)? = null,
) {
    var renaming by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var confirmingDeleteDownload by remember { mutableStateOf(false) }

    val playlist = target.playlist
    if (renaming && playlist != null && onRename != null) {
        RenamePlaylistForm(
            playlist = playlist,
            onBack = { renaming = false },
            onRename = onRename,
            modifier = modifier,
        )
        return
    }

    Column(modifier.fillMaxWidth()) {
        BrowseSheetHeader(target)
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)

        onPlay?.let { ActionRow(Icons.Rounded.PlayArrow, "Play", onClick = it) }
        onShuffle?.let { ActionRow(BitChordIcons.Shuffle, "Shuffle", onClick = it) }
        ActionRow(Icons.AutoMirrored.Rounded.PlaylistPlay, "Play next", onClick = onPlayNext)
        ActionRow(Icons.AutoMirrored.Rounded.QueueMusic, "Add to queue", onClick = onAddToQueue)
        onDownloadAll?.let { ActionRow(BitChordIcons.Download, "Download all", onClick = it) }
        onOpen?.let {
            ActionRow(BitChordIcons.ChevronRight, "Open ${target.type.noun}".trim(), onClick = it)
        }
        if (onRename != null) {
            ActionRow(Icons.Rounded.Edit, "Rename") { renaming = true }
        }
        if (onDelete != null) {
            if (confirmingDelete) {
                ActionRow(
                    icon = Icons.Rounded.DeleteForever,
                    label = "Delete \"${target.title}\" — tap to confirm",
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onDelete,
                )
            } else {
                ActionRow(Icons.Rounded.Delete, "Delete playlist") { confirmingDelete = true }
            }
        }
        if (onDeleteDownload != null) {
            if (confirmingDeleteDownload) {
                ActionRow(
                    icon = Icons.Rounded.DeleteForever,
                    label = "Remove \"${target.title}\" from this device — tap to confirm",
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onDeleteDownload,
                )
            } else {
                ActionRow(Icons.Rounded.Delete, "Delete download") { confirmingDeleteDownload = true }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Which release the sheet is about: the same row the shelf card was. */
@Composable
private fun BrowseSheetHeader(target: BrowseTarget) {
    val shape = if (target.type == BrowseType.ARTIST) CircleShape else RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = target.thumbnailUrl.artworkAt(ROW_ART_PX),
            contentDescription = null,
            modifier = Modifier
                .size(52.dp)
                .clip(shape)
                .thumbnailBorder(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = target.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // A card frequently has no subtitle at all, and the kind of
                // thing it is beats a blank line under the title. A card of
                // unknown kind has neither, and gets the track count instead —
                // which by then is the one thing actually known about it.
                text = target.subtitle.ifBlank {
                    target.type.noun.replaceFirstChar { it.uppercase() }.ifBlank {
                        target.songs.size.takeIf { it > 0 }
                            ?.let { "$it ${if (it == 1) "song" else "songs"}" }
                            .orEmpty()
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * What to call the thing the sheet is about, in a sentence. [BrowseType.OTHER]
 * is a home card nobody has identified yet — "Open" without a noun is still a
 * true label for it, and guessing "album" would not be.
 */
private val BrowseType.noun: String
    get() = when (this) {
        BrowseType.ALBUM -> "album"
        BrowseType.PLAYLIST -> "playlist"
        BrowseType.ARTIST -> "artist"
        BrowseType.OTHER -> ""
    }
