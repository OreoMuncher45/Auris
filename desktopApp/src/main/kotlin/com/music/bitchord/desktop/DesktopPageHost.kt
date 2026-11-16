package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.EntityType
import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.SearchHistoryEntity
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.SubscriptionState
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.ShelfRow
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.replay.ReplayCreditCard
import com.music.bitchord.ui.screens.ExploreScreen
import com.music.bitchord.ui.screens.HomeScreen
import com.music.bitchord.ui.screens.LibraryGridPage
import com.music.bitchord.ui.screens.LibraryLink
import com.music.bitchord.ui.screens.LibraryScreen
import com.music.bitchord.ui.screens.MoodGenrePlaylistsScreen
import com.music.bitchord.ui.screens.PodcastsScreen
import com.music.bitchord.ui.screens.RadioScreen
import com.music.bitchord.ui.screens.SearchScreen

/**
 * What the pages can ask the window to do: play, queue, open, like, download, search.
 *
 * The window's own functions, gathered so [DesktopPageHost] takes one value rather than thirty.
 * Each is a thin call into the function of the same name in [BitChordDesktopApp].
 */
internal class DesktopPageActions(
    val playSong: (Song, DesktopQueueSource?) -> Unit,
    val playSongs: (List<Song>, Int, DesktopQueueSource?) -> Unit,
    val playNext: (Song) -> Unit,
    val addToQueue: (Song) -> Unit,
    val addToPlaylist: (Song) -> Unit,
    val toggleLike: (Song) -> Unit,
    val downloadSong: (Song) -> Unit,
    val downloadAll: (List<Song>) -> Unit,
    val removeFromOpenPlaylist: (Song) -> Unit,
    val shareCollection: (DesktopCollection) -> Unit,
    val toggleSubscription: (SubscriptionState) -> Unit,
    val openArtist: (browseId: String, name: String) -> Unit,
    val openArtistByName: (String) -> Unit,
    val openBrowseItem: (BrowseItem) -> Unit,
    val openShelfItem: (ShelfItem, shelfTitle: String?) -> Unit,
    val openLibraryItem: (ShelfItem) -> Unit,
    val openMoodGenre: (MoodGenre) -> Unit,
    val openPodcastShow: (ShelfItem) -> Unit,
    val playRadioSeed: (ShelfItem) -> Unit,
    val selectDestination: (DesktopDestination) -> Unit,
    val loadHome: () -> Unit,
    val loadMoreHome: () -> Unit,
    val reloadLibrary: () -> Unit,
    val loadMoreCollectionSongs: () -> Unit,
    val editQuery: (String) -> Unit,
    val search: () -> Unit,
    val runSearch: (String) -> Unit,
    val recordSongSearch: (Song) -> Unit,
    val recordBrowseSearch: (BrowseItem) -> Unit,
    val songMenu: @Composable (Song) -> Unit,
)

/** The scroll positions the window keeps for its pages, so each keeps its place between visits. */
internal class DesktopPageListStates(
    val home: LazyListState,
    val explore: LazyListState,
    val radio: LazyListState,
    val moodGenre: LazyListState,
    val search: LazyListState,
    val library: LazyListState,
    val libraryGrid: LazyGridState,
    val podcasts: LazyListState,
)

/**
 * The page area: whichever destination is selected, or the album, artist, show, genre or Replay
 * opened over it. Settings is not here; it stands in for the page before this is reached.
 *
 * Lifted out of [BitChordDesktopApp] as it was, branch for branch and in the same order. The
 * local functions at the top keep the moved code reading as it did, each one a call into
 * [actions].
 */
@Composable
internal fun DesktopPageHost(
    destination: DesktopDestination,
    contentPadding: PaddingValues,
    pages: DesktopPagesState,
    overlays: DesktopOverlays,
    settings: DesktopAppSettings,
    searchState: DesktopSearchState,
    lists: DesktopPageListStates,
    actions: DesktopPageActions,
    openMenu: (Song) -> Unit,
    playingSong: Song?,
    isPlaying: Boolean,
    likedIds: Set<String>,
    downloads: List<Song>,
    downloadInProgress: Set<String>,
    youtubeSignedIn: Boolean,
    radioSeeds: List<ShelfItem>,
    searchHistory: List<SearchHistoryEntity>,
    replayHolder: String,
    libraryLinks: List<LibraryLink>,
    libraryDeviceItems: List<ShelfItem>,
) {
    // Desktop pages start at the same top edge as Library. Only preserve space for the floating
    // player/navigation bar at the bottom in compact windows.
    val sharedPagePadding = PaddingValues(bottom = contentPadding.calculateBottomPadding())
    val homeListState = lists.home
    val exploreListState = lists.explore
    val radioListState = lists.radio
    val moodGenreListState = lists.moodGenre
    val searchListState = lists.search
    val libraryListState = lists.library
    val libraryGridState = lists.libraryGrid
    val podcastListState = lists.podcasts
    // The mark on a downloaded row. Worked out once per change to the downloads rather than once
    // per composition of the page, for the album and History pages alike.
    val downloadedIds = remember(downloads) { downloads.map(Song::videoId).toSet() }

    fun playSong(song: Song, source: DesktopQueueSource? = null) = actions.playSong(song, source)
    fun playSongs(songs: List<Song>, startIndex: Int, source: DesktopQueueSource?) =
        actions.playSongs(songs, startIndex, source)
    fun playNext(song: Song) = actions.playNext(song)
    fun addToQueue(song: Song) = actions.addToQueue(song)
    fun toggleLike(song: Song) = actions.toggleLike(song)
    fun downloadSong(song: Song) = actions.downloadSong(song)
    fun downloadAll(songs: List<Song>) = actions.downloadAll(songs)
    fun removeFromOpenPlaylist(song: Song) = actions.removeFromOpenPlaylist(song)
    fun shareCollection(collection: DesktopCollection) = actions.shareCollection(collection)
    fun toggleSubscription(subscription: SubscriptionState) = actions.toggleSubscription(subscription)
    fun openArtist(browseId: String, name: String) = actions.openArtist(browseId, name)
    fun openArtistByName(name: String) = actions.openArtistByName(name)
    fun openBrowseItem(item: BrowseItem) = actions.openBrowseItem(item)
    fun openShelfItem(item: ShelfItem, shelfTitle: String? = null) = actions.openShelfItem(item, shelfTitle)
    fun openLibraryItem(item: ShelfItem) = actions.openLibraryItem(item)
    fun openMoodGenre(item: MoodGenre) = actions.openMoodGenre(item)
    fun openPodcastShow(item: ShelfItem) = actions.openPodcastShow(item)
    fun playRadioSeed(item: ShelfItem) = actions.playRadioSeed(item)
    fun selectDestination(next: DesktopDestination) = actions.selectDestination(next)
    fun loadHome() = actions.loadHome()
    fun loadMoreHome() = actions.loadMoreHome()
    fun reloadLibrary() = actions.reloadLibrary()
    fun loadMoreCollectionSongs() = actions.loadMoreCollectionSongs()
    fun editQuery(text: String) = actions.editQuery(text)
    fun search() = actions.search()
    fun runSearch(term: String) = actions.runSearch(term)
    fun recordSongSearch(song: Song) = actions.recordSongSearch(song)
    fun recordBrowseSearch(item: BrowseItem) = actions.recordBrowseSearch(item)

    @Composable
    fun songMenu(song: Song) = actions.songMenu(song)

    when {
        overlays.replay -> DesktopReplayPage(
            summary = pages.replaySummary,
            period = pages.replayPeriod,
            holder = replayHolder,
            onPeriodChange = { pages.replayPeriod = it },
            onPlaySong = { playSong(it) },
            onOpenArtist = ::openArtistByName,
            contentPadding = contentPadding,
        )
        pages.openedArtist != null -> DesktopArtistPage(
            state = pages.artistState,
            fallbackName = pages.openedArtist!!.name,
            onRetry = {
                pages.artistState = UiState.Loading
                pages.artistReloads++
            },
            onPlaySongs = { songs, index ->
                playSongs(songs, index, pages.openedArtist?.let { artistSource(it) })
            },
            onShuffle = { songs ->
                playSongs(songs, 0, pages.openedArtist?.let { artistSource(it) })
            },
            onOpenMenu = { song -> openMenu(song) },
            onShelfItemClick = ::openShelfItem,
            // A channel subscription is the account's, so a guest is never shown
            // the button.
            onToggleSubscription = if (youtubeSignedIn) ::toggleSubscription else null,
            contentPadding = contentPadding,
        )
        pages.openedCollection?.loading == true -> DesktopPageScaffold(contentPadding) {
            Box(Modifier.fillMaxSize()) {
                val error = pages.collectionError
                if (error != null) {
                    DesktopErrorPage(error) { pages.collectionReloads++ }
                } else {
                    val title = pages.openedCollection?.title.orEmpty()
                    DesktopLoadingPage(if (title.isBlank()) "Loading…" else "Loading $title…")
                }
            }
        }
        pages.openedCollection != null -> DesktopCollectionPage(
            collection = pages.openedCollection!!,
            loadingMore = pages.collectionLoadingMore,
            onLoadMore = ::loadMoreCollectionSongs,
            onRename = { overlays.rename = true }.takeIf { pages.openedCollection?.owned == true },
            onShare = pages.openedCollection
                ?.takeIf { it.type == BrowseType.ALBUM || it.type == BrowseType.PLAYLIST }
                ?.let { collection -> { shareCollection(collection) } },
            onDelete = { overlays.delete = true }.takeIf { pages.openedCollection?.owned == true },
            onRemoveFromPlaylist = ::removeFromOpenPlaylist
                .takeIf { pages.openedCollection?.owned == true },
            likedIds = likedIds,
            onPlaySongs = { songs, index ->
                playSongs(songs, index, pages.openedCollection?.let { collectionSource(it) })
            },
            onShuffle = { songs ->
                playSongs(songs.shuffled(), 0, pages.openedCollection?.let { collectionSource(it) })
            },
            onDownloadAll = ::downloadAll,
            animatedCanvas = settings.animatedCanvas,
            onToggleLike = { song ->
                toggleLike(song)
            },
            onAddToPlaylist = actions.addToPlaylist,
            onDownload = ::downloadSong,
            downloadedIds = downloadedIds,
            downloadInProgress = downloadInProgress,
            contentPadding = contentPadding,
        )
        destination == DesktopDestination.LISTEN_NOW -> HomeScreen(
            state = pages.homeState,
            listState = homeListState,
            onItemClick = { item, shelfTitle -> openShelfItem(item, shelfTitle) },
            onRetry = ::loadHome,
            refreshing = false,
            onRefresh = ::loadHome,
            pullState = rememberPullToRefreshState(),
            contentPadding = sharedPagePadding,
            title = null,
            signedIn = youtubeSignedIn,
            onSignIn = { overlays.accounts = true },
            onItemLongPress = { item -> if (item.videoId != null) openMenu(item.toSong()) },
            onLoadMore = ::loadMoreHome,
            loadingMore = pages.homeLoadingMore,
            recentlyPlayedLoading = pages.homeRecentsLoading,
            leadHero = false,
        )
        destination == DesktopDestination.EXPLORE && pages.selectedMoodGenre != null -> Column(Modifier.fillMaxSize()) {
            MoodGenrePlaylistsScreen(
                title = pages.selectedMoodGenre!!.title,
                state = pages.moodGenreShelves,
                listState = moodGenreListState,
                onItemClick = { item -> openShelfItem(item, pages.selectedMoodGenre?.title) },
                onRetry = {
                    pages.moodGenreShelves = UiState.Loading
                    pages.moodGenreReloads++
                },
                contentPadding = PaddingValues(bottom = sharedPagePadding.calculateBottomPadding()),
                modifier = Modifier.weight(1f),
            )
        }
        pages.openedPodcastShow != null -> DesktopPodcastShowPage(
            page = pages.openedPodcastShow!!,
            onPlay = { episode ->
                playSong(
                    episode,
                    source = DesktopQueueSource(
                        pages.openedPodcastShow?.name ?: "Podcast",
                        PlaybackSourceType.EXPLORE,
                    ),
                )
            },
            onPlayAll = { episodes ->
                playSongs(
                    episodes,
                    0,
                    DesktopQueueSource(
                        pages.openedPodcastShow?.name ?: "Podcast",
                        PlaybackSourceType.EXPLORE,
                    ),
                )
            },
            onBack = { pages.openedPodcastShow = null },
            contentPadding = contentPadding,
        )
        destination == DesktopDestination.PODCASTS -> PodcastsScreen(
            state = pages.podcastShows,
            listState = podcastListState,
            sections = pages.podcastSections,
            searchQuery = pages.podcastSearchQuery,
            onSearchQueryChange = { pages.podcastSearchQuery = it },
            searchResults = pages.podcastSearchResults,
            onShowClick = ::openPodcastShow,
            onRetry = { pages.podcastReloads++ },
            refreshing = false,
            onRefresh = { pages.podcastReloads++ },
            pullState = rememberPullToRefreshState(),
            contentPadding = sharedPagePadding,
            showTitle = false,
        )
        destination == DesktopDestination.RADIO -> RadioScreen(
            state = pages.exploreState,
            listState = radioListState,
            // The Flow tile is a community feature the desktop has no account
            // for; the rest of the page stands on its own.
            flowItem = null,
            onFlowClick = {},
            seeds = radioSeeds,
            onSeedClick = ::playRadioSeed,
            onStationClick = { item ->
                // The station's shelves are opened on Explore, which is where
                // the phone opens them too.
                selectDestination(DesktopDestination.EXPLORE)
                openMoodGenre(item)
            },
            onStartStation = { selectDestination(DesktopDestination.SEARCH) },
            onGenresClick = { selectDestination(DesktopDestination.EXPLORE) },
            onRetry = { pages.exploreReloads++ },
            refreshing = false,
            onRefresh = { pages.exploreReloads++ },
            pullState = rememberPullToRefreshState(),
            contentPadding = sharedPagePadding,
        )
        destination == DesktopDestination.EXPLORE -> ExploreScreen(
            state = pages.exploreState,
            listState = exploreListState,
            onCategoryClick = ::openMoodGenre,
            onRetry = { pages.exploreReloads++ },
            refreshing = false,
            onRefresh = { pages.exploreReloads++ },
            pullState = rememberPullToRefreshState(),
            contentPadding = sharedPagePadding,
            showTitle = false,
        )
        destination == DesktopDestination.SEARCH -> SearchScreen(
            query = searchState.query,
            onQueryChange = ::editQuery,
            filter = searchState.filter,
            // The playing row's indicator, as the phone passes it: the engine's
            // live state, not the selection a track change publishes behind it.
            currentSong = playingSong,
            isPlaying = isPlaying,
            onFilterChange = {
                searchState.filter = it
                if (searchState.query.isNotBlank()) search()
            },
            results = when {
                searchState.loading -> UiState.Loading
                searchState.error != null -> UiState.Error(searchState.error!!)
                searchState.committed -> UiState.Success(searchState.rows)
                else -> null
            },
            loadingMore = false,
            onLoadMore = {},
            listState = searchListState,
            scrollResetTrigger = searchState.scrollReset,
            focusRequested = searchState.focusRequested,
            onFocusHandled = { searchState.focusRequested = false },
            onSongClick = { songs, index ->
                songs.getOrNull(index)?.let { song ->
                    recordSongSearch(song)
                    playSong(song, source = DesktopQueueSource(DesktopStrings["search", "Search"], PlaybackSourceType.SEARCH))
                }
            },
            onSongLongPress = openMenu,
            onSongSwipe = { song ->
                if (DesktopAppUiHost.swipeToPlayNext.value) playNext(song) else addToQueue(song)
            },
            onTopResultPlay = { song ->
                recordSongSearch(song)
                playSong(song, source = DesktopQueueSource(DesktopStrings["search", "Search"], PlaybackSourceType.SEARCH))
            },
            onTopResultPlaylist = { song ->
                recordSongSearch(song)
                actions.addToPlaylist(song)
            },
            onBrowseClick = { item ->
                recordBrowseSearch(item)
                openBrowseItem(item)
            },
            history = searchHistory,
            // The typed text leads the list, put there by the keystroke rather than
            // taken from the response — the page drops it, as the phone's does.
            suggestions = if (searchState.typing && searchState.query.isNotBlank()) {
                listOf(searchState.query) +
                    searchState.suggestions.filterNot { it.equals(searchState.query, ignoreCase = true) }
            } else {
                emptyList()
            },
            typeaheadResults = if (searchState.typing && searchState.query.isNotBlank()) searchState.typeahead else emptyList(),
            onSubmit = ::search,
            onSuggestionClick = ::runSearch,
            onHistoryClick = { entity ->
                // Going back to a recent does not record it again, as on the phone.
                when (entity.entityType) {
                    EntityType.TRACK -> playSong(
                        Song(
                            videoId = entity.id,
                            title = entity.title,
                            artist = entity.subtitle,
                            thumbnailUrl = entity.artworkUrl,
                        ),
                        source = DesktopQueueSource(entity.title, PlaybackSourceType.SEARCH),
                    )
                    EntityType.ARTIST -> openArtist(entity.id, entity.title)
                    EntityType.ALBUM, EntityType.PLAYLIST -> openBrowseItem(
                        BrowseItem(
                            browseId = entity.id,
                            title = entity.title,
                            subtitle = entity.subtitle,
                            thumbnailUrl = entity.artworkUrl,
                            type = if (entity.entityType == EntityType.ALBUM) BrowseType.ALBUM else BrowseType.PLAYLIST,
                        ),
                    )
                }
            },
            onHistoryRemove = DesktopSearchHistory::remove,
            onHistoryClear = DesktopSearchHistory::clear,
            onTypeaheadLongPress = openMenu,
            contentPadding = sharedPagePadding,
            topPadding = 0.dp,
            showField = false,
        )
        destination == DesktopDestination.LIBRARY && pages.libraryShowAll != null -> Column(Modifier.fillMaxSize()) {
            // The phone keeps the grid's sort beside it, in the bar above; here it sits
            // at the end of the row the way back is on.
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, end = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) { DesktopLibrarySortMenu() }
            LibraryGridPage(
                shelf = pages.libraryShowAll!!,
                gridState = libraryGridState,
                onItemClick = ::openLibraryItem,
                onItemLongPress = { item -> if (item.videoId != null) openMenu(item.toSong()) },
                contentPadding = PaddingValues(bottom = sharedPagePadding.calculateBottomPadding()),
                modifier = Modifier.weight(1f),
                onNewPlaylist = { overlays.playlistDialog = true }
                    .takeIf { pages.libraryShowAll?.title == PLAYLISTS_SHELF },
            )
        }
        destination == DesktopDestination.LIBRARY -> LibraryScreen(
            signedIn = youtubeSignedIn,
            state = pages.libraryState,
            listState = libraryListState,
            onShelfItemClick = ::openLibraryItem,
            onShelfItemLongPress = { item -> if (item.videoId != null) openMenu(item.toSong()) },
            onNewPlaylist = { overlays.playlistDialog = true },
            onShowAll = { shelf -> pages.libraryShowAll = shelf },
            replay = {
                // The phone's wallet of Replay cards, or nothing until there is
                // listening to deal them from.
                val cards = remember(pages.replaySummary) { pages.replaySummary.heroCards() }
                if (!pages.replaySummary.isEmpty && cards.isNotEmpty()) {
                    ShelfRow(
                        modifier = Modifier.padding(vertical = 6.dp),
                        contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(cards, key = { it.label }) { card ->
                            ReplayCreditCard(
                                label = card.label,
                                value = card.value,
                                detail = card.detail,
                                artworkUrl = card.artworkUrl,
                                holder = replayHolder,
                                memberSince = pages.replaySummary.memberSince(),
                                onClick = { overlays.replay = true },
                                modifier = Modifier.width(300.dp),
                            )
                        }
                    }
                }
            },
            onSignIn = { overlays.accounts = true },
            onRetry = ::reloadLibrary,
            refreshing = false,
            onRefresh = ::reloadLibrary,
            pullState = rememberPullToRefreshState(),
            contentPadding = sharedPagePadding,
            links = libraryLinks,
            deviceItems = libraryDeviceItems,
            showTitle = false,
        )
        destination == DesktopDestination.HISTORY -> DesktopHistoryPage(
            // The account's history when there is one, and what
            // this computer played when there is not.
            history = pages.remoteHistory.ifEmpty { pages.history },
            onSongClick = { playSong(it, source = DesktopQueueSource(DesktopStrings["history", "History"], PlaybackSourceType.HISTORY)) },
            onDownload = ::downloadSong,
            onAddToPlaylist = actions.addToPlaylist,
            downloadedIds = downloadedIds,
            downloadInProgress = downloadInProgress,
            contentPadding = contentPadding,
            menu = { song -> songMenu(song) },
        )
        destination == DesktopDestination.DOWNLOADS -> Box(Modifier.fillMaxSize()) {
            DesktopDownloadsPage(
                downloads = downloads,
                onSongClick = { playSong(it, source = DesktopQueueSource(DesktopStrings["downloads", "Downloads"], PlaybackSourceType.BROWSE)) },
                contentPadding = contentPadding,
                menu = { song -> songMenu(song) },
            )
            // Only while there is a queue to look at.
            if (downloadInProgress.isNotEmpty()) {
                DesktopActionButton(
                    "Queue · ${downloadInProgress.size}",
                    BitChordIcons.Download,
                    onClick = { overlays.downloadManager = true },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 28.dp, bottom = 28.dp + contentPadding.calculateBottomPadding()),
                )
            }
        }
        destination == DesktopDestination.LOCAL_MUSIC -> DesktopLocalMusicPage(
            songs = pages.localSongs,
            onSongClick = { playSong(it, source = DesktopQueueSource(DesktopStrings["local_music", "Local Music"], PlaybackSourceType.BROWSE)) },
            contentPadding = contentPadding,
            menu = { song -> songMenu(song) },
        )
    }
}
