package com.music.bitchord.data.spotify

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The Spotify side of the homepage: Spotify's curation, as playable rows.
 *
 * ## What a row is
 *
 * Every row this produces is a row of *tracks*, each already matched to the
 * copy this app will actually play — a YouTube video, or whatever the source
 * ranking hands over instead. That is the shape the switch promises: the
 * algorithm comes from Spotify and the audio keeps coming from the existing
 * path, so a Spotify row plays exactly like a YouTube one.
 *
 * The matching is why rows are capped at [TRACKS_PER_ROW] and why there is a
 * match cache behind them. Matching one track means a YouTube search, so a
 * twenty-track row is twenty searches; the cap keeps the first paint bounded and
 * the cache means the second visit to a row costs nothing at all.
 *
 * ## Signed in, and not
 *
 * Signed in, the rows are the listener's own: what they played recently and
 * what they have had on repeat. Signed out there is no history to read, so the
 * rows come from Spotify's public curation — the featured list — opened up and
 * matched the same way. Both go through one matching path, which is what makes
 * "logged out still shows music" a property of the data rather than a special
 * case in the UI.
 *
 * ## What this is not
 *
 * It is not a byte-for-byte copy of Spotify's own homepage. The private
 * `api-partner` GraphQL that draws that page is behind persisted query hashes
 * that change with every player build, and reading it means scraping Spotify's
 * own JavaScript bundle to keep working. This reads the documented v1 API
 * instead: a smaller, steadier surface, at the cost of different ordering.
 */
internal object SpotifyHomeSource {

    private const val TAG = "SpotifyHome"

    /**
     * How many tracks of a row are matched and shown.
     *
     * A cap rather than the whole collection: a featured playlist can hold
     * hundreds of tracks, and matching them would be hundreds of searches for
     * cards nobody scrolls to. Twelve fills a row at any phone width.
     */
    private const val TRACKS_PER_ROW = 12

    /** How many featured playlists are opened into rows for a signed-out home. */
    private const val FEATURED_ROWS = 3

    /** How many matches may be in flight at once; each one is a search. */
    private const val MATCH_CONCURRENCY = 4

    /**
     * Spotify track id to the track this app will play for it.
     *
     * In memory for now, and deliberately: a match is a *search result*, and a
     * stale one is a wrong song played confidently. A row that has been seen
     * once in a session loads instantly; a fresh process asks again.
     */
    private val matches = ConcurrentHashMap<String, Song>()

    /** Whether a listener's session can be read; see [SpotifyToken][com.music.bitchord.data.canvas.SpotifyToken]. */
    fun isSignedIn(): Boolean = AppSettings.spotifySpdcToken.value.isNotBlank()

    /**
     * The provider's rows, in the order they should be drawn.
     *
     * Every call is a fresh read of Spotify: this is the feed, and a feed that
     * was cached is a feed that shows yesterday. The *matches* behind it are
     * cached, which is what keeps a re-open cheap.
     *
     * A row that cannot be read — an endpoint that refuses, a token that just
     * expired — is left out rather than drawn empty, so one failure cannot blank
     * the page. What was dropped is logged by name, which is the only way to tell
     * "Spotify refused this" from "there was nothing to show".
     */
    suspend fun shelves(): List<HomeShelf> = withContext(Dispatchers.IO) {
        val rows = if (isSignedIn()) signedInRows() else signedOutRows()
        rows.ifEmpty {
            Log.w(TAG, "no Spotify rows could be read; the home has nothing to show from this provider")
            emptyList()
        }
    }

    /**
     * The listener's own rows.
     *
     * Recently played first: it is the row that says the feed is *theirs*, and
     * it is also the one that changes every time the app is opened, which is what
     * a homepage is for. Top tracks follows, over the last few weeks rather than
     * the year — a year of listening changes too slowly to look like anything
     * that happened today.
     */
    private suspend fun signedInRows(): List<HomeShelf> = coroutineScope {
        val recent = async { SpotifyWebApi.recentlyPlayed(limit = TRACKS_PER_ROW * 2) }
        val top = async { SpotifyWebApi.topTracks(limit = TRACKS_PER_ROW * 2) }
        val featured = async { featuredRows(count = 2) }

        buildList {
            row("Recently played", recent.await())?.let(::add)
            row("On repeat", top.await())?.let(::add)
        } + featured.await()
    }

    /**
     * Rows for a listener who has not signed in.
     *
     * There is no history to read, so the curation is Spotify's own: the featured
     * list, opened into rows. That is what makes the provider useful before a
     * sign-in instead of an empty page begging for one.
     */
    private suspend fun signedOutRows(): List<HomeShelf> = featuredRows(count = FEATURED_ROWS)

    /**
     * Spotify's featured playlists, each opened into a row of its first tracks.
     *
     * A playlist *tile* would be the other way to draw this, and it is the way
     * Spotify's own page does it — but a tile needs a screen to open, and this
     * slice has none yet, so a tile here would be a card that does nothing when
     * tapped. Opening the playlist into tracks keeps every card playable with the
     * navigation that already exists.
     */
    private suspend fun featuredRows(count: Int): List<HomeShelf> = coroutineScope {
        val playlists = runCatching { SpotifyWebApi.featuredPlaylists(limit = count) }
            .onFailure { Log.w(TAG, "featured playlists could not be read: ${it.message}") }
            .getOrDefault(emptyList())
        if (playlists.isEmpty()) {
            return@coroutineScope emptyList()
        }
        playlists.take(count).map { playlist ->
            async {
                val tracks = runCatching { SpotifyWebApi.playlistTracks(playlist.id, limit = TRACKS_PER_ROW) }
                    .onFailure { Log.w(TAG, "tracks of ${playlist.id} could not be read: ${it.message}") }
                    .getOrDefault(emptyList())
                row(playlist.name, tracks)
            }
        }.awaitAll().filterNotNull()
    }

    /**
     * One row of tracks, matched, or null when nothing in it could be matched.
     *
     * Null means the row is dropped rather than drawn as a heading over an empty
     * space. A match failing is ordinary — a remix, a regional edit, a track
     * YouTube does not carry — so a row is only worth drawing if enough of it
     * survived to be a row at all.
     */
    private suspend fun row(title: String, tracks: List<SpotifyTrack>?): HomeShelf? {
        if (tracks.isNullOrEmpty()) return null
        val items = match(tracks.take(TRACKS_PER_ROW))
        if (items.isEmpty()) {
            Log.d(TAG, "row '$title' had ${tracks.size} tracks and none matched; dropped")
            return null
        }
        return HomeShelf(title = title, items = items)
    }

    /**
     * Matches each track to something playable, in parallel and bounded.
     *
     * The bound is not politeness: each match is a YouTube search, and a row of
     * twelve set loose at once is twelve searches competing with whatever the
     * listener is already playing. Four at a time keeps a row landing in about a
     * second without the app's own playback waiting behind it.
     */
    private suspend fun match(tracks: List<SpotifyTrack>): List<ShelfItem> = coroutineScope {
        val gate = Semaphore(MATCH_CONCURRENCY)
        tracks.map { track ->
            async(Dispatchers.IO) {
                val song = matches[track.id]
                    ?: gate.withPermit { SpotifyImporter.matchTrack(track) }
                        ?.also { matches[track.id] = it }
                    ?: return@async null
                ShelfItem(
                    title = song.title.ifBlank { track.title },
                    subtitle = song.artist.ifBlank { track.artist },
                    thumbnailUrl = song.thumbnailUrl ?: track.imageUrl,
                    videoId = song.videoId,
                    browseId = null,
                )
            }
        }.awaitAll().filterNotNull()
    }
}
