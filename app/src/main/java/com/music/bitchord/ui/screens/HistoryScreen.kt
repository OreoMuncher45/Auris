package com.music.bitchord.ui.screens

import com.music.bitchord.ui.components.LazyContent
import com.music.bitchord.ui.components.rememberSongKeys
import com.music.bitchord.R

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import com.music.bitchord.ui.MainViewModel
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.isSameTrackAs
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.PlayingAccent
import com.music.bitchord.ui.components.ROW_DIVIDER_INSET
import com.music.bitchord.ui.components.SongRow
import com.music.bitchord.ui.components.songListSkeleton

/**
 * What the account has been listening to, most recent first.
 *
 * A plain list rather than a page with a header: no single cover stands for a
 * history, and borrowing one — the newest track's, say — would claim the page
 * belonged to it.
 *
 * Tapping a row plays it with the rest of the history behind it, so the list
 * doubles as a queue that has already been approved once. That is the useful
 * reading of a history: not a receipt, but everything worth playing again.
 */
@Composable
fun HistoryScreen(
    state: UiState<List<Song>>,
    listState: LazyListState,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    onRetry: () -> Unit,
    contentPadding: PaddingValues,
    currentSong: Song? = null,
    isPlaying: Boolean = false,
    /** The last removal YouTube refused; that one row pulses. See [MainViewModel.removeFromHistory]. */
    removalFailure: MainViewModel.HistoryRemovalFailure? = null,
    modifier: Modifier = Modifier,
) {
    // The videoId alone is not a key here: the feed is deduplicated on it, but
    // a list keyed on something that could repeat is one bad response away
    // from a crash, so repeats are counted off. Not the index, which a new
    // play at the top shifted for every row below it — the list then rebuilt
    // the whole history rather than slotting one row in.
    val keys = rememberSongKeys((state as? UiState.Success)?.data.orEmpty())
    // Read at tap time rather than captured per row. A removal is a new list
    // with every row below it one place up; a click lambda that captured the
    // list and the index would differ for every one of those rows, and each
    // would recompose to take its new lambda. Through these the lambdas only
    // capture the row's own key, so the rows that merely moved skip.
    val currentSongs by rememberUpdatedState((state as? UiState.Success)?.data.orEmpty())
    val currentKeys by rememberUpdatedState(keys)
    val currentOnSongClick by rememberUpdatedState(onSongClick)
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        when (state) {
            is UiState.Loading -> songListSkeleton(count = 10, keyPrefix = "skeleton:history")

            is UiState.Error -> item(key = "history:message") {
                MessageState(
                    message = state.message,
                    actionLabel = stringResource(R.string.try_again),
                    onAction = onRetry,
                )
            }

            is UiState.Success -> {
                val songs = state.data
                itemsIndexed(songs, keys) { index, song ->
                    val key = keys[index]
                    // The row and its divider leave together and the rows
                    // below close the gap — a removal. The fade is a layer
                    // alpha and the slide a placement offset, both applied
                    // without relayout; the specs are constants, so nothing
                    // is allocated per frame. No fade in: rows appearing on
                    // load arrive as they always have.
                    Column(
                        Modifier
                            .animateItem(
                                fadeInSpec = null,
                                fadeOutSpec = HISTORY_ROW_FADE_OUT,
                                placementSpec = HISTORY_ROW_PLACEMENT,
                            )
                            .then(removalPulse(removalFailure?.takeIf { it.token == song.feedbackToken })),
                    ) {
                        SongRow(
                            song = song,
                            onClick = {
                                val at = currentKeys.indexOf(key)
                                if (at >= 0) currentOnSongClick(currentSongs, at)
                            },
                            onLongPress = { onSongLongPress(song) },
                            onSwipeToQueue = { onSongSwipe(song) },
                            isCurrent = song.isSameTrackAs(currentSong),
                            isPlaying = song.isSameTrackAs(currentSong) && isPlaying,
                            searchPlayingStyle = true,
                            activeTint = PlayingAccent,
                        )
                        if (index < songs.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = ROW_DIVIDER_INSET),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
            }
        }
    }
}

private val HISTORY_ROW_FADE_OUT = tween<Float>(durationMillis = 150, easing = FastOutLinearInEasing)
private val HISTORY_ROW_PLACEMENT = tween<IntOffset>(
    durationMillis = 220,
    easing = CubicBezierEasing(0.2f, 0f, 0f, 1f),
)

/**
 * One soft dip in opacity on the row whose removal YouTube refused, so the
 * row that stayed is the one the toast is about. Only the row named by
 * [failure] gets an [Animatable]; the alpha is read inside the layer block, so
 * the pulse redraws that row's layer without recomposing or relaying it out.
 */
@Composable
private fun removalPulse(failure: MainViewModel.HistoryRemovalFailure?): Modifier {
    if (failure == null) return Modifier
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(failure) {
        alpha.animateTo(0.55f, HISTORY_PULSE_STEP)
        alpha.animateTo(1f, HISTORY_PULSE_STEP)
    }
    return remember(alpha) { Modifier.graphicsLayer { this.alpha = alpha.value } }
}

private val HISTORY_PULSE_STEP = tween<Float>(durationMillis = 150)

/** Keyed by [keys], for the reason given where they are made. */
private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexed(
    songs: List<Song>,
    keys: List<String>,
    row: @Composable androidx.compose.foundation.lazy.LazyItemScope.(Int, Song) -> Unit,
) = items(
    count = songs.size,
    key = { keys[it] },
    contentType = { LazyContent.SONG_ROW },
) { index -> row(index, songs[index]) }
