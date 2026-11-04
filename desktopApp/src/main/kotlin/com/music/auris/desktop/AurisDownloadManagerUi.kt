package com.music.auris.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * What the download queue is doing, and the two things that can be done to it.
 *
 * Android's `DownloadManagerSheet`: newest ask last, which is the order the queue reaches them in.
 */
@Composable
internal fun AurisDownloadManagerDialog(onDismiss: () -> Unit) {
    val active by AurisDownloadQueue.active.collectAsState()
    val items = remember(active) { active.values.sortedBy(AurisDownloadItem::sequence) }
    val failed = items.count { it.state is AurisDownloadState.Failed }
    val busy = items.any { it.state !is AurisDownloadState.Failed }

    AurisDialogPanel(onDismiss = onDismiss, maxWidth = 520) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(AurisStrings["downloads", "Downloads"], style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text(
                    summary(items.size, failed, busy),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (failed > 0 && !busy) AurisDestructive else AurisSecondary,
                )
            }
            if (busy) {
                TextButton(onClick = AurisDownloadQueue::cancelAll) {
                    Text(AurisStrings["cancel_all", "Cancel all"], color = AurisSecondary)
                }
            } else if (items.isNotEmpty()) {
                TextButton(onClick = AurisDownloadQueue::clearFinished) {
                    Text(AurisStrings["clear", "Clear"], color = AurisSecondary)
                }
            }
        }

        if (items.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
                Text(AurisStrings["nothing_downloading", "Nothing downloading."], color = AurisSecondary)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(items, key = { it.song.videoId }) { item -> DownloadRow(item) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text(AurisStrings["done", "Done"], color = Color.White) }
        }
    }
}

@Composable
private fun DownloadRow(item: AurisDownloadItem) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AurisArtwork(
            item.song.thumbnailUrl,
            Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)),
            px = 120,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.song.title,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when (val state = item.state) {
                is AurisDownloadState.Queued -> Text(
                    AurisStrings["queued", "Queued"],
                    style = MaterialTheme.typography.bodySmall,
                    color = AurisSecondary,
                )
                is AurisDownloadState.Running -> Column {
                    Text(
                        if (state.fraction > 0f) {
                            "Downloading · ${(state.fraction * 100).toInt()}%"
                        } else {
                            "Downloading"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = AurisSecondary,
                    )
                    Spacer(Modifier.height(4.dp))
                    // A length the server never stated leaves the bar indeterminate rather than
                    // pinned at zero, which reads as stalled.
                    if (state.fraction > 0f) {
                        LinearProgressIndicator(
                            progress = { state.fraction },
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                            color = AurisAccent,
                            trackColor = Color.White.copy(alpha = 0.15f),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                            color = AurisAccent,
                            trackColor = Color.White.copy(alpha = 0.15f),
                        )
                    }
                }
                is AurisDownloadState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = AurisAccent,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        state.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = AurisAccent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (item.state is AurisDownloadState.Failed) {
            IconButton(onClick = { AurisDownloadQueue.retry(item.song.videoId) }) {
                Icon(Icons.Rounded.Refresh, AurisStrings["retry", "Retry"], tint = AurisSecondary, modifier = Modifier.size(18.dp))
            }
        }
        IconButton(onClick = { AurisDownloadQueue.cancel(item.song.videoId) }) {
            Icon(Icons.Rounded.Close, AurisStrings["cancel", "Cancel"], tint = AurisSecondary, modifier = Modifier.size(18.dp))
        }
    }
}

internal fun summary(total: Int, failed: Int, busy: Boolean): String = when {
    total == 0 -> "Nothing downloading"
    failed > 0 && !busy -> if (failed == 1) "1 download failed" else "$failed downloads failed"
    total == 1 -> "1 track"
    else -> "$total tracks"
}
