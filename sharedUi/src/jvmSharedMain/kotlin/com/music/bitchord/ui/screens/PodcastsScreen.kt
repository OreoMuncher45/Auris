package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.podcasts
import com.music.bitchord.sharedui.resources.podcasts_empty
import com.music.bitchord.sharedui.resources.retry
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.PullToRefresh
import com.music.bitchord.ui.components.ShimmerBox
import org.jetbrains.compose.resources.stringResource

/**
 * The podcast shows the account follows.
 *
 * A show is not a playlist and not an album — it is a thing you subscribe to,
 * with episodes under it. YouTube Music keeps those in a feed of their own,
 * and this page reads that feed: the covers, the names, and nothing else. An
 * episode lives on the show's own page, where the show names itself at the
 * top and the episodes hang off it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastsScreen(
    state: UiState<List<ShelfItem>>,
    listState: LazyListState,
    onShowClick: (ShelfItem) -> Unit,
    onRetry: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    pullState: PullToRefreshState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    /** The big "Podcasts" heading; the desktop's pages carry none. */
    showTitle: Boolean = true,
) {
    PullToRefresh(
        refreshing = refreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = modifier,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = podcastColumns(maxWidth)
            LazyColumn(
                state = listState,
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (showTitle) {
                    item {
                        Text(
                            text = stringResource(Res.string.podcasts),
                            style = MaterialTheme.typography.displayLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(
                                start = PAGE_GUTTER,
                                end = PAGE_GUTTER,
                                top = 8.dp,
                                bottom = 14.dp,
                            ),
                        )
                    }
                }
                when (state) {
                    UiState.Loading -> item { PodcastSkeletonRows(columns) }
                    is UiState.Error -> item {
                        MessageState(
                            state.message,
                            actionLabel = stringResource(Res.string.retry),
                            onAction = onRetry,
                        )
                    }
                    is UiState.Success -> {
                        val shows = state.data.distinctBy { it.browseId ?: it.title }
                        if (shows.isEmpty()) {
                            item {
                                MessageState(stringResource(Res.string.podcasts_empty))
                            }
                        } else {
                            val rows = shows.chunked(columns)
                            items(
                                rows,
                                key = { row -> row.first().browseId ?: row.first().title },
                            ) { row ->
                                PodcastShowRow(
                                    row = row,
                                    columns = columns,
                                    onShowClick = onShowClick,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PodcastShowRow(
    row: List<ShelfItem>,
    columns: Int,
    onShowClick: (ShelfItem) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(PODCAST_SPACING),
        modifier = Modifier
            .padding(horizontal = PAGE_GUTTER)
            .padding(bottom = PODCAST_SPACING),
    ) {
        row.forEach { item ->
            ShelfCard(
                item = item,
                onClick = { onShowClick(item) },
                modifier = Modifier.weight(1f),
            )
        }
        // A short last row keeps its cards the same width as the rows above
        // it rather than stretching them to fill the gap.
        repeat(columns - row.size) {
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun PodcastSkeletonRows(columns: Int) {
    Column(Modifier.padding(horizontal = PAGE_GUTTER)) {
        repeat(4) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(PODCAST_SPACING),
                modifier = Modifier.padding(bottom = PODCAST_SPACING),
            ) {
                repeat(columns) {
                    ShimmerBox(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f),
                    )
                }
            }
        }
    }
}

private fun podcastColumns(available: Dp): Int {
    val row = available - PAGE_GUTTER * 2
    return ((row + PODCAST_SPACING) / (PODCAST_MIN_CARD_WIDTH + PODCAST_SPACING))
        .toInt()
        .coerceIn(2, PODCAST_MAX_COLUMNS)
}

private val PODCAST_SPACING = 12.dp
private val PODCAST_MIN_CARD_WIDTH = 150.dp
private const val PODCAST_MAX_COLUMNS = 4
