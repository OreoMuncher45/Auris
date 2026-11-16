package com.music.bitchord.desktop

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.music.bitchord.data.model.ArtistPage
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.LibraryPage
import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.MoodGenreSection
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState

/**
 * What the window's pages show: each page's loaded content, and the album, artist, show or
 * genre opened over it.
 *
 * The loaders in [BitChordDesktopApp] write these and [DesktopPageHost] reads them. As loose
 * locals they were captured one by one by every lambda between the two; held here they are one.
 * The initial values are the ones the locals had.
 */
@Stable
internal class DesktopPagesState(persistence: DesktopPersistence) {
    var homeState by mutableStateOf<UiState<List<HomeShelf>>>(UiState.Loading)

    /**
     * The home feed is paged: `FEmusic_home` answers with a couple of shelves and a token, and
     * everything else arrives by following it.
     */
    var homeContinuation by mutableStateOf<String?>(null)
    var homeLoadingMore by mutableStateOf(false)

    /**
     * Recently Played has its own request and its own skeleton at the head of the page, as on the
     * phone; it is only asked for with an account to have played on.
     */
    var homeRecentsLoading by mutableStateOf(false)

    // The podcasts page: YouTube Music's own shows, Apple's catalogue shelves, and the search
    // over Apple's catalogue — the same three sources the phone's page draws on.
    var podcastShows by mutableStateOf<UiState<List<ShelfItem>>>(UiState.Loading)
    var podcastSections by mutableStateOf<UiState<List<HomeShelf>>>(UiState.Success(emptyList()))
    var podcastSearchQuery by mutableStateOf("")
    var podcastSearchResults by mutableStateOf<UiState<List<ShelfItem>>>(UiState.Success(emptyList()))
    var podcastReloads by mutableStateOf(0)

    /** The show opened from Apple's catalogue, with its episodes. */
    var openedPodcastShow by mutableStateOf<DesktopPodcastShow?>(null)

    var exploreState by mutableStateOf<UiState<List<MoodGenreSection>>>(UiState.Loading)
    var selectedMoodGenre by mutableStateOf<MoodGenre?>(null)

    // Bumped by a retry so the loaders re-run without the state they are keyed on having to
    // change to something and back again.
    var exploreReloads by mutableStateOf(0)
    var moodGenreReloads by mutableStateOf(0)
    var moodGenreShelves by mutableStateOf<UiState<List<HomeShelf>>>(UiState.Loading)

    /**
     * What was played on this computer. The account's own history replaces it while signed in —
     * see [remoteHistory] — because that is what Android's History screen is and what makes a
     * phone and a desktop agree.
     */
    var history by mutableStateOf(persistence.history())
    var remoteHistory by mutableStateOf<List<Song>>(emptyList())
    var localSongs by mutableStateOf<List<Song>>(emptyList())
    var replayPeriod by mutableStateOf(DesktopReplayPeriod.ALL_TIME)
    var replaySummary by mutableStateOf(DesktopListeningStats.summary())

    /** The account's own library, fetched once a session is in force. */
    var libraryState by mutableStateOf<UiState<LibraryPage>>(UiState.Loading)

    /** A Library shelf's "Show all", open as a grid in place of the page. */
    var libraryShowAll by mutableStateOf<HomeShelf?>(null)

    var openedCollection by mutableStateOf<DesktopCollection?>(null)
    var collectionLoadingMore by mutableStateOf(false)
    var collectionError by mutableStateOf<String?>(null)
    var collectionReloads by mutableStateOf(0)

    var openedArtist by mutableStateOf<DesktopArtistTarget?>(null)
    var artistState by mutableStateOf<UiState<ArtistPage>>(UiState.Loading)
    var artistReloads by mutableStateOf(0)
}
