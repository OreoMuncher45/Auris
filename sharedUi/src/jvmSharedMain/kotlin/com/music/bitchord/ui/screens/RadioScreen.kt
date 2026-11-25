package com.music.bitchord.ui.screens

import com.music.bitchord.sharedui.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.MoodGenreSection
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.PullToRefresh
import com.music.bitchord.ui.components.ShelfRow
import com.music.bitchord.ui.components.ShimmerBox
import com.music.bitchord.ui.components.contextClick
import com.music.bitchord.ui.components.longPressMenuClickable
import com.music.bitchord.ui.components.thumbnailBorder
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.player.CanvasArtworkPlayer
import com.music.bitchord.ui.player.CanvasContentMode
import com.music.bitchord.ui.player.PlayerSettings
import com.music.bitchord.ui.player.rememberFlowVideo
import com.music.bitchord.ui.theme.rememberArtworkPalette

/**
 * The Radio tab, built the way Apple Music's is: one featured station at the
 * top, then a wall of stations, then the things you played recently as seeds.
 *
 * There is no live broadcast here. A "station" is the app's own endless mix —
 * Flow is the personal one, and each mood or genre opens its station's
 * collection, which is what the catalogue can actually play. A seed card
 * starts a station from what you already listened to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioScreen(
    state: UiState<List<MoodGenreSection>>,
    listState: LazyListState,
    flowItem: ShelfItem?,
    onFlowClick: () -> Unit,
    seeds: List<ShelfItem>,
    onSeedClick: (ShelfItem) -> Unit,
    onStationClick: (MoodGenre) -> Unit,
    onStartStation: () -> Unit,
    /** Opens the moods-and-genres grid, which used to sit on its own tab. */
    onGenresClick: () -> Unit,
    onRetry: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    pullState: PullToRefreshState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    /**
     * The signed-in listener's "Mixed for you" cards; empty hides the
     * section, which is what signed out always is.
     */
    mixes: List<ShelfItem> = emptyList(),
    /** Tapping a mix: plays it. */
    onMixClick: (ShelfItem) -> Unit = {},
    /** Holding a mix: the playlist menu, as on Home. */
    onMixLongPress: ((ShelfItem) -> Unit)? = null,
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
            item(key = "title") {
                Text(
                    text = stringResource(Res.string.radio),
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
            item(key = "featured") {
                RadioFeatureCard(
                    item = flowItem,
                    onClick = onFlowClick,
                )
            }
            item(key = "start_station") {
                StartStationRow(onClick = onStartStation)
            }
            item(key = "genres") {
                GenresRow(onClick = onGenresClick)
            }
            if (mixes.isNotEmpty()) {
                item(key = "mixes") {
                    MixesCarousel(mixes = mixes, onClick = onMixClick, onLongPress = onMixLongPress)
                }
            }
            if (seeds.isNotEmpty()) {
                item(key = "seeds_header") {
                    SectionHeader(stringResource(Res.string.radio_recent_seeds))
                }
                item(key = "seeds") {
                    ShelfRow(
                        contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(seeds) { item ->
                            ShelfCard(
                                item = item,
                                onClick = { onSeedClick(item) },
                            )
                        }
                    }
                }
                item(key = "seeds_gap") { Spacer(Modifier.height(18.dp)) }
            }
            when (state) {
                UiState.Loading -> item(key = "skeleton") {
                    RadioSkeleton()
                }
                is UiState.Error -> item(key = "error") {
                    MessageState(
                        state.message,
                        actionLabel = stringResource(Res.string.retry),
                        onAction = onRetry,
                    )
                }
                is UiState.Success -> {
                    val genres = state.data.flatMap(MoodGenreSection::items)
                        .distinctBy { it.browseId to it.params }
                    if (genres.isNotEmpty()) {
                        item(key = "stations_header") {
                            SectionHeader(stringResource(Res.string.radio_stations))
                        }
                        item(key = "stations") {
                            StationGrid(genres = genres, onStationClick = onStationClick)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The personal station, drawn as Apple's "On Air Now" card: a wide sleeve with
 * the name set over its lower fade. It uses Flow's current cover when there is
 * one and a sunrise gradient when the queue is empty, so the tab never opens
 * on a blank rectangle.
 */
@Composable
private fun RadioFeatureCard(item: ShelfItem?, onClick: () -> Unit) {
    val animatedCanvas by PlayerSettings.animatedCanvas.collectAsStateWithLifecycle()
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER)
            .aspectRatio(1.52f)
            .clip(shape)
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        val art = item?.thumbnailUrl
        if (art != null) {
            AsyncImage(
                model = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF2A1B5C), Color(0xFF6E3BC4), Color(0xFFFF4B7A)),
                        ),
                    ),
            )
        }
        // Flow's own clip plays over whichever still is underneath, so a slow
        // decode leaves the sleeve or the gradient up rather than a hole.
        if (animatedCanvas) rememberFlowVideo(square = false)?.let { clip ->
            CanvasArtworkPlayer(
                canvas = clip,
                isPlaying = true,
                contentMode = CanvasContentMode.CROP,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.08f),
                        0.45f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.78f),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 18.dp, end = 18.dp, bottom = 16.dp),
        ) {
            Text(
                text = stringResource(Res.string.radio_featured).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.78f),
                letterSpacing = 1.4.sp,
            )
            Text(
                text = item?.title ?: "Flow",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(Res.string.radio_featured_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.8f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(14.dp)
                .size(38.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.34f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = BitChordIcons.Play,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * "Your Mixes": the listener's own mixes and the named ones, as cards a size
 * up from Home's, each captioned on a band tinted from its own artwork.
 *
 * One item of the page's list holding a lazy row, so only the cards on screen
 * are composed. The tint is read once per cover from the same small artwork
 * the card draws, which the image cache already holds; nothing is blurred.
 */
@Composable
private fun MixesCarousel(
    mixes: List<ShelfItem>,
    onClick: (ShelfItem) -> Unit,
    onLongPress: ((ShelfItem) -> Unit)?,
) {
    Column(Modifier.padding(top = 8.dp, bottom = 8.dp)) {
        SectionHeader(stringResource(Res.string.mixes_yours))
        ShelfRow(
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(mixes, key = { it.browseId ?: it.title }) { item ->
                MixCard(
                    item = item,
                    onClick = { onClick(item) },
                    onLongPress = onLongPress?.let { { it(item) } },
                    modifier = Modifier.width(MIX_CARD_WIDTH),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MixCard(
    item: ShelfItem,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val art = remember(item.thumbnailUrl) { item.thumbnailUrl.artworkAt(CARD_ART_PX) }
    val palette = rememberArtworkPalette(item.thumbnailUrl, artPx = CARD_ART_PX)
    val shape = RoundedCornerShape(MIX_CARD_CORNER)
    Column(
        modifier = modifier
            .clip(shape)
            .thumbnailBorder(shape)
            .background(palette.wash)
            .longPressMenuClickable(onClick = onClick, onLongClick = onLongPress)
            .contextClick(onLongPress),
    ) {
        AsyncImage(
            model = art,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                color = palette.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = localizeCardSubtitle(item.subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.onBackgroundVariant,
                // Two lines held even when the credit is short, so every card
                // in the row stands the same height.
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** One fixed width on every screen: about one and a half of Home's cards. */
private val MIX_CARD_WIDTH = 240.dp
private val MIX_CARD_CORNER = 14.dp

/**
 * The way into moods and genres.
 *
 * They used to have a tab of their own, and when the podcasts took that
 * slot they lost their front door — so they get one here, next to the radio
 * stations they share a feed with.
 */
@Composable
private fun GenresRow(onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER)
            .padding(top = 8.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = BitChordIcons.TabExplore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.explore),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(Res.string.explore_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** "Start a station" — the one action a catalogue radio page cannot do silently. */
@Composable
private fun StartStationRow(onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER)
            .padding(top = 18.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = BitChordIcons.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.radio_start_station),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(Res.string.radio_start_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = BitChordIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Apple's Radio wall: square station tiles, three to a phone row. */
@Composable
private fun StationGrid(genres: List<MoodGenre>, onStationClick: (MoodGenre) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = (maxWidth / 118.dp).toInt().coerceIn(3, 5)
        Column(
            modifier = Modifier.padding(horizontal = PAGE_GUTTER),
            verticalArrangement = Arrangement.spacedBy(STATION_SPACING),
        ) {
            genres.chunked(columns).forEach { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(STATION_SPACING),
                ) {
                    row.forEach { genre ->
                        StationTile(
                            genre = genre,
                            onClick = { onStationClick(genre) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(columns - row.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun StationTile(genre: MoodGenre, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val wash = remember(genre.stripeColor, genre.title) {
        genre.stripeColor?.let { Color(it.toInt()) } ?: Color(0xFF3A3A3C)
    }
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(wash)
            .clickable(onClick = onClick),
    ) {
        genre.thumbnailUrl?.let { art ->
            AsyncImage(
                model = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to wash.copy(alpha = 0.38f),
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.62f),
                    ),
                ),
        )
        Text(
            text = genre.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 10.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun RadioSkeleton() {
    Column(
        modifier = Modifier.padding(horizontal = PAGE_GUTTER),
        verticalArrangement = Arrangement.spacedBy(STATION_SPACING),
    ) {
        repeat(4) {
            Row(horizontalArrangement = Arrangement.spacedBy(STATION_SPACING)) {
                repeat(3) {
                    ShimmerBox(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f),
                        RoundedCornerShape(16.dp),
                    )
                }
            }
        }
    }
}

private val STATION_SPACING = 10.dp
