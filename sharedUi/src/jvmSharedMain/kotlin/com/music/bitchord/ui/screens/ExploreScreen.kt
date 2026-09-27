package com.music.bitchord.ui.screens

import com.music.bitchord.sharedui.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.MoodGenreSection
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.PullToRefresh
import com.music.bitchord.ui.components.ShimmerBox
import com.music.bitchord.ui.components.feedSkeleton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    state: UiState<List<MoodGenreSection>>,
    listState: LazyListState,
    onCategoryClick: (MoodGenre) -> Unit,
    onRetry: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    pullState: PullToRefreshState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    /** The big "Explore" heading; the desktop's pages carry none. */
    showTitle: Boolean = true,
) {
    PullToRefresh(
        refreshing = refreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = modifier,
    ) {
        LazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (showTitle) {
                item {
                    Text(
                        text = stringResource(Res.string.explore),
                        style = MaterialTheme.typography.displayLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
                    )
                }
            }
            when (state) {
                UiState.Loading -> item { ExploreSkeleton() }
                is UiState.Error -> item {
                    MessageState(state.message, actionLabel = stringResource(Res.string.retry), onAction = onRetry)
                }
                is UiState.Success -> state.data.forEach { section ->
                    item(key = section.title) {
                        MoodGenreGrid(section = section, onCategoryClick = onCategoryClick)
                    }
                }
            }
        }
    }
}

@Composable
private fun MoodGenreGrid(
    section: MoodGenreSection,
    onCategoryClick: (MoodGenre) -> Unit,
) {
    Column(Modifier.padding(bottom = 22.dp)) {
        SectionHeader(section.title)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = moodColumns(maxWidth)
            val cardWidth = (maxWidth - PAGE_GUTTER * 2 - MOOD_SPACING * (columns - 1)) / columns
            Column(
                verticalArrangement = Arrangement.spacedBy(MOOD_SPACING),
                modifier = Modifier.padding(horizontal = PAGE_GUTTER),
            ) {
                section.items.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(MOOD_SPACING)) {
                        row.forEach { item ->
                            MoodGenreCard(
                                item = item,
                                onClick = { onCategoryClick(item) },
                                modifier = Modifier.width(cardWidth),
                            )
                        }
                        // Short rows keep their cards the size of a full row's.
                        repeat(columns - row.size) { Spacer(Modifier.width(cardWidth)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoodGenreCard(
    item: MoodGenre,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = moodColor(item.title)
    Box(
        modifier = modifier
            .height(100.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                Brush.linearGradient(
                    listOf(color, color.copy(red = color.red * .68f, green = color.green * .68f, blue = color.blue * .68f)),
                ),
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                // Push a rotated square beyond the corner, exactly like a
                // cropped album sleeve rather than a floating rectangle.
                .offset(x = 10.dp, y = 12.dp)
                .size(82.dp)
                .graphicsLayer { rotationZ = 16f }
                .clip(RoundedCornerShape(7.dp))
                .background(Color.White.copy(alpha = .22f)),
        ) {
            item.thumbnailUrl?.let { artwork ->
                AsyncImage(
                    model = artwork,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.TopStart).padding(end = 48.dp),
        )
    }
}

private fun moodColor(title: String): Color = when ((title.hashCode() and Int.MAX_VALUE) % 8) {
    0 -> Color(0xFFE64A19)
    1 -> Color(0xFFEC0B65)
    2 -> Color(0xFF8664AC)
    3 -> Color(0xFF6B4EFF)
    4 -> Color(0xFFBE6100)
    5 -> Color(0xFF233C78)
    6 -> Color(0xFF4D97E5)
    else -> Color(0xFFAA267E)
}

/** Gap between mood cards, in both directions. */
private val MOOD_SPACING = 12.dp

/**
 * The narrowest a mood card is let get before the row drops a column.
 *
 * Pitched so every phone still gets its two (the widest, 448dp, has room for
 * 1.8 of these), while a tablet or a window lays out as many as fit rather
 * than two cards each half a screen wide.
 */
private val MOOD_MIN_CARD_WIDTH = 220.dp

private const val MOOD_MAX_COLUMNS = 6

private fun moodColumns(available: Dp): Int {
    val row = available - PAGE_GUTTER * 2
    return ((row + MOOD_SPACING) / (MOOD_MIN_CARD_WIDTH + MOOD_SPACING)).toInt().coerceIn(2, MOOD_MAX_COLUMNS)
}

@Composable
private fun ExploreSkeleton() {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = moodColumns(maxWidth)
        ExploreSkeletonRows(columns)
    }
}

@Composable
private fun ExploreSkeletonRows(columns: Int) {
    Column(Modifier.padding(horizontal = PAGE_GUTTER)) {
        repeat(5) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(MOOD_SPACING),
                modifier = Modifier.padding(bottom = MOOD_SPACING),
            ) {
                repeat(columns) {
                    Box(Modifier.weight(1f).height(100.dp).clip(RoundedCornerShape(8.dp))) {
                        ShimmerBox(Modifier.fillMaxSize(), RoundedCornerShape(8.dp))
                        ShimmerBox(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .offset(x = 10.dp, y = 12.dp)
                                .size(82.dp)
                                .graphicsLayer { rotationZ = 16f },
                            RoundedCornerShape(7.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodGenrePlaylistsScreen(
    title: String,
    state: UiState<List<HomeShelf>>,
    listState: LazyListState,
    onItemClick: (ShelfItem) -> Unit,
    onRetry: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = modifier.fillMaxSize(),
    ) {
        item {
            Text(
                text = title,
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
            )
        }
        when (state) {
            UiState.Loading -> feedSkeleton()
            is UiState.Error -> item {
                MessageState(state.message, actionLabel = stringResource(Res.string.retry), onAction = onRetry)
            }
            is UiState.Success -> items(state.data, key = { it.title }) { shelf ->
                Shelf(shelf = shelf, onItemClick = onItemClick)
            }
        }
    }
}
