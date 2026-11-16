package com.music.bitchord.data.spotify

import com.music.bitchord.data.DebugLog as Log
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
 * Spotify, as this app reads it.
 *
 * Two things live here, and both are about the listener rather than about a
 * feed:
 *
 *  - [listens]: what they are playing over there — their top tracks and what
 *    they played lately — matched to something this app can play. That is the
 *    one thing Spotify contributes to the mix, and it is *material*, not a
 *    second algorithm: the mix is built by one engine and expanded through
 *    one radio, and Spotify's listening is what seeds it.
 *  - the row a Spotify page draws: [songFor], for a catalogue track that has
 *    no playable id behind it yet.
 *
 * ## What is not here any more
 *
 * Spotify's own home page — its curation, its cards, its public browse lists
 * — and its own algorithm for what comes next. Both were the app's second
 * algorithm: a second feed that repeated the same track across its rows, a
 * second radio that answered with the listener's saved songs whether they had
 * asked for that or not, and a second answer to the same question. The
 * homepage is YouTube Music's and the radio is YouTube Music's; what Spotify
 * knows about the listener still comes through [listens].
 */
internal object SpotifyHomeSource {

    private const val TAG = "SpotifyHome"

    /** How many matches may be in flight at once; each one is a search. */
    private const val MATCH_CONCURRENCY = 4

    /**
     * How many Spotify tracks are read as the listener's listening.
     *
     * Bounded: each one is matched on YouTube Music before it can be played,
     * and a mix is not worth a hundred searches. Twelve is enough to seed the
     * radio from and to fill a pool with, and the matches are remembered, so
     * the next mix costs nothing.
     */
    private const val LISTEN_SEEDS = 12

    /**
     * Spotify track id to the track this app will play for it.
     *
     * In memory for now, and deliberately: a match is a *search result*, and a
     * stale one is a wrong song played confidently. A track that has been seen
     * once in a session loads instantly; a fresh process asks again.
     */
    private val matches = ConcurrentHashMap<String, Song>()

    /** Whether a listener's session can be read. */
    fun isSignedIn(): Boolean = AppSettings.spotifySpdcToken.value.isNotBlank()

    /**
     * The listener's Spotify listening, as playable tracks.
     *
     * Their top tracks and what they played lately — what they *listen* to,
     * not what they saved. Their liked songs are not read here: the mix asks
     * the catalogue for songs like the ones they liked only when they say so,
     * and a shelf of liked songs read on its own puts their likes back into
     * every mix whether they asked for them or not.
     *
     * Empty without a session: there is nobody's listening to read, and the
     * public catalogue's editorial playlists are not a listener's listening.
     */
    suspend fun listens(limit: Int = LISTEN_SEEDS): List<Song> = withContext(Dispatchers.IO) {
        if (!isSignedIn()) return@withContext emptyList()
        val top = runCatching { SpotifyWebApi.topTracks(limit = limit) }.getOrNull().orEmpty()
        val recent = runCatching { SpotifyWebApi.recentlyPlayed(limit = limit) }.getOrNull().orEmpty()
        val tracks = (top + recent).distinctBy { it.id }.take(limit)
        songs(tracks)
    }

    /**
     * Matches each track to something playable, in parallel and bounded.
     *
     * The bound is not politeness: each match is a YouTube search, and a row
     * of twelve set loose at once is twelve searches competing with whatever
     * the listener is already playing.
     */
    private suspend fun matched(tracks: List<SpotifyTrack>): List<Pair<SpotifyTrack, Song>> = coroutineScope {
        val gate = Semaphore(MATCH_CONCURRENCY)
        tracks.distinctBy { it.id }.map { track ->
            async(Dispatchers.IO) {
                val cached = matches[track.id]
                if (cached != null) return@async track to cached
                // Guarded: a matcher that throws would otherwise take the whole
                // read down with it — the exception leaves awaitAll, and a
                // search that failed looks exactly like a catalogue that
                // refused.
                val matched = runCatching { gate.withPermit { SpotifyImporter.matchTrack(track) } }
                    .onFailure {
                        Log.w(TAG, "Searching YouTube Music for '${track.title}' failed: ${it.message}")
                    }
                    .getOrNull()
                matched?.let {
                    matches[track.id] = it
                    track to it
                }
            }
        }.awaitAll().filterNotNull()
    }

    /** The playable songs behind [tracks], for a seed pool. */
    private suspend fun songs(tracks: List<SpotifyTrack>): List<Song> =
        matched(tracks).map { (_, song) -> song }

    /**
     * A Spotify row as a queue entry of its own.
     *
     * This is what a Spotify search shows: Spotify's catalogue, not a list of
     * the tracks that happened to match somewhere else. The id says so —
     * `spotify:<id>` — and the player resolves it by asking the enabled sources
     * for the recording by name (see `PlaybackService`'s resolver). Matching is
     * no longer a condition for *appearing*: a catalogue with a track none of
     * your sources hold is still a catalogue, and hiding the row was hiding the
     * search.
     */
    internal fun songFor(track: SpotifyTrack): Song {
        val key = "$SPOTIFY_KEY_PREFIX${track.id}"
        return Song(
            videoId = key,
            title = track.title,
            artist = track.artist,
            thumbnailUrl = track.imageUrl,
            durationText = track.durationMs.takeIf { it > 0 }?.let { ms ->
                val total = ms / 1000
                "%d:%02d".format(total / 60, total % 60)
            },
            albumName = track.album,
        )
    }

    /** The prefix that marks a row as Spotify's own, with no playable id behind it. */
    const val SPOTIFY_KEY_PREFIX = "spotify:"
}

/** The card on a shelf, as this app draws one. */
internal fun SpotifyCard.item(): ShelfItem? {
    if (title.isBlank()) return null
    return ShelfItem(
        title = title,
        subtitle = subtitle,
        thumbnailUrl = imageUrl,
        videoId = null,
        browseId = browseIdOf(),
        year = year,
        popularity = popularity,
    )
}

/**
 * The address the card opens.
 *
 * A playlist opens as a playlist page, an album as an album page and an
 * artist as an artist page — each with its own prefix, so the page that opens
 * knows what it is reading without being told.
 */
internal fun SpotifyCard.browseIdOf(): String? = when (kind) {
    SpotifyCard.Kind.PLAYLIST -> if (id == "tracks") SPOTIFY_PAGE_PREFIX + SpotifyLibrary.LIKED_ID
        else SPOTIFY_PAGE_PREFIX + id
    SpotifyCard.Kind.ALBUM -> SPOTIFY_ALBUM_PREFIX + id
    SpotifyCard.Kind.ARTIST -> SPOTIFY_ARTIST_PREFIX + id
    SpotifyCard.Kind.SHOW -> SPOTIFY_SHOW_PREFIX + id
    SpotifyCard.Kind.CATEGORY -> SPOTIFY_CATEGORY_PREFIX + id
    SpotifyCard.Kind.TRACK -> null
    SpotifyCard.Kind.OTHER -> null
}
