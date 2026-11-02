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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.podcast.ItunesShow
import com.music.bitchord.data.settings.MediaProgress
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.icons.BitChordIcons
import androidx.compose.ui.res.stringResource

/**
 * An Apple-catalogue podcast: its cover and name at the top, its latest
 * episodes as rows underneath. A tap plays the episode straight from its
 * RSS enclosure URL.
 */
@Composable
fun ItunesPodcastScreen(
    show: ItunesShow?,
    episodes: UiState<List<Song>>,
    onPlay: (List<Song>, Int) -> Unit,
    onRetry: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    isLikedShow: Boolean = false,
    onToggleLikeShow: () -> Unit = {},
    onToggleLater: (Song) -> Unit = {},
    isLater: (String) -> Boolean = { false },
    sortNewestFirst: Boolean = true,
    onToggleSort: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        // Explicit ink so no row inherits a dark content colour on a dark
        // glass backdrop (the black-on-black bug).
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        if (show != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(84.dp)
                        .clip(RoundedCornerShape(12.dp)),
                ) {
                    AsyncImage(
                        model = show.artworkUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = show.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (show.artist.isNotBlank()) {
                        Text(
                            text = show.artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onToggleLikeShow) {
                    Icon(
                        imageVector = if (isLikedShow) BitChordIcons.HeartFilled else BitChordIcons.Heart,
                        contentDescription = null,
                        tint = if (isLikedShow) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        when (episodes) {
            UiState.Loading -> MessageState(stringResource(R.string.podcast_episodes_loading))
            is UiState.Error -> MessageState(
                episodes.message,
                actionLabel = stringResource(R.string.retry),
                onAction = onRetry,
            )
            is UiState.Success -> {
                if (episodes.data.isEmpty()) {
                    MessageState(stringResource(R.string.podcast_episodes_empty))
                } else {
                    val sorted = if (sortNewestFirst) episodes.data else episodes.data.reversed()
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = PAGE_GUTTER, vertical = 6.dp),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(onClick = onToggleSort) {
                                    Text(
                                        text = if (sortNewestFirst) "Newest first" else "Oldest first",
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                        itemsIndexed(sorted, key = { _, song -> song.videoId }) { index, song ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = PAGE_GUTTER, vertical = 4.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                                    .clickable { onPlay(sorted, index) }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                            ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${index + 1}.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(32.dp),
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = song.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val meta = listOfNotNull(song.publishedDate, song.durationText).joinToString(" · ")
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
                                val listened = MediaProgress.isListened(song.videoId)
                                val frac = MediaProgress.fraction(song.videoId)
                                val posMs = MediaProgress.positionMs(song.videoId)
                                if (listened) {
                                    Text(
                                        text = stringResource(R.string.media_listened),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                } else if (frac != null && frac > 0f && posMs > 0L) {
                                    Text(
                                        text = stringResource(R.string.media_resume, formatMs(posMs)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                }
                                IconButton(onClick = { onToggleLater(song) }) {
                                    Icon(
                                        imageVector = if (isLater(song.videoId)) BitChordIcons.HeartFilled else BitChordIcons.Heart,
                                        contentDescription = null,
                                        tint = if (isLater(song.videoId)) MaterialTheme.colorScheme.primary
                                               else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            }
                        }
                    }
                }
            }
        }
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
