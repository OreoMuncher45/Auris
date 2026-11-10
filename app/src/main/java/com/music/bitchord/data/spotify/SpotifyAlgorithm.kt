package com.music.bitchord.data.spotify

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Spotify's own answer to "what plays next".
 *
 * ## Why this exists
 *
 * A mix built on a Spotify account but expanded through YouTube Music's radio
 * is not a Spotify mix. It is a Spotify-shaped list of YouTube songs: the
 * seeds are the listener's, and everything after them is somebody else's
 * algorithm. The listener asked for Spotify's taste and got YouTube's.
 *
 * So the expansion is Spotify's own, in this order:
 *
 * 1. **The station.** `inspiredby-mix` is the endpoint the web player itself
 *    uses for "start radio", and it is a playlist Spotify's algorithm made
 *    from the seed. It is the best answer and it is personalised, so it needs
 *    a signed-in session.
 * 2. **The artist graph.** When there is no station — signed out, or a seed
 *    this process has never seen — the artist's own Spotify page is read, and
 *    with it the artists Spotify says its listeners also play ("Fans also
 *    like"). Their top tracks are the expansion. This one is public
 *    catalogue, so it answers for a visitor too.
 *
 * Both end in the same place: Spotify's metadata, matched to something this
 * app can actually play.
 */
internal object SpotifyAlgorithm {

    private const val TAG = "SpotifyAlgorithm"

    /** How many related artists are followed, and how many tracks each gives. */
    private const val RELATED_ARTISTS = 3
    private const val TRACKS_PER_ARTIST = 3

    /**
     * The station Spotify made for [seed], as songs this app can play.
     *
     * Empty when the seed is not a Spotify track this session has seen, or
     * when Spotify would not make a station for it.
     */
    suspend fun station(seed: Song, limit: Int): List<Song> = withContext(Dispatchers.IO) {
        if (limit <= 0) return@withContext emptyList()
        val id = SpotifyHomeSource.spotifyIdOf(seed) ?: return@withContext emptyList()
        val tracks = SpotifyRadio.radioTracks("spotify:track:$id", limit = limit)
        if (tracks.isEmpty()) {
            Log.w(TAG, "Spotify made no station for '$id'")
            return@withContext emptyList()
        }
        SpotifyHomeSource.matchTracks(tracks).take(limit)
    }

    /**
     * Spotify's related-artist expansion for [seed], as playable songs.
     *
     * The seed's artist is looked up in Spotify's own catalogue, their page
     * is read, and the artists Spotify puts beside them ("Fans also like")
     * give the tracks. This is the expansion a visitor gets — it is public
     * catalogue, not a personalised list.
     */
    suspend fun related(seed: Song, limit: Int): List<Song> = withContext(Dispatchers.IO) {
        if (limit <= 0) return@withContext emptyList()
        val name = seed.artist.trim()
        if (name.isBlank()) return@withContext emptyList()
        val artistId = artistIdFor(name) ?: run {
            Log.w(TAG, "Spotify has no artist called '$name'")
            return@withContext emptyList()
        }
        val page = runCatching { SpotifyPages.artist(artistId) }.getOrElse { error ->
            Log.w(TAG, "the Spotify page of '$name' could not be read: ${error.message}")
            return@withContext emptyList()
        }
        val relatedIds = page.shelves
            .firstOrNull { shelf -> shelf.title.contains("Fans also like", ignoreCase = true) }
            ?.items.orEmpty()
            .mapNotNull { item -> item.browseId?.removePrefix(SPOTIFY_ARTIST_PREFIX) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(RELATED_ARTISTS)
        if (relatedIds.isEmpty()) return@withContext emptyList()
        val tracks = mutableListOf<SpotifyTrack>()
        for (id in relatedIds) {
            val relatedPage = runCatching { SpotifyPages.artist(id) }.getOrNull() ?: continue
            tracks += relatedPage.tracks.take(TRACKS_PER_ARTIST)
        }
        if (tracks.isEmpty()) return@withContext emptyList()
        SpotifyHomeSource.matchTracks(tracks).take(limit)
    }

    /**
     * The best answer Spotify can give for [seed]: the station first, the
     * artist graph second.
     *
     * The order matters: the station is Spotify's own mix of a track, and the
     * artist graph is a looser thing Spotify offers anyone.
     */
    suspend fun more(seed: Song, limit: Int): List<Song> {
        val station = station(seed, limit)
        if (station.isNotEmpty()) return station
        return related(seed, limit)
    }

    /**
     * The Spotify id of the artist called [name], or null.
     *
     * An exact name match wins over the first hit: a search for "The Weeknd"
     * must not answer with a tribute act called "Weeknd Radio".
     */
    private suspend fun artistIdFor(name: String): String? {
        val variables = buildJsonObject {
            put("term", name)
            put("limit", 5)
            put("numberOfTopResults", 5)
        }
        val root = runCatching { SpotifyGraphQL.query("assistedCurationSearch", variables) }.getOrNull()
            ?: return null
        val candidates = root.obj("searchV2")?.obj("artists")?.arr("items").orEmpty()
            .mapNotNull { item -> item.objOrNull()?.obj("data") }
        val exact = candidates.firstOrNull { data ->
            data.obj("profile")?.str("name")?.equals(name, ignoreCase = true) == true
        } ?: candidates.firstOrNull()
        val uri = exact?.str("uri")?.takeIf { it.startsWith("spotify:artist:") } ?: return null
        return uri.substringAfterLast(':')
    }
}
