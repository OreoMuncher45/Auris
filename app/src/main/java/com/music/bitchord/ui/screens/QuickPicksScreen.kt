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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.SongRow

/**
 * The Quick Picks page.
 *
 * Quick Picks is the feed's own answer to "what should I play" — a short list
 * of tracks chosen for the account rather than for a mood. It had a row on
 * Home, which is the right place for a glance and the wrong place for a list:
 * twenty cards sideways is not a list, and the only way to see the whole set
 * was to scroll the feed past it. Here it is a page.
 *
 * Offline the page is the device's own library, for the same reason the Home
 * row is: there is no feed to ask, and the files are what there is. The list
 * is the whole of it there — every downloaded track, not the twenty the shelf
 * shows — because a page that lists a subset of what it can play is a page
 * that cannot be trusted to tell you what is on the device.
 */
@Composable
fun QuickPicksScreen(
    songs: List<Song>,
    /** True when the list is the device's own library rather than the feed's. */
    offline: Boolean,
    onPlay: (Song) -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    currentSong: Song?,
    isPlaying: Boolean,
    onItemLongPress: ((Song) -> Unit)? = null,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item(key = "quick-picks:header") {
            QuickPicksHeader(
                offline = offline,
                count = songs.size,
                onPlayAll = onPlayAll,
                onShuffle = onShuffle,
                enabled = songs.isNotEmpty(),
            )
        }
        if (songs.isEmpty()) {
            item(key = "quick-picks:empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PAGE_GUTTER, vertical = 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(
                            if (offline) {
                                R.string.quick_picks_offline_empty
                            } else {
                                R.string.quick_picks_empty
                            },
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(songs, key = { it.videoId }) { song ->
                SongRow(
                    song = song,
                    onClick = { onPlay(song) },
                    onLongPress = onItemLongPress?.let { { it(song) } },
                    isCurrent = currentSong?.videoId == song.videoId,
                    isPlaying = currentSong?.videoId == song.videoId && isPlaying,
                )
            }
        }
    }
}

/**
 * The page's head: what it is, how much of it there is, and the two ways to
 * start it.
 *
 * The count is on the page because the list below it can be the whole device
 * library, and "18 tracks" is the difference between a suggestion and a
 * library.
 */
@Composable
private fun QuickPicksHeader(
    offline: Boolean,
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = PAGE_GUTTER),
        ) {
            Icon(
                Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.quick_picks),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(
                if (offline) {
                    R.string.quick_picks_offline_subtitle
                } else {
                    R.string.quick_picks_subtitle
                },
            ),
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
                        contentDescription = stringResource(
                            R.string.quick_picks_shuffle,
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}
