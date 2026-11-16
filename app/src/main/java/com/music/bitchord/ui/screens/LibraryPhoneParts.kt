package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.spotify_logo
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.download.Downloads
import com.music.bitchord.download.SavedCollection
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.ReplayCardRowSkeleton
import com.music.bitchord.ui.replay.ReplayCardRow
import com.music.bitchord.ui.replay.ReplayHeroCard
import com.music.bitchord.ui.replay.ReplayStoryPage

/** The Library's way in to LibriVox's public-domain books. */
const val AUDIOBOOKS_BROWSE_ID = "librivox:books"

// The phone's halves of the shared Library page: its list of folders, what
// sits on its "On device" shelf, and the way in to Replay at its head.

/**
 * The phone's folders, drawn as a list under the Replay cards: Downloads and
 * Local Music, the song cache when Settings has it shown, and the two remote
 * libraries it can reach.
 */
@Composable
fun libraryLinks(): List<LibraryLink> {
    val webdavConfigured by AppSettings.webdavUrl.collectAsStateWithLifecycle()
    val smbHost by AppSettings.smbHost.collectAsStateWithLifecycle()
    val smbShare by AppSettings.smbShare.collectAsStateWithLifecycle()
    val showCacheFolder by AppSettings.showCacheFolder.collectAsStateWithLifecycle()
    val spotifyConnected by AppSettings.spotifySpdcToken.collectAsStateWithLifecycle()
    fun link(icon: ImageVector, title: String, subtitle: String, browseId: String) = LibraryLink(
        item = ShelfItem(
            title = title,
            subtitle = subtitle,
            thumbnailUrl = null,
            videoId = null,
            browseId = browseId,
        ),
        icon = icon,
    )
    val downloadsLabel = stringResource(R.string.downloads)
    val downloadedSongsLabel = stringResource(R.string.downloaded_songs)
    val localMusicLabel = stringResource(R.string.local_music)
    val audioFilesOnDeviceLabel = stringResource(R.string.audio_files_on_device)
    val audiobooksLabel = stringResource(R.string.audiobooks)
    val audiobooksSubtitleLabel = stringResource(R.string.audiobooks_subtitle)
    val cachedSongsLabel = stringResource(R.string.cached_songs)
    val cachedSongsSubtitleLabel = stringResource(R.string.cached_songs_subtitle)
    val webdavLabel = stringResource(R.string.webdav)
    val webdavSubtitleLabel = stringResource(R.string.webdav_subtitle)
    val smbLabel = stringResource(R.string.smb)
    val smbSubtitleLabel = stringResource(R.string.smb_subtitle)
    val spotifyLabel = stringResource(R.string.spotify)
    val spotifyLibrarySubtitleLabel = stringResource(R.string.spotify_library_subtitle)
    // Remembered, so the Library is handed the same list until something on
    // it changes: a list is compared by identity, and a fresh one on every
    // call kept LibraryScreen from ever skipping.
    return remember(
        showCacheFolder, webdavConfigured, smbHost, smbShare, spotifyConnected,
        downloadsLabel, downloadedSongsLabel, localMusicLabel, audioFilesOnDeviceLabel,
        audiobooksLabel, audiobooksSubtitleLabel, cachedSongsLabel, cachedSongsSubtitleLabel,
        webdavLabel, webdavSubtitleLabel, smbLabel, smbSubtitleLabel,
        spotifyLabel, spotifyLibrarySubtitleLabel,
    ) {
        listOfNotNull(
            link(
                Icons.Rounded.Download,
                downloadsLabel,
                downloadedSongsLabel,
                "local:downloads",
            ),
            link(
                Icons.Rounded.Folder,
                localMusicLabel,
                audioFilesOnDeviceLabel,
                "local:all",
            ),
            // Public-domain books, read aloud. A library of long things that are
            // not songs — the one shelf a music library is otherwise missing.
            link(
                Icons.Rounded.MenuBook,
                audiobooksLabel,
                audiobooksSubtitleLabel,
                AUDIOBOOKS_BROWSE_ID,
            ),
            // Opt-in from Settings → Storage: what the song cache is holding from
            // YouTube and JioSaavn. See [com.music.bitchord.playback.AudioCache.cachedSongs].
            link(
                Icons.Rounded.Storage,
                cachedSongsLabel,
                cachedSongsSubtitleLabel,
                CACHE_FOLDER_BROWSE_ID,
            ).takeIf { showCacheFolder },
            // Only once set up in Sources; unconfigured, they'd just be dead ends.
            link(
                Icons.Rounded.Cloud,
                webdavLabel,
                webdavSubtitleLabel,
                com.music.bitchord.data.webdav.WebDavConfig.BROWSE_ID,
            ).takeIf { webdavConfigured.isNotBlank() },
            link(
                Icons.Rounded.Lan,
                smbLabel,
                smbSubtitleLabel,
                com.music.bitchord.data.smb.SmbConfig.BROWSE_ID,
            ).takeIf { smbHost.isNotBlank() && smbShare.isNotBlank() },
            // Only while signed in to Spotify in Settings → Accounts; it opens that
            // account's playlists rather than a browse page.
            link(
                Icons.Rounded.Cloud,
                spotifyLabel,
                spotifyLibrarySubtitleLabel,
                SPOTIFY_BROWSE_ID,
            ).takeIf { spotifyConnected.isNotBlank() }?.copy(logo = Res.drawable.spotify_logo),
        )
    }
}

/**
 * The phone's "On device" shelf: the playlists and albums downloaded whole —
 * the same promise the folders above it make, here, now, without a network.
 * Nothing is truncated: the shelf is a row that scrolls, so "all of them"
 * costs nothing.
 */
@Composable
fun libraryDeviceItems(downloadedReleases: List<SavedCollection>): List<ShelfItem> {
    val downloadedPlaylist = stringResource(R.string.downloaded_playlist)
    // Remembered for the same reason as [libraryLinks]: one list per change.
    return remember(downloadedReleases, downloadedPlaylist) {
        downloadedReleases.map { release ->
            ShelfItem(
                title = release.title,
                // The credit the release was downloaded with, because this is also
                // what the page it opens bills itself by — see `headerLines`, which
                // reads the kind and the owner back out of it. Saying "Downloaded
                // playlist" here instead would make that header read "Downloaded
                // playlist" over "PLAYLIST • 12 SONGS", and the shelf this card is on
                // already says where it lives.
                subtitle = release.subtitle.ifBlank { if (release.playlist) downloadedPlaylist else "" },
                thumbnailUrl = release.thumbnailUrl,
                videoId = null,
                browseId = Downloads.pageIdFor(release.id),
            )
        }
    }
}

/** The Library row that opens the connected Spotify account; handled by the app, not a browse page. */
const val SPOTIFY_BROWSE_ID = "app:spotify"

/** The Cached songs folder's page id — one of the `local:` device folders. */
const val CACHE_FOLDER_BROWSE_ID = "local:cache"

/**
 * The phone's way in to Replay: its row of headline cards, placeholders for
 * them while the history is read, and nothing at all while there is not yet
 * enough listening to deal them — Settings still opens Replay either way.
 */
@Composable
fun LibraryReplayEntry(
    cards: List<ReplayHeroCard>,
    loading: Boolean,
    holder: String,
    memberSince: String?,
    onOpenReplay: (ReplayStoryPage) -> Unit,
) {
    if (loading) {
        ReplayCardRowSkeleton()
    } else if (cards.isNotEmpty()) {
        ReplayCardRow(
            cards = cards,
            holder = holder,
            memberSince = memberSince,
            onCardClick = onOpenReplay,
            modifier = Modifier.padding(vertical = 6.dp),
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
        )
    }
}
