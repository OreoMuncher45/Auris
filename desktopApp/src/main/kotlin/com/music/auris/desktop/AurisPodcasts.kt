package com.music.auris.desktop

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.podcast.ItunesPodcasts
import com.music.bitchord.data.podcast.ItunesShow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The podcasts page's shelves, as the phone's page builds them.
 *
 * One shelf of recommendations and one per Apple Podcasts category, each searched for on its
 * own and published the moment its own answer lands. The searches are independent — a category
 * shelf does not wait for the recommendation shelf — and running them in a line meant the last
 * shelf waited for twelve round trips before anything could be drawn, which read as a page with
 * nothing on it.
 */
internal suspend fun fetchPodcastShelves(onShelves: (List<HomeShelf>) -> Unit) {
    val searches = buildList {
        add("Recommended podcasts" to "podcast")
        ItunesPodcasts.CATEGORIES.forEach { (label, term) -> add(label to "$term podcast") }
    }
    val shelves = LinkedHashMap<String, List<ItunesShow>>()
    val lock = Mutex()
    coroutineScope {
        searches.forEach { (label, term) ->
            launch(Dispatchers.IO) {
                val limit = if (label == "Recommended podcasts") 30 else 20
                val shows = runCatching { ItunesPodcasts.search(term, limit = limit) }
                    .getOrDefault(emptyList())
                if (shows.isEmpty()) return@launch
                lock.withLock {
                    shelves[label] = shows
                    // Published in the order the shelves are declared, not in the order the
                    // answers happen to arrive.
                    onShelves(
                        searches.mapNotNull { (name, _) ->
                            shelves[name]?.let { found -> HomeShelf(name, found.map { it.toShelfItem() }) }
                        },
                    )
                }
            }
        }
    }
}

/** A show from Apple's catalogue, as a card on the podcasts page. */
internal fun ItunesShow.toShelfItem(): ShelfItem = ShelfItem(
    title = name,
    subtitle = artist,
    thumbnailUrl = artworkUrl,
    videoId = null,
    browseId = "itunes:$collectionId",
)

/**
 * A show opened from Apple's catalogue, with its episodes.
 *
 * The episode audio comes straight from the show's own RSS feed, so nothing is downloaded
 * ahead of time: the rows are the feed's items, and playing one streams it.
 */
internal data class AurisPodcastShow(
    val collectionId: String,
    val name: String,
    val artist: String = "",
    val artworkUrl: String? = null,
    val genre: String? = null,
    val episodes: List<Song> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

/**
 * Loads a show and its episodes, the phone's way: the catalogue's entry, then its feed.
 *
 * The card the show was opened from already carries its name and cover, so a catalogue that
 * has no entry for it — or no answer — still leaves a page that says what was opened.
 */
internal suspend fun loadPodcastShow(
    collectionId: String,
    name: String,
    artist: String = "",
    artworkUrl: String? = null,
): AurisPodcastShow {
    val show = withContext(Dispatchers.IO) { ItunesPodcasts.lookup(collectionId) }
    if (show == null) {
        return AurisPodcastShow(
            collectionId = collectionId,
            name = name,
            artist = artist,
            artworkUrl = artworkUrl,
            loading = false,
            error = "That show is no longer in the catalogue",
        )
    }
    val episodes = runCatching { withContext(Dispatchers.IO) { ItunesPodcasts.episodes(show) } }
        .getOrDefault(emptyList())
    return AurisPodcastShow(
        collectionId = collectionId,
        name = show.name.ifBlank { name },
        artist = show.artist.ifBlank { artist },
        artworkUrl = show.artworkUrl ?: artworkUrl,
        genre = show.genre,
        episodes = episodes,
        loading = false,
    )
}

/**
 * The show's own page: its cover, its name, and the episodes it has published.
 *
 * A desktop page has no heading of its own — the window's title bar carries the name — so the
 * cover and the name sit at the top of the list, as they do on the phone.
 */
@Composable
internal fun AurisPodcastShowPage(
    page: AurisPodcastShow,
    onPlay: (Song) -> Unit,
    onPlayAll: (List<Song>) -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item(key = "header") {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(104.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.06f)),
                    contentAlignment = Alignment.Center,
                ) {
                    page.artworkUrl?.let { url ->
                        AurisArtwork(url, Modifier.size(104.dp).clip(RoundedCornerShape(10.dp)), px = COVER_ART_PX)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        page.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (page.artist.isNotBlank()) {
                        Text(
                            page.artist,
                            color = AurisSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    page.genre?.takeIf { it.isNotBlank() }?.let { genre ->
                        Text(
                            genre,
                            color = AurisSecondary.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    AurisPodcastButton("Play all", enabled = page.episodes.isNotEmpty()) {
                        onPlayAll(page.episodes)
                    }
                }
            }
        }
        when {
            page.loading -> item(key = "loading") {
                Text(
                    "Loading the episodes…",
                    color = AurisSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 18.dp),
                )
            }
            page.error != null -> item(key = "error") {
                Text(
                    page.error,
                    color = AurisDestructive,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 18.dp),
                )
            }
            page.episodes.isEmpty() -> item(key = "empty") {
                Text(
                    "This show has published no episodes.",
                    color = AurisSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 18.dp),
                )
            }
            else -> items(page.episodes, key = { it.videoId }) { episode ->
                AurisEpisodeRow(episode = episode, onClick = { onPlay(episode) })
            }
        }
    }
}

/** One episode: its title, its date, and how long it runs. */
@Composable
private fun AurisEpisodeRow(episode: Song, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                episode.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = listOfNotNull(
                episode.publishedDate?.takeIf { it.isNotBlank() },
                episode.durationText?.takeIf { it.isNotBlank() },
            ).joinToString(" • ")
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    color = AurisSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The page's own button: the accent fill the rest of the desktop uses for an action. */
@Composable
private fun AurisPodcastButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (enabled) AurisAccent else AurisAccent.copy(alpha = 0.35f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.Black, fontWeight = FontWeight.SemiBold)
    }
}

/** The cover art of a page, in the size the desktop draws covers at. */
private const val COVER_ART_PX = 320
