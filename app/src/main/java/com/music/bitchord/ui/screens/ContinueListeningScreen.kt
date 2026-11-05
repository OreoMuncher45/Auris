package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.MediaProgress
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.PillTextField

/**
 * Everything the listener has played, and nothing they have not.
 *
 * The shelf on the Podcasts page shows a handful of these; this is the set
 * behind the arrow at the end of its heading — the last fifty episodes played,
 * newest first, searchable by title or by show.
 *
 * Every row can be taken off the list. A history the listener cannot trim is
 * a history they stop trusting, and half of what lands in it is a episode
 * they have finished with and do not want to be offered again.
 */
@Composable
fun ContinueListeningScreen(
    episodes: List<Song>,
    query: String,
    onQueryChange: (String) -> Unit,
    onPlay: (Song) -> Unit,
    onRemove: (Song) -> Unit,
    onMore: (Song) -> Unit,
    listState: LazyListState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val needle = query.trim().lowercase()
    val shown = if (needle.isEmpty()) {
        episodes
    } else {
        episodes.filter {
            it.title.lowercase().contains(needle) || it.artist.lowercase().contains(needle)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item(key = "continue:search") {
            PillTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = stringResource(R.string.search_continue_listening),
                modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
            )
        }
        if (shown.isEmpty()) {
            item(key = "continue:empty") {
                Text(
                    text = stringResource(
                        if (episodes.isEmpty()) R.string.continue_listening_empty else R.string.no_results,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 24.dp),
                )
            }
        } else {
            items(shown, key = { it.videoId }) { episode ->
                ContinueEpisodeRow(
                    episode = episode,
                    onClick = { onPlay(episode) },
                    onRemove = { onRemove(episode) },
                    onMore = { onMore(episode) },
                )
            }
        }
    }
}

/**
 * One played episode: its cover, its title, the show it is from, how far in
 * it was — and the two things a listener might want to do about it, take it
 * off the list or open its menu.
 */
@Composable
private fun ContinueEpisodeRow(
    episode: Song,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onMore: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        AsyncImage(
            model = episode.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = episode.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val position = MediaProgress.positionMs(episode.videoId)
            val listened = MediaProgress.isListened(episode.videoId)
            val meta = listOfNotNull(
                episode.artist.takeIf { it.isNotBlank() },
                when {
                    listened -> stringResource(R.string.media_listened)
                    position > 0L -> stringResource(R.string.media_resume, formatMs(position))
                    else -> null
                },
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.remove_from_continue_listening),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onMore) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = stringResource(R.string.episode_options),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = (ms / 1000).toInt()
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
