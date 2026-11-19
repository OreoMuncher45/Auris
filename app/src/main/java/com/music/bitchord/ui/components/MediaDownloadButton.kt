package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.download.DownloadState
import com.music.bitchord.download.Downloads

/**
 * The download button on a podcast episode and an audiobook chapter.
 *
 * The two screens that show a recording rather than a catalogue entry have no
 * track menu to hang a download row off, so the button is the whole of it —
 * one tap to take the episode home, one tap to stop that, one tap to try again
 * when it failed, and a tick once it is on the device.
 *
 * The state is read where it is drawn rather than threaded in: a download
 * changes while the screen is open, and the row that started it is not the
 * only one that has to know.
 */
@Composable
fun MediaDownloadButton(
    videoId: String,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active by Downloads.active.collectAsStateWithLifecycle()
    val saved by Downloads.saved.collectAsStateWithLifecycle()

    when (val state = active[videoId]) {
        is DownloadState.Queued -> IconButton(onClick = { Downloads.cancel(videoId) }, modifier = modifier) {
            Icon(
                imageVector = Icons.Rounded.Downloading,
                contentDescription = stringResource(R.string.cancel),
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        is DownloadState.Running -> IconButton(onClick = { Downloads.cancel(videoId) }, modifier = modifier) {
            Box(contentAlignment = Alignment.Center) {
                if (state.fraction > 0f) {
                    CircularProgressIndicator(
                        progress = { state.fraction },
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Downloading,
                        contentDescription = stringResource(R.string.download),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        is DownloadState.Failed -> IconButton(onClick = onDownload, modifier = modifier) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = stringResource(R.string.retry),
                tint = MaterialTheme.colorScheme.error,
            )
        }

        null -> if (saved[videoId] != null) {
            IconButton(onClick = {}, modifier = modifier) {
                Icon(
                    imageVector = Icons.Rounded.DownloadDone,
                    contentDescription = stringResource(R.string.saved_to_downloads),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            IconButton(onClick = onDownload, modifier = modifier) {
                Icon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = stringResource(R.string.download),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
