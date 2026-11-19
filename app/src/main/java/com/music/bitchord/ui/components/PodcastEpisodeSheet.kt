package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.BookmarkRemove
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.HistoryToggleOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.download.DownloadState
import com.music.bitchord.download.Downloads

/**
 * What can be done to a podcast episode.
 *
 * A podcast episode is not a song: it cannot be rated against a catalogue,
 * queued into a playlist or fetched from a source, because it has no place in
 * any of those. What it *can* be is taken home, kept, and taken off the
 * shelf of things half-heard — which is what this sheet is: the four things
 * that mean anything for an episode, and nothing that does not.
 *
 * The sheet is the same one whether it was opened from an episode's row or
 * from a card on the "Continue listening" shelf, because an episode reached
 * from either place is the same episode.
 */
@Composable
fun PodcastEpisodeSheet(
    episode: Song,
    isLiked: Boolean,
    isLater: Boolean,
    isInContinueListening: Boolean,
    onDownload: () -> Unit,
    onSmartDownload: () -> Unit,
    onToggleLike: () -> Unit,
    onToggleLater: () -> Unit,
    onRemoveFromContinueListening: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active by Downloads.active.collectAsStateWithLifecycle()
    val saved by Downloads.saved.collectAsStateWithLifecycle()

    Column(modifier.fillMaxWidth()) {
        SheetTrackHeader(episode)
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)

        when (val state = active[episode.videoId]) {
            is DownloadState.Queued -> ActionRow(
                icon = Icons.Rounded.Downloading,
                label = stringResource(R.string.queued),
                value = stringResource(R.string.cancel),
                onClick = { Downloads.cancel(episode.videoId) },
            )

            is DownloadState.Running -> ActionRow(
                icon = Icons.Rounded.Downloading,
                label = stringResource(R.string.download_notification_title),
                value = if (state.fraction > 0f) "${(state.fraction * 100).toInt()}%" else null,
                onClick = { Downloads.cancel(episode.videoId) },
            )

            is DownloadState.Failed -> ActionRow(
                icon = Icons.Rounded.Downloading,
                label = state.reason,
                value = stringResource(R.string.retry),
                tint = MaterialTheme.colorScheme.error,
                onClick = onDownload,
            )

            null -> if (saved[episode.videoId] != null) {
                ActionRow(
                    icon = Icons.Rounded.DownloadDone,
                    label = stringResource(R.string.saved_to_downloads),
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = {},
                )
            } else {
                ActionRow(
                    icon = Icons.Rounded.Download,
                    label = stringResource(R.string.download_episode),
                    onClick = onDownload,
                )
            }
        }

        // The smart one: this episode and the few that came after it, so the
        // next hours of a commute are on the device without a second thought.
        ActionRow(
            icon = Icons.Rounded.AutoAwesome,
            label = stringResource(R.string.smart_download),
            value = stringResource(R.string.smart_download_cover),
            onClick = onSmartDownload,
        )

        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)

        ActionRow(
            icon = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            label = stringResource(if (isLiked) R.string.unlike_episode else R.string.like_episode),
            tint = if (isLiked) MaterialTheme.colorScheme.primary else null,
            onClick = onToggleLike,
        )

        ActionRow(
            icon = if (isLater) Icons.Rounded.BookmarkRemove else Icons.Rounded.BookmarkAdd,
            label = stringResource(
                if (isLater) R.string.remove_from_episodes_for_later else R.string.add_to_episodes_for_later,
            ),
            tint = if (isLater) MaterialTheme.colorScheme.primary else null,
            onClick = onToggleLater,
        )

        // Only there when it is something to do: an episode that was never
        // on the shelf cannot be taken off it.
        if (isInContinueListening) {
            ActionRow(
                icon = Icons.Rounded.HistoryToggleOff,
                label = stringResource(R.string.remove_from_continue_listening),
                onClick = onRemoveFromContinueListening,
            )
        }
    }
}
