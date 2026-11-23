package com.music.bitchord.ui.components

import com.music.bitchord.R

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbDownOffAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.webdav.WebDavUploads
import kotlin.math.roundToInt
import com.music.bitchord.download.DownloadState
import com.music.bitchord.download.Downloads
import com.music.bitchord.playback.SleepTimer
import com.music.bitchord.ui.components.thumbnailBorder
import com.music.bitchord.ui.components.menu.LocalMenuInteractive
import com.music.bitchord.ui.components.menu.MenuDensity
import com.music.bitchord.ui.components.menu.MenuDivider
import com.music.bitchord.ui.components.menu.MenuRow
import com.music.bitchord.ui.components.menu.MenuTokens
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.theme.ArtworkPalette
import com.music.bitchord.ui.theme.rememberArtworkPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Long-press menu for a track, in the shape music apps normally use.
 *
 * The account actions lead — rating, playlists, library — because they are
 * what the menu is opened for; the queue and navigation rows below it were
 * always the fallback for "I meant to do something with this song".
 *
 * Everything that writes to the account is hidden outright when [signedIn] is
 * false rather than shown and refused. The same goes for a track that is
 * playing from a local file or a finished download (`song.localUri != null`):
 * rating, playlists, downloading it again and sharing all assume a YouTube
 * identity the file doesn't carry, so those rows drop out regardless of
 * [signedIn].
 *
 * [showSleepTimer] and [onShare] are the player's extras: a sleep timer isn't a
 * property of some row in a list, so it only appears where it means something.
 *
 * [onDownload] is only the *start* of a download — cancelling one and deleting
 * a saved file are answered here, because neither needs anything the caller
 * has. Starting one might: below API 29 it needs a storage permission that only
 * an Activity can ask for.
 *
 * Only the rows: the surface is the host's — [com.music.bitchord.ui.components.menu.AnchoredMenu]
 * for a ⋮ or the player's button, [HeldContextMenu] for a hold. The rows bring
 * their own scroll, so a track with every optional row present scrolls inside
 * the menu rather than running off the screen.
 */
@Composable
fun SongActionsSheet(
    song: Song,
    signedIn: Boolean,
    likeStatus: LikeStatus,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onStartRadio: () -> Unit,
    onDownload: () -> Unit,
    /**
     * Copies this track to the WebDAV server. Null hides the row — the caller
     * passes one only for a readable device file while a server is
     * configured. Uploading while another upload of the same track runs just
     * re-runs it; the conflict dialog keeps that idempotent.
     */
    onUploadToWebDav: (() -> Unit)? = null,
    onToggleLike: () -> Unit,
    onToggleDislike: () -> Unit,
    onAddToPlaylist: () -> Unit,
    /**
     * Opens the sheet that puts this track into one of the listener's own
     * folders. Null hides the row — a host with no folders of its own has
     * nowhere to put the track.
     *
     * Offered whether or not anyone is signed in: a folder belongs to the
     * device, not to an account, so this works offline too.
     */
    onAddToFolder: (() -> Unit)? = null,
    /**
     * Takes this track back out of the folder it is being shown in. Null
     * hides the row.
     */
    onRemoveFromFolder: (() -> Unit)? = null,
    /**
     * Takes this track out of the song cache. Null hides the row — the caller
     * passes one only while the cache actually holds some of it.
     */
    onRemoveFromCache: (() -> Unit)? = null,
    /**
     * Removes this track from the account's listening history. Null hides the
     * row — passed only for a menu opened on the History screen, for a row
     * YouTube gave a removal token.
     */
    onRemoveFromHistory: (() -> Unit)? = null,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    modifier: Modifier = Modifier,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    showSleepTimer: Boolean = false,
    /**
     * Sends the playing track back to YouTube's own upload, and keeps it there.
     * Offered for any player item that has one behind it — a substituted copy
     * can be the wrong recording from the first second, not only after a
     * visible upgrade. See
     * [com.music.bitchord.playback.hasYouTubeOriginal].
     */
    onRollbackToOriginal: (() -> Unit)? = null,
    /**
     * The way back from a revert: available only for a player item the listener
     * has pinned to YouTube's own upload, where nothing will go looking for a
     * better copy again until they ask.
     * See [com.music.bitchord.playback.OriginalVersion].
     */
    onUpgradeQuality: (() -> Unit)? = null,
    /** Keeps the upgrade row visible but untappable while its lookup is running. */
    upgradeQualityInProgress: Boolean = false,
    /**
     * Switches the playing track between its video and audio-only cuts. Null
     * hides the row — offered only from the player, and only once an
     * alternate cut is known to exist. See
     * [com.music.bitchord.playback.PlaybackService.smoothSwapCurrentTrackVersion].
     */
    onToggleAudioVersion: (() -> Unit)? = null,
    /** Which cut is playing now, so the row can offer the other one. */
    isAudioVersion: Boolean = false,
    onShare: (() -> Unit)? = null,
    /**
     * Saves this track's cover at full quality to the gallery. Offered
     * wherever there is art to save — rows and player alike — unlike the
     * account rows above it.
     */
    onSaveArtwork: (() -> Unit)? = null,
    /**
     * Copies what the app logged while starting this track. Null everywhere
     * except the player, where "this track" means something.
     */
    onCopyLog: (() -> Unit)? = null,
    /** Opens the timing control offered only by the main player's menu. */
    onLyricsOffset: (() -> Unit)? = null,
    /**
     * Opens the Sing vocal-level sheet. Player-only: it acts on the track
     * coming out of the speakers right now, not on the song as a library
     * entry.
     */
    onOpenSing: (() -> Unit)? = null,
    /** Opens Replay. Player-only, for the same reason as the copy-log row. */
    onOpenReplay: (() -> Unit)? = null,
    /** Current spatial-audio state, or null to hide the toggle row. */
    spatialAudioOn: Boolean? = null,
    /** Flips spatial audio. Player-only. */
    onToggleSpatialAudio: (() -> Unit)? = null,
    /**
     * Opens Listen Together — the member list when there is a party, the
     * create/join page otherwise. Player-only.
     */
    onOpenListenTogether: (() -> Unit)? = null,
    /** Party size while listening together, or null when there is no party. */
    listenTogetherMembers: Int? = null,
    /**
     * True while a lookup for this track's album/artist ids is still in
     * flight, so it isn't yet known whether "Open album" and "Open artist"
     * belong on this sheet at all. Only the player ever opens a sheet before
     * it knows; everywhere else this is simply false, and the two rows behave
     * as before — present when the id is there, absent when it never was.
     */
    resolvingLinks: Boolean = false,
    /**
     * Whose ink the rows are drawn in. [SongActionsPresentation.Menu] is the
     * theme's, for a menu floating over a page — a ⋮ in a list, or a held row;
     * [SongActionsPresentation.Glass] is the artwork's, for the menu that
     * hangs off the player's three-dot button, whose glass shows the artwork.
     */
    presentation: SongActionsPresentation = SongActionsPresentation.Menu,
) {
    var pickingSleepTimer by remember { mutableStateOf(false) }
    val glass = presentation == SongActionsPresentation.Glass
    // A menu over a page stays in the theme's colours: it floats over
    // whatever the page is, and an accent pitched against one sleeve is not
    // guaranteed to read against another.
    //
    // The player's menu does the opposite: it floats over the artwork itself,
    // so its ink has to be the artwork's ink — the same white-or-near-black
    // the player draws its own chrome in, or a label would vanish into a
    // bright sleeve. Read from the thumbnail already on screen, not a larger
    // copy: a handful of swatches is all it needs.
    val palette = if (glass) {
        rememberArtworkPalette(song.thumbnailUrl, artPx = ROW_ART_PX)
    } else {
        neutralMenuPalette()
    }
    val liked = likeStatus == LikeStatus.LIKE
    val disliked = likeStatus == LikeStatus.DISLIKE
    // A local file or a finished download has no YouTube identity behind it to
    // rate, save, queue into a playlist, fetch again, or share a link for.
    val isOffline = song.localUri != null

    val rows: @Composable ColumnScope.() -> Unit = {
        // Leads the list whenever it's available: which recording is playing
        // is the one question that has to be answered before any of the rows
        // below mean anything — there is no point rating, queueing or
        // downloading the wrong version of a song.
        //
        // The two are never both present — one is offered for a track playing
        // a substituted copy, the other for a track held on YouTube's own —
        // and between them they are the whole of the choice, which is why they
        // sit in the same place under the same divider.
        if (onRollbackToOriginal != null || onUpgradeQuality != null || onToggleAudioVersion != null) {
            (onRollbackToOriginal ?: onUpgradeQuality)?.let {
                ActionRow(
                    icon = if (onRollbackToOriginal != null) {
                        Icons.AutoMirrored.Rounded.Undo
                    } else {
                        Icons.Rounded.HighQuality
                    },
                    label = stringResource(
                        if (onRollbackToOriginal != null) {
                            R.string.revert_to_original
                        } else {
                            R.string.upgrade_quality
                        },
                    ),
                    accent = palette.accent,
                    enabled = onRollbackToOriginal != null || !upgradeQualityInProgress,
                    onClick = it,
                )
            }
            // Which recording, not which quality — a different question from
            // the row above, so it sits right under it rather than merged
            // into it, but still ahead of the divider both share.
            onToggleAudioVersion?.let {
                ActionRow(
                    icon = if (isAudioVersion) Icons.Rounded.Videocam else BitChordIcons.MusicNote,
                    label = stringResource(
                        if (isAudioVersion) R.string.convert_to_video else R.string.convert_to_audio,
                    ),
                    accent = palette.accent,
                    onClick = it,
                )
            }
            GroupSeparator(palette)
        }

        // Remove stands without an account too: a playlist imported onto this
        // device is the listener's to edit while signed out or offline.
        val accountRows = signedIn && !isOffline
        if (accountRows || onRemoveFromPlaylist != null) {
            if (accountRows) {
                ActionRow(
                    icon = if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    label = if (liked) stringResource(R.string.remove_from_liked) else stringResource(R.string.like),
                    tint = if (liked) palette.accent else null,
                    accent = palette.accent,
                    onClick = onToggleLike,
                )
                ActionRow(
                    icon = if (disliked) Icons.Rounded.ThumbDown else Icons.Rounded.ThumbDownOffAlt,
                    label = if (disliked) stringResource(R.string.undo_dislike) else stringResource(R.string.dislike),
                    tint = if (disliked) palette.accent else null,
                    accent = palette.accent,
                    onClick = onToggleDislike,
                )
                ActionRow(
                    icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                    label = stringResource(R.string.add_to_playlist),
                    accent = palette.accent,
                    onClick = onAddToPlaylist,
                )
            }
            onRemoveFromPlaylist?.let {
                ActionRow(
                    icon = Icons.Rounded.PlaylistRemove,
                    label = stringResource(R.string.remove_from_playlist),
                    accent = palette.accent,
                    onClick = it,
                )
            }
            GroupSeparator(palette)
        }

        // The folders are on this device, not on an account, so these two rows
        // are here whether anyone is signed in or not — the account's playlists
        // are the rows above, and a folder is the answer when there is no
        // account to have a playlist on.
        onAddToFolder?.let {
            ActionRow(
                icon = BitChordIcons.Plus,
                label = stringResource(R.string.add_to_folder),
                accent = palette.accent,
                onClick = it,
            )
        }
        onRemoveFromFolder?.let {
            ActionRow(
                icon = Icons.Rounded.PlaylistRemove,
                label = stringResource(R.string.remove_from_folder),
                accent = palette.accent,
                onClick = it,
            )
        }

        onRemoveFromHistory?.let {
            ActionRow(
                icon = Icons.Rounded.History,
                label = stringResource(R.string.remove_from_history),
                accent = palette.accent,
                onClick = it,
            )
        }
        DownloadRow(song, palette, isOffline, onDownload)
        onRemoveFromCache?.let {
            ActionRow(
                icon = Icons.Rounded.DeleteSweep,
                label = stringResource(R.string.remove_from_cache),
                accent = palette.accent,
                onClick = it,
            )
        }
        WebDavUploadRow(song, palette, isOffline, onUploadToWebDav)
        ActionRow(
            icon = Icons.Rounded.Radio,
            label = stringResource(R.string.start_radio),
            accent = palette.accent,
            onClick = onStartRadio,
        )
        ActionRow(
            icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
            label = stringResource(R.string.play_next),
            accent = palette.accent,
            onClick = onPlayNext,
        )
        ActionRow(
            icon = Icons.AutoMirrored.Rounded.QueueMusic,
            label = stringResource(R.string.add_to_queue),
            accent = palette.accent,
            onClick = onAddToQueue,
        )
        when (val id = song.albumId) {
            null -> if (resolvingLinks) {
                LoadingActionRow(Icons.Rounded.Album, stringResource(R.string.open_album), palette)
            }
            else -> ActionRow(
                Icons.Rounded.Album,
                stringResource(R.string.open_album),
                accent = palette.accent,
            ) { onOpenAlbum(id) }
        }
        when (val id = song.artistId) {
            null -> if (resolvingLinks) {
                LoadingActionRow(Icons.Rounded.Person, stringResource(R.string.open_artist), palette)
            }
            else -> ActionRow(
                Icons.Rounded.Person,
                stringResource(R.string.open_artist),
                accent = palette.accent,
            ) { onOpenArtist(id) }
        }
        if (showSleepTimer) {
            ActionRow(
                icon = Icons.Rounded.Bedtime,
                label = stringResource(R.string.sleep_timer),
                value = sleepTimerStatus(),
                accent = palette.accent,
            ) { pickingSleepTimer = true }
        }
        // Deliberately outside the online-only block below. Local files and
        // completed downloads use the same player lyric clock (including
        // embedded synced lyrics), so they need this control just as much as
        // streamed songs do.
        onLyricsOffset?.let {
            ActionRow(
                icon = Icons.Rounded.Tune,
                label = stringResource(R.string.lyrics_offset),
                accent = palette.accent,
                onClick = it,
            )
        }
        // Sing, Replay, Spatial and Listen Together sit together: all four
        // are about the listening session in progress rather than the song as
        // a library entry, and all four are player-only for that reason.
        onOpenSing?.let {
            ActionRow(
                icon = Icons.Rounded.Mic,
                label = stringResource(R.string.sing),
                value = singStatus(),
                accent = palette.accent,
                onClick = it,
            )
        }
        onOpenReplay?.let {
            ActionRow(
                icon = Icons.Rounded.History,
                label = stringResource(R.string.your_replay),
                accent = palette.accent,
                onClick = it,
            )
        }
        if (spatialAudioOn != null && onToggleSpatialAudio != null) {
            val on = spatialAudioOn
            ActionRow(
                icon = Icons.Rounded.SurroundSound,
                label = stringResource(R.string.spatial_audio),
                value = stringResource(if (on) R.string.on else R.string.off),
                accent = palette.accent,
                onClick = onToggleSpatialAudio,
            )
        }
        onOpenListenTogether?.let {
            ActionRow(
                icon = Icons.Rounded.Groups,
                label = stringResource(R.string.listen_together),
                value = listenTogetherMembers?.toString(),
                accent = palette.accent,
                onClick = it,
            )
        }
        if (!isOffline) {
            onShare?.let {
                ActionRow(Icons.Rounded.Share, stringResource(R.string.share), accent = palette.accent, onClick = it)
            }
        }
        // Beside share: the row's own art is a thumbnail, so this fetches
        // the largest rendition first — see ArtworkDownload. Hidden only
        // when there is no art to save.
        if (song.thumbnailUrl != null) {
            onSaveArtwork?.let {
                ActionRow(Icons.Rounded.Image, stringResource(R.string.save_cover_art), accent = palette.accent, onClick = it)
            }
        }
        // Last, and only from the player: it is about the track playing right
        // now rather than about the song as a thing in a library, and it is
        // the one row here nobody reaches for by accident.
        onCopyLog?.let {
            ActionRow(Icons.Rounded.BugReport, stringResource(R.string.copy_log), accent = palette.accent, onClick = it)
        }
    }

    // Every surface carries the same rows, in the same order and with the
    // same glyphs: it is the same menu wherever it hangs.
    CompositionLocalProvider(
        LocalActionRowStyle provides ActionRowStyle.Menu,
        LocalMenuInk provides palette.onBackground,
    ) {
        Column(
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            if (pickingSleepTimer) {
                SleepTimerPicker(
                    palette = palette,
                    onBack = { pickingSleepTimer = false },
                    ink = palette.onBackground,
                    mutedInk = palette.onBackgroundVariant,
                )
            } else {
                rows()
            }
        }
    }
}

/** Whose ink [SongActionsSheet]'s rows are drawn in — see its `presentation`. */
enum class SongActionsPresentation { Menu, Glass }

/**
 * Which surface an [ActionRow] is on: a menu ([MenuRow]), or one of the
 * sheets that are still sheets — the playlist and folder pickers.
 */
internal enum class ActionRowStyle { Sheet, Menu }

internal val LocalActionRowStyle = staticCompositionLocalOf { ActionRowStyle.Sheet }

/**
 * The ink for rows drawn in a menu.
 *
 * A menu's glass shows whatever is behind it, so the theme's own greys have
 * nothing dependable to sit against over artwork: there the artwork decides
 * whether a label has to be white or near-black. Null is the theme's surface
 * foreground, which is right over a page.
 */
internal val LocalMenuInk = staticCompositionLocalOf<Color?> { null }

/**
 * A menu's colours over a page: the theme's own, so it reads the same in light
 * and dark whatever the artwork is. Shaped as an [ArtworkPalette] so every row
 * takes it exactly as it takes a sleeve's in the player's menu.
 */
@Composable
private fun neutralMenuPalette(): ArtworkPalette {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) {
        ArtworkPalette(
            background = scheme.surfaceContainerHigh,
            wash = scheme.surfaceContainerHigh,
            elevated = scheme.surfaceContainerHighest,
            accent = scheme.primary,
            onBackground = scheme.onSurface,
            onBackgroundVariant = scheme.onSurfaceVariant,
            divider = scheme.onSurface.copy(alpha = 0.08f),
        )
    }
}

/** The break between groups of rows — see [MenuDivider]. */
@Composable
private fun GroupSeparator(palette: ArtworkPalette) {
    MenuDivider(
        color = palette.divider,
        density = if (LocalActionRowStyle.current == ActionRowStyle.Menu) MenuDensity.Menu else MenuDensity.Sheet,
    )
}

/**
 * One row carrying the whole life of a download: start it, watch it, cancel it,
 * and delete what it produced.
 *
 * A row rather than a screen because that is the size of the decision. The
 * files land in the device's own Music folder, which already has a manager
 * — the Files app — and building a second one inside this app would be
 * duplicating it in a worse place. What this app uniquely knows is which *song*
 * a file belongs to, and that is exactly what this row says.
 *
 * The state comes straight from [Downloads] rather than through the caller: it
 * changes while the sheet is open, and threading a flow through the sheet's
 * signature would buy nothing over reading it where it's drawn — the same
 * arrangement the sleep timer row already uses.
 */
@Composable
private fun DownloadRow(song: Song, palette: ArtworkPalette, isOffline: Boolean, onDownload: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val active by Downloads.active.collectAsStateWithLifecycle()
    val saved by Downloads.saved.collectAsStateWithLifecycle()

    // The record is a claim about a folder the user manages themselves, so it
    // is checked against the disk rather than trusted — re-checked whenever the
    // record for this track changes, which is what makes the row settle onto
    // "Saved" the moment a download finishes.
    //
    // Seeded with the record rather than with null so that moment isn't a
    // flicker: a finished download clears the running state and writes the
    // record in the same breath, and starting pessimistic would show "Download"
    // again for as long as the check off the main thread takes.
    val recorded = saved[song.videoId]
    val file by produceState(recorded?.let(Uri::parse), song.videoId, recorded) {
        value = Downloads.savedUri(context, song.videoId)
    }

    // A failure is worth stating once. Leaving it set would have the row still
    // reporting last week's dead connection the next time the menu is opened.
    // Only a failure this row actually showed is cleared — see [seenWhenOnScreen].
    val shownFailure = remember(song.videoId) { arrayOfNulls<DownloadState.Failed>(1) }
    DisposableEffect(song.videoId) {
        onDispose { shownFailure[0]?.let { Downloads.dismissFailure(song.videoId, it) } }
    }

    val state = active[song.videoId]
    val interactive = LocalMenuInteractive.current
    when (state) {
        is DownloadState.Queued -> ActionRow(
            icon = Icons.Rounded.Downloading,
            label = stringResource(R.string.queued),
            value = stringResource(R.string.cancel),
            accent = palette.accent,
        ) { Downloads.cancel(song.videoId) }

        is DownloadState.Running -> ActionRow(
            icon = Icons.Rounded.Downloading,
            label = stringResource(R.string.download_notification_title),
            // Indeterminate until the first response names a length; a
            // stuck "0%" reads as broken where a bare label reads as starting.
            value = if (state.fraction > 0f) "${(state.fraction * 100).toInt()}%" else null,
            tint = palette.accent,
            accent = palette.accent,
        ) { Downloads.cancel(song.videoId) }

        is DownloadState.Failed -> ActionRow(
            icon = Icons.Rounded.ErrorOutline,
            label = state.reason,
            value = stringResource(R.string.retry),
            // Not the artwork's colour: a failure has to stay legible as a
            // failure whatever the sleeve happens to be tinted.
            tint = MaterialTheme.colorScheme.error,
            accent = MaterialTheme.colorScheme.error,
            modifier = if (interactive) {
                Modifier.seenWhenOnScreen { shownFailure[0] = state }
            } else {
                // A menu on its way out: drawn, but no longer being read.
                Modifier
            },
            onClick = onDownload,
        )

        null -> if (file != null) {
            ActionRow(
                icon = Icons.Rounded.DownloadDone,
                label = stringResource(R.string.saved_to_downloads),
                value = stringResource(R.string.delete),
                tint = palette.accent,
                accent = palette.accent,
            ) { scope.launch { Downloads.delete(context, song.videoId) } }
        } else if (!isOffline) {
            ActionRow(
                icon = Icons.Rounded.Download,
                label = stringResource(R.string.download),
                accent = palette.accent,
                onClick = onDownload,
            )
        }
    }
}

/**
 * Calls [onSeen] once at least half of this element has been inside every
 * clip around it — for a row in a menu, the menu's visible, scrolled part.
 *
 * What a row that reports something once (a failed download) uses to decide
 * it has been read: a row scrolled out of view was composed but never seen.
 * Asked from this one element's own placement — a comparison each time it
 * moves, as the menu scrolls — so no other row pays for it.
 */
internal fun Modifier.seenWhenOnScreen(onSeen: () -> Unit): Modifier = onGloballyPositioned { coordinates ->
    if (isHalfOnScreen(coordinates.size.height, coordinates.boundsInWindow().height)) onSeen()
}

/** Whether a row [height] px tall, of which [visibleHeight] px are unclipped, counts as on screen. */
internal fun isHalfOnScreen(height: Int, visibleHeight: Float): Boolean =
    height > 0 && visibleHeight * 2f >= height

/**
 * Sends a device file to the WebDAV server. Sits beside the download row
 * because it is the same idea in the other direction — and like that row it
 * reads its state where it is drawn rather than threading it through the
 * sheet's signature.
 *
 * Shown only for readable local files. A track already on the server, or a
 * stream with no bytes on this device, has nothing to send up.
 */
@Composable
private fun WebDavUploadRow(
    song: Song,
    palette: ArtworkPalette,
    isOffline: Boolean,
    onUpload: (() -> Unit)?,
) {
    onUpload ?: return
    if (!isOffline || !WebDavUploads.isUploadable(song)) return
    val active by WebDavUploads.active.collectAsStateWithLifecycle()
    when (val state = active[song.videoId]) {
        is WebDavUploads.TrackState.Running -> ActionRow(
            icon = Icons.Rounded.FileUpload,
            label = stringResource(R.string.upload_to_webdav),
            value = "${(state.fraction * 100).toInt()}%",
            tint = palette.accent,
            accent = palette.accent,
            // In flight already; there is no cancel, so the row only reports.
            onClick = {},
        )
        is WebDavUploads.TrackState.Failed -> ActionRow(
            icon = Icons.Rounded.FileUpload,
            label = state.reason,
            value = stringResource(R.string.try_again),
            tint = MaterialTheme.colorScheme.error,
            accent = MaterialTheme.colorScheme.error,
            onClick = onUpload,
        )
        else -> ActionRow(
            icon = Icons.Rounded.FileUpload,
            label = stringResource(R.string.upload_to_webdav),
            accent = palette.accent,
            onClick = onUpload,
        )
    }
}

/** End of track or a duration, plus a way out once one is running. */
@Composable
private fun SleepTimerPicker(
    palette: ArtworkPalette,
    onBack: () -> Unit,
    /** The ink for the labels — the theme's own, or the artwork's over glass. */
    ink: Color = MaterialTheme.colorScheme.onBackground,
    mutedInk: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val chosen by SleepTimer.minutes.collectAsStateWithLifecycle()
    val afterTrack by SleepTimer.afterTrack.collectAsStateWithLifecycle()
    val countdown = sleepTimerCountdown()

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 22.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, enabled = LocalMenuInteractive.current) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = ink,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.sleep_timer),
                    style = MaterialTheme.typography.titleLarge,
                    color = ink,
                )
                Text(
                    text = when {
                        countdown != null -> stringResource(R.string.pause_countdown, countdown)
                        afterTrack -> stringResource(R.string.pause_after_song)
                        else -> stringResource(R.string.pause_after_a_while)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = mutedInk,
                )
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = palette.divider)

        // Finishing the song is the one people reach for at the end of a
        // listen, so it leads rather than sitting under the durations.
        SleepOption(
            label = stringResource(R.string.after_this_song),
            selected = afterTrack,
            accent = palette.accent,
        ) {
            SleepTimer.startAfterTrack()
            onBack()
        }
        SleepTimer.PRESETS.forEach { minutes ->
            SleepOption(
                label = if (minutes == 60) {
                    stringResource(R.string.one_hour)
                } else {
                    pluralStringResource(R.plurals.minute_count, minutes, minutes)
                },
                selected = minutes == chosen,
                accent = palette.accent,
            ) {
                SleepTimer.start(minutes)
                onBack()
            }
        }
        if (chosen != null || afterTrack) {
            ActionRow(Icons.Rounded.Close, stringResource(R.string.turn_off_timer), accent = palette.accent) {
                SleepTimer.cancel()
                onBack()
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SleepOption(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val ink = LocalMenuInk.current ?: MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = LocalMenuInteractive.current, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = ink,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = stringResource(R.string.running),
                tint = accent,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** What the Sing row shows on the right, or null while Sing is off. */
@Composable
private fun singStatus(): String? {
    val level by AppSettings.singLevel.collectAsStateWithLifecycle()
    if (level <= 0.001f) return null
    return stringResource(R.string.sing_level_percent, (level * 100).roundToInt())
}

/** What the sleep timer row shows on the right, or null when none is armed. */
@Composable
private fun sleepTimerStatus(): String? {
    val afterTrack by SleepTimer.afterTrack.collectAsStateWithLifecycle()
    return sleepTimerCountdown() ?: stringResource(R.string.after_this_song).takeIf { afterTrack }
}

/** Live "m:ss" until the sleep timer fires, or null when none is running. */
@Composable
private fun sleepTimerCountdown(): String? {
    val deadline by SleepTimer.deadline.collectAsStateWithLifecycle()
    val remaining by produceState<Long?>(initialValue = SleepTimer.remainingMs(), deadline) {
        while (deadline != null) {
            value = SleepTimer.remainingMs()
            delay(1_000)
        }
        value = null
    }
    return remaining?.let {
        val seconds = it / 1000
        "%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
    }
}

/**
 * One line of a menu, or of the playlist and folder pickers — the sheets that
 * are still sheets. Shared so the two read as the same control rather than as
 * two lists that happen to look alike.
 *
 * In a menu it is [MenuRow]; on a sheet, the sheet-density row.
 *
 * [tint] is for rows whose icon carries state — a filled heart on a liked
 * track — and is otherwise the ordinary foreground. [accent] colours the
 * trailing [value], and defaults to the app's own red: the player's menu
 * passes the artwork's accent instead, so the row belongs to what it is drawn on.
 */
@Composable
internal fun ActionRow(
    icon: ImageVector,
    label: String,
    value: String? = null,
    tint: Color? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    if (LocalActionRowStyle.current == ActionRowStyle.Menu) {
        MenuRow(
            icon = icon,
            label = label,
            modifier = modifier,
            ink = LocalMenuInk.current ?: MaterialTheme.colorScheme.onSurface,
            value = value,
            valueColor = accent,
            iconTint = tint,
            enabled = enabled,
            onClick = onClick,
        )
        return
    }
    val foreground = MaterialTheme.colorScheme.onBackground
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = modifier
            .fillMaxWidth()
            // A sheet on its way out stops answering — see AppSheet.
            .clickable(enabled = enabled && LocalMenuInteractive.current, onClick = onClick)
            .padding(horizontal = MenuTokens.SheetRowPaddingH, vertical = MenuTokens.SheetRowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = (tint ?: foreground).copy(alpha = alpha),
            modifier = Modifier.size(MenuTokens.SheetIcon),
        )
        Spacer(Modifier.width(MenuTokens.IconGap))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = foreground.copy(alpha = alpha),
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = accent.copy(alpha = alpha),
                maxLines = 1,
            )
        }
    }
}

/**
 * Stands in for [ActionRow] while whether it belongs on the sheet at all is
 * still unknown — "Open album" or "Open artist" before the lookup for their
 * ids has come back. A spinner rather than the row simply being missing, so
 * it doesn't read as decided against until it actually is.
 */
@Composable
private fun LoadingActionRow(icon: ImageVector, label: String, palette: ArtworkPalette) {
    MenuRow(
        icon = icon,
        label = label,
        ink = palette.onBackground,
        enabled = false,
        trailing = {
            CircularProgressIndicator(
                color = palette.onBackgroundVariant,
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp),
            )
        },
        onClick = null,
    )
}

/** The track the playlist picker is adding, drawn at its head. */
@Composable
internal fun SheetTrackHeader(
    song: Song,
    modifier: Modifier = Modifier,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = song.artworkAt(ROW_ART_PX),
            contentDescription = null,
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(8.dp))
                .thumbnailBorder(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            ExplicitSongTitle(
                song = song,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = subtitleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The heading over a sheet's second half — "Add to playlist". */
@Composable
internal fun SheetHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 4.dp),
    )
}
