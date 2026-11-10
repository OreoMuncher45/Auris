package com.music.bitchord.ui.screens

import com.music.bitchord.ui.components.contextClick
import com.music.bitchord.ui.AppUi
import com.music.bitchord.sharedui.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.DrawableResource
import androidx.compose.foundation.Image
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.music.bitchord.ui.components.longPressMenuClickable
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import com.music.bitchord.ui.components.artworkDeparture
import com.music.bitchord.ui.components.rememberArtworkTap
import com.music.bitchord.ui.components.ShelfRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cached
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Storage
import com.music.bitchord.ui.icons.BitChordIcons
import coil3.compose.AsyncImage
import com.music.bitchord.data.flow.FlowRules
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.HEADER_ART_PX
import com.music.bitchord.data.model.HomeChip
import com.music.bitchord.data.model.HomeHeader
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.isQuickPicksShelfTitle
import java.util.Locale
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.settings.LibraryViewType
import com.music.bitchord.ui.components.HERO_CARD_RATIO
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.RowMoreButton
import com.music.bitchord.ui.components.PlayingAccent
import com.music.bitchord.ui.components.PullToRefresh
import com.music.bitchord.ui.components.SHELF_CARD_WIDTH
import com.music.bitchord.ui.components.SearchPlayingBars
import com.music.bitchord.ui.components.SignInBanner
import com.music.bitchord.ui.components.feedMoreSkeleton
import com.music.bitchord.ui.components.feedSkeleton
import com.music.bitchord.ui.components.recentlyPlayedSkeleton
import com.music.bitchord.ui.components.heroCardWidth
import com.music.bitchord.ui.components.thumbnailBorder
import com.music.bitchord.ui.components.trackColumnWidth
import com.music.bitchord.ui.player.CanvasArtworkPlayer
import com.music.bitchord.ui.player.CanvasContentMode
import com.music.bitchord.ui.player.MeshGradientBackground
import com.music.bitchord.ui.player.MeshPalette
import com.music.bitchord.ui.player.PlayerSettings
import com.music.bitchord.ui.player.rememberFlowVideo

/** How far the Listen now heading and feed sit above the shared top inset. */
private val HOME_TITLE_LIFT = 28.dp

private const val RECENTS_TITLE = "Recents"
private const val RECENT_TRACKS_PER_COLUMN = 4

/**
 * The shelf name the quick-access tiles are reported under.
 *
 * They are not a shelf, but every card on the page carries the name of the
 * row it came from — it is what the playback source is called — so the tiles
 * need one too.
 */
private const val SHORTCUT_SHELF_TITLE = "Shortcuts"

private fun ShelfItem.matchesCurrentlyPlaying(song: Song?): Boolean {
    song ?: return false
    if (videoId == null) return false
    return videoId == song.videoId || (title == song.title && subtitle == song.artist)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: UiState<List<HomeShelf>>,
    listState: LazyListState,
    /** The tapped item and the recommendation shelf it came from. */
    onItemClick: (ShelfItem, String) -> Unit,
    onRetry: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    pullState: PullToRefreshState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    /** The page's heading, or null for none — the desktop's pages carry no heading. */
    title: String?,
    /**
     * What the page says above its shelves.
     *
     * A greeting, a row of chips and a grid of quick-access tiles. Spotify's
     * own home has all three, and they are not shelves: a greeting is not a
     * row of things, a chip is not a filter over what is already drawn, and
     * the tiles are a grid rather than a carousel. So they arrive separately
     * rather than as a shelf with an empty title, which would draw a heading
     * over nothing and make the page's structure something every reader has
     * to decode.
     *
     * Null for a provider whose home is nothing but shelves — YouTube's has
     * never had a header.
     */
    header: HomeHeader? = null,
    /**
     * A chip was picked.
     *
     * The chip is part of the *request*: the provider is asked again with it
     * in force and the answer replaces the page. It is not a filter over the
     * shelves already drawn.
     */
    onChipSelected: ((String) -> Unit)? = null,
    signedIn: Boolean = true,
    onSignIn: (() -> Unit)? = null,
    /** When true, the sign-in banner is hidden (the user dismissed it). */
    signInBannerDismissed: Boolean = false,
    onDismissSignInBanner: (() -> Unit)? = null,
    /**
     * Holding a card rather than tapping it. Every card on the feed answers,
     * whichever kind it is: the caller reads the item the same way it does for
     * a tap, so a track card opens the track menu and a card that points at a
     * collection opens the album / playlist one.
     */
    onItemLongPress: ((ShelfItem) -> Unit)? = null,
    // Explore doesn't page — only Home has a continuation worth following.
    onLoadMore: (() -> Unit)? = null,
    loadingMore: Boolean = false,
    recentlyPlayedLoading: Boolean = false,
    /**
     * Whether the feed's first shelf gets the big full-bleed cards. The phone
     * leads with them; on a window as wide as a desktop's they are posters, so
     * there every shelf is the ordinary row.
     */
    leadHero: Boolean = true,
    currentSong: Song? = null,
    isPlaying: Boolean = false,
    /**
     * The Flow tile, when the listener has Flow enabled. It rides at the head
     * of the Quick Picks row rather than as a shelf of its own: Flow and Quick
     * Picks are the same question — "what next" — and two separate rows made
     * Flow look like another category that reordering could bury.
     */
    flowItem: ShelfItem? = null,
    onFlowClick: (() -> Unit)? = null,
    /** Opens Replay; its card leads Top Picks, the way Apple's does. */
    onOpenReplay: (() -> Unit)? = null,
    /**
     * Opens the whole Quick Picks set.
     *
     * The arrow at the end of the Quick Picks heading: the shelf shows what
     * fits on a screen, and this is the set behind it. Quick Picks is a
     * section of Home rather than a tab of its own — the navbar is crowded
     * enough, and the feed's own shelf is already the card row it needs.
     */
    onOpenQuickPicks: (() -> Unit)? = null,
) {
    val recentsViewType by AppUi.host.homeRecentsViewType.collectAsStateWithLifecycle()

    PullToRefresh(
        refreshing = refreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = modifier,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // The heading sits higher than the other tabs' first row: pull the
            // whole feed up by [HOME_TITLE_LIFT] with it.
            contentPadding = if (title != null) {
                PaddingValues(
                    top = (contentPadding.calculateTopPadding() - HOME_TITLE_LIFT).coerceAtLeast(0.dp),
                    bottom = contentPadding.calculateBottomPadding(),
                )
            } else {
                contentPadding
            },
        ) {
            // The provider's own greeting replaces the page's heading when it
            // has one: Spotify's home opens with "Good evening", and putting
            // "Listen now" above that would be two headings for one page.
            val greeting = header?.greeting?.takeIf { it.isNotBlank() }
            if (greeting != null) {
                item(key = "home:greeting") { HomeGreeting(greeting) }
            } else if (title != null) {
                item { HomeTitle(title) }
            }
            if (!signedIn && onSignIn != null && !signInBannerDismissed) {
                item {
                    SignInBanner(
                        onSignIn = onSignIn,
                        onDismiss = onDismissSignInBanner,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
            // The chips stay on screen while the page behind them reloads, and
            // after a failure: they are the way back to the whole feed, and a
            // page that hides them on an error leaves the listener stuck.
            val pageHeader = header
            if (pageHeader != null && (pageHeader.chips.isNotEmpty() || pageHeader.chip.isNotBlank())) {
                item(key = "home:chips") {
                    HomeChipRow(
                        chips = pageHeader.chips,
                        selected = pageHeader.chip,
                        onSelect = onChipSelected,
                    )
                }
            }
            when (state) {
                is UiState.Loading -> {
                    if (recentlyPlayedLoading) {
                        recentlyPlayedSkeleton(listLayout = recentsViewType == LibraryViewType.LIST)
                        // Recents owns the leading layout while its request is
                        // pending, so the feed behind it starts with ordinary
                        // shelf placeholders rather than another hero card.
                        feedSkeleton(firstIsHero = false)
                    } else {
                        feedSkeleton(firstIsHero = leadHero)
                    }
                }
                is UiState.Error -> item {
                    MessageState(state.message, actionLabel = stringResource(Res.string.retry), onAction = onRetry)
                }
                is UiState.Success -> {
                    if (recentlyPlayedLoading) {
                        recentlyPlayedSkeleton(listLayout = recentsViewType == LibraryViewType.LIST)
                    }
                    // The quick-access grid: the little square tiles above the
                    // shelves, the way Spotify's own home opens. A grid rather
                    // than a row, and not a shelf — a shelf would put a heading
                    // over it and a carousel would stretch two tiles across a
                    // phone screen.
                    header?.shortcuts?.takeIf { it.isNotEmpty() }?.let { shortcuts ->
                        item(key = "home:shortcuts") {
                            HomeShortcutGrid(
                                shortcuts = shortcuts,
                                onItemClick = { item -> onItemClick(item, SHORTCUT_SHELF_TITLE) },
                                onItemLongPress = onItemLongPress,
                            )
                        }
                    }
                    // The loading skeleton already owns the hero slot. Until
                    // Recently Played lands, every real shelf must retain its
                    // compact-card layout instead of briefly becoming a hero.
                    itemsIndexedShelves(
                        shelves = state.data,
                        onItemClick = onItemClick,
                        onItemLongPress = onItemLongPress,
                        currentSong = currentSong,
                        isPlaying = isPlaying,
                        flowItem = flowItem,
                        onFlowClick = onFlowClick,
                        onOpenReplay = onOpenReplay,
                        onOpenQuickPicks = onOpenQuickPicks,
                        firstIsHero = leadHero && !recentlyPlayedLoading,
                        recentsViewType = recentsViewType,
                        onRecentsViewTypeToggle = {
                            AppUi.host.setHomeRecentsViewType(
                                if (recentsViewType == LibraryViewType.LIST) {
                                    LibraryViewType.GRID
                                } else {
                                    LibraryViewType.LIST
                                },
                            )
                        },
                    )
                    if (loadingMore) feedMoreSkeleton()
                }
            }
        }
    }

    if (onLoadMore != null && state is UiState.Success) {
        val loadMore by rememberUpdatedState(onLoadMore)
        // Re-checked on every layout change, rather than on the rising edge of
        // "the tail is in view". A page that appends only a shelf or two leaves
        // the list still near its end, so an edge-triggered effect would never
        // fire a second time: the feed dead-ended at the bottom with no
        // skeleton and no request in flight to explain it. Restarting on
        // [loadingMore] re-checks the moment a page settles, so the next one is
        // asked for while the tail is still on screen to show it loading.
        //
        // The trigger is the *end* of the list, not the last three rows of it:
        // Home's continuation is endless — every page it hands back is another
        // set of generated Super Mixes — so a trigger that fires while the tail
        // merely happens to be on screen asks for a page the listener never
        // scrolled to, and asks again for the next one, and the feed runs away
        // on its own. The last row has to be the one in view, which means the
        // listener got there.
        LaunchedEffect(listState, loadingMore) {
            snapshotFlow {
                val layout = listState.layoutInfo
                (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) to layout.totalItemsCount
            }.collect { (lastVisible, total) ->
                if (!loadingMore && total > 0 && lastVisible >= total - 1) loadMore()
            }
        }
    }
}

/**
 * Recents mirrors the artist page's top-tracks pager. Any other lead shelf keeps
 * Apple's full-bleed treatment, while the remaining shelves use square cards.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedShelves(
    shelves: List<HomeShelf>,
    onItemClick: (ShelfItem, String) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    currentSong: Song?,
    isPlaying: Boolean,
    flowItem: ShelfItem? = null,
    onFlowClick: (() -> Unit)? = null,
    onOpenReplay: (() -> Unit)? = null,
    /** Opens the whole Quick Picks set — the arrow at the end of its heading. */
    onOpenQuickPicks: (() -> Unit)? = null,
    firstIsHero: Boolean = true,
    recentsViewType: LibraryViewType,
    onRecentsViewTypeToggle: () -> Unit,
) {
    // Built once rather than per row: Quick Picks is pinned to the top, so at
    // most one shelf ever asks for it.
    val flowCard: (@Composable () -> Unit)? = if (flowItem != null) {
        {
            ShelfCard(
                item = flowItem,
                onClick = { onFlowClick?.invoke() ?: onItemClick(flowItem, FlowRules.FLOW_SHELF_TITLE) },
                isCurrent = false,
                isPlaying = false,
            )
        }
    } else {
        null
    }
    // Apple's Top Picks lead with Replay, so it shares the pinned row with
    // Flow and Quick Picks instead of sitting in a shelf of its own.
    val replayCard: (@Composable () -> Unit)? = onOpenReplay?.let { open ->
        { ReplayPickCard(onClick = open) }
    }
    val topPickLeading: (@Composable () -> Unit)? =
        if (replayCard != null || flowCard != null) {
            {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    replayCard?.invoke()
                    flowCard?.invoke()
                }
            }
        } else {
            null
        }

    // The quick-picks row hosts the Flow and Replay cards when it is there.
    // When the feed has no such row — some accounts get a home without one,
    // and the row's title is written in the account's own language — the cards
    // get a row of their own at the top of the page.
    //
    // What they must never do is become a shelf of their own. A shelf at the
    // top of Home is a full-width hero, and that is the old huge Flow: the
    // card stopped being a card and became a poster.
    val quickPicksFound = shelves.any { isQuickPicksShelf(it.title) }
    if (!quickPicksFound && topPickLeading != null) {
        item(key = "auris:leading-cards") {
            Column(Modifier.padding(bottom = 26.dp)) {
                ShelfRow(
                    contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item(key = "leading") { topPickLeading() }
                }
            }
        }
    }

    // The hero is the first ordinary shelf — not the quick-picks row, which
    // carries the cards, and not Recents, which has a treatment of its own.
    // It used to be the first row whatever it was, which meant that a
    // quick-picks row at the top took the hero slot with it and the page
    // lost the one shelf it was built around.
    val heroIndex = shelves.indexOfFirst { shelf ->
        !isQuickPicksShelf(shelf.title) &&
            !shelf.title.equals(RECENTS_TITLE, ignoreCase = true)
    }

    // The keys are the shelves' own, not their places: a key carrying the
    // index changes when the shelves are reordered, and the list then reads
    // as a different list — it loses the row the listener was looking at, and
    // the row they moved to the top is somewhere else.
    //
    // Two shelves can share a title and a first song — the feed has been
    // known to send the same row twice — and a key given twice is a crash,
    // so the second one is counted off.
    val keyCounts = mutableMapOf<String, Int>()
    shelves.forEachIndexed { index, shelf ->
        val base = "shelf:" + shelf.title + ":" + (shelf.items.firstOrNull()?.videoId
            ?: shelf.items.firstOrNull()?.browseId ?: index)
        val seen = keyCounts.getOrDefault(base, 0)
        keyCounts[base] = seen + 1
        item(key = if (seen == 0) base else "$base#$seen") {
            val openItem: (ShelfItem) -> Unit = { item -> onItemClick(item, shelf.title) }
            val quickPicks = isQuickPicksShelf(shelf.title)
            if (quickPicks) {
                // The Flow tile leads this row, so the two share one horizontal
                // scroll instead of fighting for the top of the page.
                Shelf(
                    shelf = shelf,
                    onItemClick = openItem,
                    onItemLongPress = onItemLongPress,
                    leadingCard = topPickLeading,
                    currentSong = currentSong,
                    isPlaying = isPlaying,
                    // The section's own mark, and the arrow that opens the
                    // whole set rather than one card of it.
                    headerIcon = Icons.Rounded.AutoAwesome,
                    onHeaderNavigate = onOpenQuickPicks,
                )
            } else if (shelf.title.equals(RECENTS_TITLE, ignoreCase = true)) {
                RecentShelf(
                    shelf = shelf,
                    onItemClick = openItem,
                    onItemLongPress = onItemLongPress,
                    currentSong = currentSong,
                    isPlaying = isPlaying,
                    viewType = recentsViewType,
                    onViewTypeToggle = onRecentsViewTypeToggle,
                )
            } else if (index == heroIndex && firstIsHero) {
                HeroShelf(
                    shelf = shelf,
                    onItemClick = openItem,
                    onItemLongPress = onItemLongPress,
                    currentSong = currentSong,
                    isPlaying = isPlaying,
                )
            } else {
                Shelf(
                    shelf = shelf,
                    onItemClick = openItem,
                    onItemLongPress = onItemLongPress,
                    currentSong = currentSong,
                    isPlaying = isPlaying,
                )
            }
        }
    }
}

/** The same four-rows-per-page treatment used by an artist's Top songs. */
@Composable
private fun RecentShelf(
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)?,
    currentSong: Song?,
    isPlaying: Boolean,
    viewType: LibraryViewType,
    onViewTypeToggle: () -> Unit,
) {
    Column(Modifier.padding(bottom = 26.dp)) {
        RecentSectionHeader(
            title = shelf.title,
            subtitle = shelf.subtitle,
            viewType = viewType,
            onViewTypeToggle = onViewTypeToggle,
        )
        if (viewType == LibraryViewType.LIST) {
            BoxWithConstraints {
                val columnWidth = trackColumnWidth(maxWidth)
                ShelfRow(
                    contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(shelf.items.chunked(RECENT_TRACKS_PER_COLUMN)) { column ->
                        Column(Modifier.width(columnWidth)) {
                            column.forEach { item ->
                                RecentTrackRow(
                                    item = item,
                                    onClick = { onItemClick(item) },
                                    onLongPress = onItemLongPress?.let { { it(item) } },
                                    isCurrent = item.matchesCurrentlyPlaying(currentSong),
                                    isPlaying = item.matchesCurrentlyPlaying(currentSong) && isPlaying,
                                )
                            }
                        }
                    }
                }
            }
        } else {
            BoxWithConstraints {
                val cardWidth = heroCardWidth(maxWidth)
                ShelfRow(
                    contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(shelf.items) { item ->
                        HeroCard(
                            item = item,
                            onClick = { onItemClick(item) },
                            onLongPress = onItemLongPress?.let { { it(item) } },
                            modifier = Modifier.width(cardWidth),
                            isCurrent = item.matchesCurrentlyPlaying(currentSong),
                            isPlaying = item.matchesCurrentlyPlaying(currentSong) && isPlaying,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentSectionHeader(
    title: String,
    subtitle: String,
    viewType: LibraryViewType,
    onViewTypeToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .padding(horizontal = PAGE_GUTTER, vertical = 10.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable(onClick = onViewTypeToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (viewType == LibraryViewType.LIST) {
                    BitChordIcons.GridView
                } else {
                    BitChordIcons.ListView
                },
                contentDescription = stringResource(
                    if (viewType == LibraryViewType.LIST) {
                        Res.string.switch_to_grid_view
                    } else {
                        Res.string.switch_to_list_view
                    },
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@Composable
private fun RecentTrackRow(
    item: ShelfItem,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    isCurrent: Boolean,
    isPlaying: Boolean,
) {
    CompactTrackRow(
        title = item.title,
        subtitle = item.subtitle,
        thumbnailUrl = item.thumbnailUrl,
        onClick = onClick,
        onLongPress = onLongPress,
        isCurrent = isCurrent,
        isPlaying = isPlaying,
    )
}

/**
 * One row of Recents' four-to-a-column pager. Public so the desktop artist page
 * can list its top songs the same way.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CompactTrackRow(
    title: String,
    subtitle: String,
    thumbnailUrl: String?,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .longPressMenuClickable(onClick = onClick, onLongClick = onLongPress)
            .contextClick(onLongPress)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp)) {
            AsyncImage(
                model = thumbnailUrl.artworkAt(ROW_ART_PX),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .thumbnailBorder(RoundedCornerShape(7.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            if (isCurrent && isPlaying) SearchPlayingBars(Modifier.align(Alignment.Center))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isCurrent) PlayingAccent else MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onLongPress != null) {
            RowMoreButton(onClick = onLongPress)
        }
    }
}

/**
 * Listen now's large heading.
 *
 * The app's mark used to sit above it. It does not any more: the heading is
 * the first thing on the page, and the mark was a picture of the app rather
 * than anything the listener came for.
 */
@Composable
private fun HomeTitle(title: String, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.displayLarge
    Column(modifier = modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp)) {
        Text(
            text = title,
            style = style,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/**
 * The provider's own greeting — Spotify's "Good evening".
 *
 * It takes the heading's place rather than sitting under it: the page has one
 * heading, and "Listen now" above "Good evening" would be two of them.
 */
@Composable
private fun HomeGreeting(greeting: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp)) {
        Text(
            text = greeting,
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/**
 * The row of chips the page can be narrowed by.
 *
 * Spotify's own row: *All*, then the feeds it offers — Music, Podcasts,
 * Audiobooks. Their labels come from Spotify and are therefore already in the
 * listener's language; only *All* is ours, because only *All* is a chip
 * Spotify does not have.
 *
 * A chip in force is shown with the chips that open beside it, joined to it
 * as one shape, and a close button in front of it: the way back to the whole
 * page. The chips stay on screen while the page reloads and after a failure,
 * because they are the way out of a narrowed page that came back empty.
 */
@Composable
private fun HomeChipRow(
    chips: List<HomeChip>,
    selected: String,
    onSelect: ((String) -> Unit)?,
) {
    val parent = chips.firstOrNull { it.id == selected }
        ?: chips.firstOrNull { chip -> chip.subChips.any { it.id == selected } }
    val subs = parent?.subChips.orEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (selected.isNotBlank()) {
            // The way back to the whole page, as one round button.
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onSelect?.invoke("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(Res.string.home_chip_all),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            // The picked chip and the ones that open beside it, joined: one
            // shape, so they read as one control rather than as a row of them.
            // The chip in force may not be in the list at all — the feed
            // changed under it — and then there is no parent to draw, only the
            // way back.
            if (parent != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HomePill(
                        label = parent.label,
                        selected = true,
                        shape = if (subs.isEmpty()) HomePillShape else HomeJoinedStart,
                    ) { onSelect?.invoke(parent.id) }
                    subs.forEachIndexed { index, sub ->
                        HomePill(
                            label = sub.label,
                            selected = sub.id == selected,
                            shape = if (index == subs.lastIndex) HomeJoinedEnd else RoundedCornerShape(4.dp),
                        ) { onSelect?.invoke(sub.id) }
                    }
                }
            }
        } else {
            HomePill(label = stringResource(Res.string.home_chip_all), selected = true) {
                onSelect?.invoke("")
            }
            chips.forEach { chip ->
                HomePill(label = chip.label) { onSelect?.invoke(chip.id) }
            }
        }
    }
}

/** The pill's own shape: a full round. */
private val HomePillShape = RoundedCornerShape(50)

/** The left half of two pills joined together. */
private val HomeJoinedStart = RoundedCornerShape(
    topStartPercent = 50,
    bottomStartPercent = 50,
    topEndPercent = 15,
    bottomEndPercent = 15,
)

/** The right half of two pills joined together. */
private val HomeJoinedEnd = RoundedCornerShape(
    topStartPercent = 15,
    bottomStartPercent = 15,
    topEndPercent = 50,
    bottomEndPercent = 50,
)

/** One chip: a pill, dark when it is not in force and lit when it is. */
@Composable
private fun HomePill(
    label: String,
    selected: Boolean = false,
    shape: androidx.compose.ui.graphics.Shape = HomePillShape,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(shape)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
        )
    }
}

/**
 * The quick-access grid at the top of the page.
 *
 * Two columns on a phone, more on a wide window: each tile is a small square
 * of artwork and the name beside it, the way Spotify's own home opens. The
 * names are the listener's — their liked songs, the things they jumped back
 * into — so the grid is the one part of the page that is unmistakably theirs.
 */
@Composable
private fun HomeShortcutGrid(
    shortcuts: List<ShelfItem>,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)? = null,
) {
    BoxWithConstraints {
        // Two columns on a phone; a wider window gets more, so the tiles do
        // not stretch into banners.
        val columns = if (maxWidth >= 720.dp) 4 else 2
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PAGE_GUTTER, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            shortcuts.chunked(columns).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowItems.forEach { item ->
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .longPressMenuClickable(
                                    onClick = { onItemClick(item) },
                                    onLongClick = onItemLongPress?.let { { it(item) } },
                                )
                                .contextClick(onItemLongPress?.let { { it(item) } }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AsyncImage(
                                model = item.thumbnailUrl.artworkAt(ROW_ART_PX),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    // A row with one tile short of a full row still has to
                    // fill the width, or the tile it does have stretches.
                    repeat(columns - rowItems.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** ic_note's viewport, 374 x 521. */
private const val LOGO_ASPECT = 374f / 521f

/**
 * Shared by the home feed, Explore and Library so headings line up across tabs.
 *
 * [onShowAll] is only ever set on Library, whose rows stop at five cards
 * rather than running the shelf's whole length — see [LibraryGridShelf].
 * Home and Explore never pass it, so their heading is unchanged.
 *
 * [icon] is the mark a section carries — Quick Picks' spark, the same one the
 * navbar tab wore — and [onNavigate] is the arrow at the end of the heading,
 * which opens the whole section rather than one card of it.
 */
@Composable
internal fun SectionHeader(
    title: String,
    subtitle: String = "",
    onShowAll: (() -> Unit)? = null,
    icon: ImageVector? = null,
    onNavigate: (() -> Unit)? = null,
    /** A control at the end of the heading — the list/grid switch, usually. */
    trailing: (@Composable () -> Unit)? = null,
) {
    val displayTitle = localizeShelfTitle(title)
    val displaySubtitle = localizeShelfSubtitle(subtitle)
    Row(
        modifier = Modifier
            .padding(horizontal = PAGE_GUTTER, vertical = 10.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = displayTitle,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (displaySubtitle.isNotBlank()) {
                Text(
                    text = displaySubtitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            trailing()
        } else if (onShowAll != null) {
            Text(
                text = stringResource(Res.string.show_all),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClick = onShowAll)
                    .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
            )
        } else if (onNavigate != null) {
            Icon(
                imageVector = BitChordIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier
                    .size(24.dp)
                    .clickable(onClick = onNavigate),
            )
        }
    }
}
/**
 * Whether a feed shelf is Quick Picks.
 *
 * The matcher itself lives in the data layer, because the feed has to know
 * too: the row is not always in the response, and what stands in for it is
 * built from the same table. See [isQuickPicksShelfTitle].
 */
fun isQuickPicksShelf(title: String): Boolean = isQuickPicksShelfTitle(title)

@Composable
internal fun localizeShelfTitle(title: String): String {
    val trimmed = title.trim()
    return when {
        trimmed.equals("Recents", ignoreCase = true) ||
            trimmed.equals("Recently played", ignoreCase = true) ||
            trimmed.equals("Gần đây", ignoreCase = true) ||
            trimmed.equals("最近", ignoreCase = true) ->
            stringResource(Res.string.shelf_recents)
        trimmed.equals("Playlists", ignoreCase = true) ||
            trimmed.equals("Danh sách phát", ignoreCase = true) ||
            trimmed.equals("再生リスト", ignoreCase = true) ->
            stringResource(Res.string.playlists)
        trimmed.equals("Albums", ignoreCase = true) ||
            trimmed.equals("Album", ignoreCase = true) ||
            trimmed.equals("アルバム", ignoreCase = true) ->
            stringResource(Res.string.albums)
        trimmed.equals("Artists", ignoreCase = true) ||
            trimmed.equals("Nghệ sĩ", ignoreCase = true) ||
            trimmed.equals("アーティスト", ignoreCase = true) ->
            stringResource(Res.string.artists)
        trimmed.equals("Subscriptions", ignoreCase = true) ||
            trimmed.equals("Đã đăng ký", ignoreCase = true) ||
            trimmed.equals("登録チャンネル", ignoreCase = true) ->
            stringResource(Res.string.subscriptions)
        trimmed.equals("Trending community playlists", ignoreCase = true) ||
            trimmed.equals("Danh sách phát cộng đồng thịnh hành", ignoreCase = true) ||
            trimmed.equals("Danh sách phát thịnh hành trong cộng đồng người dùng", ignoreCase = true) ||
            trimmed.equals("急上昇のコミュニティ再生リスト", ignoreCase = true) ->
            stringResource(Res.string.shelf_trending_community_playlists)
        trimmed.equals("Featured playlists for you", ignoreCase = true) ||
            trimmed.equals("Danh sách phát đề xuất cho bạn", ignoreCase = true) ||
            trimmed.equals("Danh sách phát nổi bật dành cho bạn", ignoreCase = true) ||
            trimmed.equals("おすすめの再生リスト", ignoreCase = true) ->
            stringResource(Res.string.shelf_featured_playlists_for_you)
        trimmed.equals("Quick picks", ignoreCase = true) ||
            trimmed.equals("Lựa chọn nhanh", ignoreCase = true) ||
            trimmed.equals("Chọn nhanh đài phát", ignoreCase = true) ||
            trimmed.equals("クイック ミックス", ignoreCase = true) ->
            stringResource(Res.string.shelf_quick_picks)
        trimmed.equals("Listen again", ignoreCase = true) ||
            trimmed.equals("Nghe lại", ignoreCase = true) ||
            trimmed.equals("もう一度聴く", ignoreCase = true) ->
            stringResource(Res.string.shelf_listen_again)
        trimmed.equals("Mixed for you", ignoreCase = true) ||
            trimmed.equals("Dành riêng cho bạn", ignoreCase = true) ||
            trimmed.equals("ミックス", ignoreCase = true) ->
            stringResource(Res.string.shelf_mixed_for_you)
        trimmed.startsWith("Similar to", ignoreCase = true) -> {
            val rest = trimmed.substring(10).trim()
            stringResource(Res.string.shelf_similar_to, rest)
        }
        trimmed.startsWith("Tương tự như", ignoreCase = true) -> {
            val rest = trimmed.substring(12).trim()
            stringResource(Res.string.shelf_similar_to, rest)
        }
        trimmed.equals("Forgotten favorites", ignoreCase = true) ||
            trimmed.equals("Giai điệu quen thuộc", ignoreCase = true) ||
            trimmed.equals("よく聴いたお気に入りの曲", ignoreCase = true) ->
            stringResource(Res.string.shelf_forgotten_favorites)
        trimmed.equals("Recommended music videos", ignoreCase = true) ||
            trimmed.equals("Video âm nhạc đề xuất", ignoreCase = true) ||
            trimmed.equals("おすすめのミュージック ビデオ", ignoreCase = true) ->
            stringResource(Res.string.shelf_recommended_music_videos)
        trimmed.equals("From your library", ignoreCase = true) ||
            trimmed.equals("Từ thư viện của bạn", ignoreCase = true) ||
            trimmed.equals("ライブラリから", ignoreCase = true) ->
            stringResource(Res.string.shelf_from_your_library)
        trimmed.equals("Charts", ignoreCase = true) ||
            trimmed.equals("Bảng xếp hạng", ignoreCase = true) ||
            trimmed.equals("チャート", ignoreCase = true) ->
            stringResource(Res.string.shelf_charts)
        trimmed.equals("New releases", ignoreCase = true) ||
            trimmed.equals("Bản phát hành mới", ignoreCase = true) ||
            trimmed.equals("最新リリース", ignoreCase = true) ->
            stringResource(Res.string.shelf_new_releases)
        trimmed.equals("Top music videos", ignoreCase = true) ||
            trimmed.equals("Video âm nhạc hàng đầu", ignoreCase = true) ||
            trimmed.equals("人気のミュージック ビデオ", ignoreCase = true) ->
            stringResource(Res.string.shelf_top_music_videos)
        trimmed.equals("For you", ignoreCase = true) ||
            trimmed.equals("Dành cho bạn", ignoreCase = true) ||
            trimmed.equals("あなたへのおすすめ", ignoreCase = true) ->
            stringResource(Res.string.shelf_for_you)
        trimmed.equals("Hits today", ignoreCase = true) ||
            trimmed.equals("Today's Hits", ignoreCase = true) ||
            trimmed.equals("Bản hit hôm nay", ignoreCase = true) ||
            trimmed.equals("今日のヒット曲", ignoreCase = true) ->
            stringResource(Res.string.shelf_hits_today)
        trimmed.equals("Artists on the rise", ignoreCase = true) ||
            trimmed.equals("Nghệ sĩ đang lên", ignoreCase = true) ->
            stringResource(Res.string.shelf_artists_on_the_rise)
        trimmed.equals("Concerts", ignoreCase = true) ||
            trimmed.equals("Buổi hòa nhạc", ignoreCase = true) ||
            trimmed.equals("コンサート", ignoreCase = true) ->
            stringResource(Res.string.shelf_concerts)
        trimmed.startsWith("Shorts", ignoreCase = true) ||
            trimmed.equals("Shorts nổi bật", ignoreCase = true) ||
            trimmed.equals("ショート", ignoreCase = true) ->
            stringResource(Res.string.shelf_shorts)
        trimmed.equals("Trending", ignoreCase = true) ||
            trimmed.equals("Thịnh hành", ignoreCase = true) ||
            trimmed.equals("急上昇", ignoreCase = true) ->
            stringResource(Res.string.shelf_trending)
        else -> title
    }
}
@Composable
internal fun localizeShelfSubtitle(subtitle: String): String {
    val trimmed = subtitle.trim()
    return when {
        trimmed.equals("TOP TUNES RIGHT NOW", ignoreCase = true) ||
            trimmed.equals("Top tunes right now", ignoreCase = true) ||
            trimmed.equals("Giai điệu hàng đầu hiện nay", ignoreCase = true) ||
            trimmed.equals("注目の曲", ignoreCase = true) ->
            stringResource(Res.string.shelf_top_tunes_right_now)
        trimmed.equals("From the community", ignoreCase = true) ||
            trimmed.equals("Từ cộng đồng", ignoreCase = true) ||
            trimmed.equals("コミュニティより", ignoreCase = true) ->
            stringResource(Res.string.shelf_from_the_community)
        trimmed.equals("YouTube Charts", ignoreCase = true) ||
            trimmed.equals("Bảng xếp hạng YouTube", ignoreCase = true) ||
            trimmed.equals("YouTube チャート", ignoreCase = true) ->
            stringResource(Res.string.shelf_youtube_charts)
        else -> subtitle
    }
}

@Composable
internal fun localizeCardSubtitle(subtitle: String): String {
    if (subtitle.isBlank()) return subtitle
    val delimiter = " • "
    val parts = subtitle.split(delimiter)
    val songLabel = stringResource(Res.string.shelf_song_item)
    val singleLabel = stringResource(Res.string.shelf_single)
    val chartLabel = stringResource(Res.string.shelf_chart)
    val playlistLabel = stringResource(Res.string.playlist)
    val localizedParts = parts.map { part ->
        val trimmed = part.trim()
        val lower = trimmed.lowercase(Locale.ROOT)
        when {
            trimmed.equals("Song", ignoreCase = true) || trimmed.equals("Titre", ignoreCase = true) ||
                trimmed.equals("Bài hát", ignoreCase = true) || trimmed.equals("曲", ignoreCase = true) -> songLabel
            trimmed.equals("Single", ignoreCase = true) || trimmed.equals("Đĩa đơn", ignoreCase = true) ||
                trimmed.equals("シングル", ignoreCase = true) -> singleLabel
            trimmed.equals("Chart", ignoreCase = true) || trimmed.equals("Bảng xếp hạng", ignoreCase = true) ||
                trimmed.equals("チャート", ignoreCase = true) -> chartLabel
            trimmed.equals("Playlist", ignoreCase = true) || trimmed.equals("Danh sách phát", ignoreCase = true) ||
                trimmed.equals("再生リスト", ignoreCase = true) -> playlistLabel
            lower.endsWith(" views") || lower.endsWith(" view") || lower.endsWith(" lượt xem") ||
                lower.endsWith(" 回視聴") || lower.endsWith("回視聴") -> {
                val count = trimmed.substringBeforeLast(' ', "").trim()
                if (count.any { it.isDigit() }) stringResource(Res.string.card_views_format, count) else part
            }
            lower.endsWith(" plays") || lower.endsWith(" play") || lower.endsWith(" lượt phát") ||
                lower.endsWith(" 回再生") || lower.endsWith("回再生") -> {
                val count = trimmed.substringBeforeLast(' ', "").trim()
                if (count.any { it.isDigit() }) stringResource(Res.string.card_plays_format, count) else part
            }
            lower.endsWith(" songs") || lower.endsWith(" song") || lower.endsWith(" bài hát") ||
                lower.endsWith(" 曲") || lower.endsWith("曲") -> {
                val count = trimmed.substringBeforeLast(' ', "").trim()
                if (count.any { it.isDigit() }) stringResource(Res.string.card_songs_format, count) else part
            }
            else -> part
        }
    }
    return localizedParts.joinToString(delimiter)
}

@Composable
private fun HeroShelf(
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)? = null,
    currentSong: Song? = null,
    isPlaying: Boolean = false,
) {
    Column(Modifier.padding(bottom = 26.dp)) {
        SectionHeader(shelf.title, shelf.subtitle)
        // Measured rather than taken as a share of the parent, because the card
        // has a ceiling as well as a fraction — see [heroCardWidth]. A fixed
        // width is also the only one of the two the aspect ratio below can turn
        // into a height, so the card keeps its shape however it was arrived at.
        BoxWithConstraints {
            val cardWidth = heroCardWidth(maxWidth)
            ShelfRow(
                state = rememberLazyListState(),
                contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(shelf.items) { item ->
                    HeroCard(
                        item = item,
                        onClick = { onItemClick(item) },
                        onLongPress = onItemLongPress?.let { { it(item) } },
                        modifier = Modifier.width(cardWidth),
                        isCurrent = item.matchesCurrentlyPlaying(currentSong),
                        isPlaying = item.matchesCurrentlyPlaying(currentSong) && isPlaying,
                    )
                }
            }
        }
    }
}

/** Big card: artwork with the caption laid over a scrim, as on Listen Now. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeroCard(
    item: ShelfItem,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
) {
    Box(
        modifier = modifier
            .aspectRatio(HERO_CARD_RATIO)
            .clip(RoundedCornerShape(18.dp))
            .thumbnailBorder(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .longPressMenuClickable(onClick = onClick, onLongClick = onLongPress)
            .contextClick(onLongPress),
    ) {
        AsyncImage(
            model = item.thumbnailUrl.artworkAt(HEADER_ART_PX),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (isCurrent && isPlaying) SearchPlayingBars(Modifier.align(Alignment.Center))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)),
                    ),
                )
                .padding(start = 16.dp, end = 16.dp, top = 34.dp, bottom = 14.dp),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleLarge,
                color = if (isCurrent) PlayingAccent else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.subtitle.isNotBlank()) {
                val displaySubtitle = localizeCardSubtitle(item.subtitle)
                Text(
                    text = displaySubtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * [leadingCard] rides at the head of the row, ahead of the content — the
 * Library tab's "New playlist" tile, which belongs among the playlists rather
 * than in a bar somewhere above them. [onItemLongPress] opens the album /
 * playlist menu, and is null only where a card points at something with no
 * track list behind it to act on.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Shelf(
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)? = null,
    leadingCard: (@Composable () -> Unit)? = null,
    currentSong: Song? = null,
    isPlaying: Boolean = false,
    /** The mark this section's heading carries. */
    headerIcon: ImageVector? = null,
    /** The arrow at the end of the heading, which opens the whole section. */
    onHeaderNavigate: (() -> Unit)? = null,
) {
    Column(Modifier.padding(bottom = 26.dp)) {
        SectionHeader(
            shelf.title,
            shelf.subtitle,
            icon = headerIcon,
            onNavigate = onHeaderNavigate,
        )
        ShelfRow(
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            leadingCard?.let { card -> item(key = "leading") { card() } }
            items(shelf.items) { item ->
                ShelfCard(
                    item = item,
                    onClick = { onItemClick(item) },
                    onLongPress = onItemLongPress?.let { { it(item) } },
                    isCurrent = item.matchesCurrentlyPlaying(currentSong),
                    isPlaying = item.matchesCurrentlyPlaying(currentSong) && isPlaying,
                )
            }
        }
    }
}

/**
 * A card that isn't a thing yet — the dashed "New playlist" tile at the head
 * of the Library's playlist row, sized to sit in line with the covers beside
 * it rather than as a button bolted above them.
 */
@Composable
internal fun NewShelfCard(
    icon: ImageVector,
    label: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.width(SHELF_CARD_WIDTH),
    /** A full-colour mark drawn in place of [icon], for a card that stands for a brand. */
    logo: DrawableResource? = null,
) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (logo != null) {
                // The mark is a disc with the bars cut out, so tinting it
                // leaves the bars clear: white on dark, black on light.
                Icon(
                    painter = painterResource(logo),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(44.dp),
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(34.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A device-folder card: mesh gradient in the folder's colours with its glyph
 * on top. One composable for local music, WebDAV and SMB rather than three
 * copies of the same box.
 */
@Composable
private fun ServiceCard(colors: List<Color>, trackKey: String, icon: ImageVector) {
    val palette = remember { MeshPalette(colors) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        // One settle on arrival, not an orbit: a blurred layer redrawn at
        // refresh rate is the most expensive thing on this screen, and there
        // can be several of these tiles visible at once on a potato.
        MeshGradientBackground(
            palette = palette,
            trackKey = trackKey,
            blurRadius = 24.dp,
        )
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(40.dp),
        )
    }
}

/**
 * The Replay tile at the head of Top Picks. A gradient rather than artwork:
 * Replay is a recap, not a release, and Apple's tile is the one place on Home
 * that is deliberately not a cover.
 */
@Composable
private fun ReplayPickCard(onClick: () -> Unit) {
    Column(modifier = Modifier.width(SHELF_CARD_WIDTH).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFFFF375F), Color(0xFFFF9F0A), Color(0xFFBF5AF2)),
                    ),
                )
                .padding(14.dp),
        ) {
            Icon(
                painter = painterResource(Res.drawable.ic_note),
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .height(18.dp)
                    .aspectRatio(LOGO_ASPECT),
            )
            Column(Modifier.align(Alignment.BottomStart)) {
                Text(
                    text = stringResource(Res.string.replay),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                )
                Text(
                    text = stringResource(Res.string.replay_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.82f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(Res.string.replay),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(Res.string.replay_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ShelfCard(
    item: ShelfItem,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier.width(SHELF_CARD_WIDTH),
    /** Set on a Library playlist card that's in [AppSettings.pinnedPlaylists][com.music.bitchord.data.settings.AppSettings.pinnedPlaylists]. */
    isPinned: Boolean = false,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
) {
    val animatedCanvas by PlayerSettings.animatedCanvas.collectAsStateWithLifecycle()
    // Tapping this card lifts its cover and flies it into the page it opens —
    // see ArtworkFlight. The card is the departure and the page's sleeve is the
    // arrival; neither knows about the other, and the flight is one picture
    // moving between two measured rectangles.
    val openWithFlight = rememberArtworkTap(
        destination = item.browseId,
        token = item,
        onOpen = onClick,
    )
    Column(
        modifier = modifier
            .longPressMenuClickable(onClick = openWithFlight, onLongClick = onLongPress)
            .contextClick(onLongPress),
    ) {
        when (item.browseId) {
            FlowRules.FLOW_BROWSE_ID -> {
                // Flow has no cover of its own until its queue has a track, and
                // a blank tile reads as a broken image. The gradient is the
                // station's identity, not a placeholder for one.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF1D1B4F), Color(0xFF6D2FD8), Color(0xFFFF4B7A)),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = BitChordIcons.MusicNote,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.92f),
                        modifier = Modifier.size(40.dp),
                    )
                    // The square cut of Flow's clip, over the gradient and note
                    // while it decodes — so the tile keeps its identity either way.
                    // Off with the animated-canvas switch: a tile is decoration,
                    // and a looping decoder per tile is the first thing to go on
                    // a device that cannot afford it.
                    if (animatedCanvas) rememberFlowVideo(square = true)?.let { clip ->
                        CanvasArtworkPlayer(
                            canvas = clip,
                            isPlaying = true,
                            contentMode = CanvasContentMode.CROP,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            "local:downloads" -> {
                val palette = remember { MeshPalette(listOf(Color(0xFF1E3C72), Color(0xFF2A5298))) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    MeshGradientBackground(
                        palette = palette,
                        trackKey = "local:downloads",
                        blurRadius = 24.dp,
                    )
                    Icon(
                        imageVector = BitChordIcons.Download,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            "local:all" -> ServiceCard(
                colors = listOf(Color(0xFF134E5E), Color(0xFF71B280)),
                trackKey = "local:all",
                icon = Icons.Rounded.LibraryMusic,
            )
            "local:cache" -> ServiceCard(
                colors = listOf(Color(0xFF42275A), Color(0xFF734B6D)),
                trackKey = "local:cache",
                icon = Icons.Rounded.Cached,
            )
            "local:webdav" -> ServiceCard(
                colors = listOf(Color(0xFF3A1C71), Color(0xFFD76D77)),
                trackKey = "local:webdav",
                icon = Icons.Rounded.Folder,
            )
            "local:smb" -> ServiceCard(
                colors = listOf(Color(0xFF0F2027), Color(0xFF2C5364)),
                trackKey = "local:smb",
                icon = Icons.Rounded.Storage,
            )
            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .thumbnailBorder(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        // The cover this card flies from: measured where it is,
                        // and drawn away while its own copy is in the air.
                        .artworkDeparture(token = item, model = item.thumbnailUrl.artworkAt(CARD_ART_PX)),
                ) {
                    AsyncImage(
                        model = item.thumbnailUrl.artworkAt(CARD_ART_PX),
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (isCurrent && isPlaying) SearchPlayingBars(Modifier.align(Alignment.Center))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isPinned) {
                Icon(
                    imageVector = BitChordIcons.Pin,
                    contentDescription = stringResource(Res.string.pinned),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isCurrent) PlayingAccent else MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        val displaySubtitle = localizeCardSubtitle(item.subtitle)
        Text(
            text = displaySubtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
