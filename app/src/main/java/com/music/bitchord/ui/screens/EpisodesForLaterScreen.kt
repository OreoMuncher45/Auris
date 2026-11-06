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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.music.bitchord.ui.components.PAGE_GUTTER

/**
 * The episodes put aside, as a playlist.
 *
 * "Episodes for later" is the listener's own list: starred here, unstarred
 * there, and it has to behave like the playlists elsewhere in the app — play
 * the lot from the top, shuffle it, take one off without disturbing the rest.
 *
 * The shelf on the Podcasts page shows a handful of these; this is the set
 * behind the arrow at the end of its heading.
 */
@Composable
fun EpisodesForLaterScreen(
    episodes: List<Song>,
    onPlay: (Song) -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onRemove: (Song) -> Unit,
    onMore: (Song) -> Unit,
    listState: LazyListState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item(key = "later:header") {
            LaterHeader(
                count = episodes.size,
                onPlayAll = onPlayAll,
                onShuffle = onShuffle,
                enabled = episodes.isNotEmpty(),
            )
        }
        if (episodes.isEmpty()) {
            item(key = "later:empty") {
                Text(
                    text = stringResource(R.string.episodes_for_later_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 24.dp),
                )
            }
        } else {
            items(episodes, key = { it.videoId }) { episode ->
                LaterEpisodeRow(
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
 * The top of the list: what it is, and the two ways to start it.
 *
 * Play all runs the list in the order it was put aside, which is the order
 * the listener built it in. Shuffle is the other thing anybody does with a
 * pile of episodes they have been meaning to get to.
 */
@Composable
private fun LaterHeader(
    count: Int,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    enabled: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.episodes_for_later),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = PAGE_GUTTER),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.episodes_for_later_subtitle, count),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = PAGE_GUTTER),
        )
        if (count > 0) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PAGE_GUTTER),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onPlayAll,
                    enabled = enabled,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    contentPadding = PaddingValues(horizontal = 26.dp, vertical = 14.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.quick_picks_play_all),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(enabled = enabled, onClick = onShuffle),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Shuffle,
                        contentDescription = stringResource(R.string.shuffle),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

/**
 * One episode: its cover, its title, the show it is from — and the two
 * things a listener might want to do about it, take it off the list or open
 * its menu.
 */
@Composable
private fun LaterEpisodeRow(
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
            if (episode.artist.isNotBlank()) {
                Text(
                    text = episode.artist,
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
                contentDescription = stringResource(R.string.remove_from_episodes_for_later),
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
