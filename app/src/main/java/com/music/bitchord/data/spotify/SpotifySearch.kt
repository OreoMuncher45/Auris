package com.music.bitchord.data.spotify

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Spotify's own catalogue search, read through the web player's backend.
 *
 * ## Why this and not the public `/v1` search
 *
 * The public API's search is rate limited *per client*, and this app's client
 * is the web player's — shared by everyone who harvests a token from it. A
 * search asked for through `/v1` came back `429` on a signed-in session and
 * the results list was empty, with the reason in a log line nobody reads.
 *
 * The web player's backend is the same one the home feed is read through, and
 * it is not the surface that limits. So the search is asked there, with the
 * player's own operation names and its own variables.
 *
 * ## What comes back
 *
 * Four lists, because Spotify's search page has four tabs and this app draws
 * the same four: tracks, albums, artists and playlists. A track is metadata
 * only — the audio comes from YouTube Music and the addons — so a track row
 * is matched to something playable before it is drawn, and a track that
 * matches nothing is dropped rather than drawn as a row that cannot play.
 *
 * The operation names and the variable shape come from the web player itself
 * (and from the myspoti reference client, which reads the same backend);
 * they change with Spotify's build, so they are looked up in the operation
 * table rather than written down as hashes.
 */
internal object SpotifySearch {

    private const val TAG = "SpotifySearch"

    /**
     * One page of Spotify's answer.
     *
     * [nextOffset] is where the next page starts, or null when the catalogue
     * has no more to give. Spotify paginates by number, not by a continuation
     * token, so this is an offset and not a string.
     */
    data class Page(
        val rows: List<SearchResult>,
        val nextOffset: Int?,
    )

    /**
     * Searches Spotify's catalogue for [query].
     *
     * The tabs the filter asks for are asked at once: they are independent
     * requests and the page wants all of them, so asking them in parallel is
     * the difference between one round trip's wall time and four.
     */
    suspend fun searchPage(
        query: String,
        filter: SearchFilter,
        offset: Int = 0,
    ): Page = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext Page(emptyList(), null)
        // One operation answers for every tab now, and the variable it wants
        // is `term` — the old four operations asked for `searchTerm`, and
        // they are no longer in the web player at all. The answer has the
        // same shape: `data.searchV2` holds a section per kind.
        val variables = buildJsonObject {
            put("term", query)
            put("limit", limitOf(filter))
            put("numberOfTopResults", limitOf(filter))
        }
        val root = runCatching { SpotifyGraphQL.query("assistedCurationSearch", variables) }
            .onFailure { error ->
                Log.w(TAG, "Spotify's search could not be read: ${error.message}")
            }
            .getOrNull()
        val section = root?.obj("searchV2")
        if (section == null) {
            Log.w(TAG, "Spotify's search came back without a search section")
            return@withContext Page(emptyList(), null)
        }

        val trackItems = section.obj("tracksV2")?.arr("items").orEmpty()
        val albumItems = section.obj("albumsV2")?.arr("items").orEmpty()
        val artistItems = section.obj("artists")?.arr("items").orEmpty()
        // Playlists are no longer a section of their own: they come back
        // among the top results, each with the kind it is.
        val playlistItems = section.obj("playlists")?.arr("items").orEmpty()
            .ifEmpty {
                section.obj("topResultsV2")?.arr("itemsV2").orEmpty()
                    .mapNotNull { item -> item.objOrNull()?.obj("item") }
            }

        val rows = mutableListOf<SearchResult>()
        if (filter == SearchFilter.SONGS || filter == SearchFilter.ALL) {
            SpotifyHomeSource.matchTracks(parseTracks(trackItems)).forEach { song ->
                rows += SearchResult.Track(song)
            }
        }
        if (filter == SearchFilter.ALBUMS || filter == SearchFilter.ALL) {
            albumItems.mapNotNull { parseAlbum(it.objOrNull()?.obj("data")) }
                .forEach { rows += it.row() }
        }
        if (filter == SearchFilter.ARTISTS || filter == SearchFilter.ALL) {
            artistItems.mapNotNull { parseArtist(it.objOrNull()?.obj("data")) }
                .forEach { rows += it.row() }
        }
        if (filter == SearchFilter.PLAYLISTS || filter == SearchFilter.ALL) {
            playlistItems.mapNotNull { parsePlaylist(it.objOrNull()?.obj("data")) }
                .forEach { rows += it.row() }
        }
        Page(rows, null)
    }

    /** How many rows one tab is asked for. */
    private fun limitOf(filter: SearchFilter): Int = when (filter) {
        SearchFilter.SONGS -> 12
        else -> 8
    }

    // -- parsing ------------------------------------------------------------

    private fun parseTracks(items: List<kotlinx.serialization.json.JsonElement>): List<SpotifyTrack> =
        items.mapNotNull { element ->
            val data = element.objOrNull()?.obj("item")?.obj("data") ?: return@mapNotNull null
            val uri = data.str("uri") ?: return@mapNotNull null
            if (!uri.startsWith("spotify:track:")) return@mapNotNull null
            val title = data.str("name").orEmpty()
            if (title.isBlank()) return@mapNotNull null
            val album = data.obj("albumOfTrack")
            SpotifyTrack(
                id = uri.substringAfterLast(':'),
                title = title,
                artist = artistsOf(data),
                album = album?.str("name"),
                durationMs = data.obj("duration")?.int("totalMilliseconds")
                    ?: data.obj("trackDuration")?.int("totalMilliseconds")
                    ?: 0,
                imageUrl = album?.obj("coverArt")?.arr("sources")?.bestSourceUrl(),
            )
        }

    private fun parseAlbum(data: JsonObject?): SpotifyCard? {
        data ?: return null
        val uri = data.str("uri")?.takeIf { it.startsWith("spotify:album:") } ?: return null
        val title = data.str("name").orEmpty()
        if (title.isBlank()) return null
        return SpotifyCard(
            uri = uri,
            id = uri.substringAfterLast(':'),
            kind = SpotifyCard.Kind.ALBUM,
            title = title,
            subtitle = artistsOf(data),
            imageUrl = data.obj("coverArt")?.arr("sources")?.bestSourceUrl(),
        )
    }

    private fun parseArtist(data: JsonObject?): SpotifyCard? {
        data ?: return null
        val uri = data.str("uri")?.takeIf { it.startsWith("spotify:artist:") } ?: return null
        val title = data.obj("profile")?.str("name").orEmpty()
        if (title.isBlank()) return null
        return SpotifyCard(
            uri = uri,
            id = uri.substringAfterLast(':'),
            kind = SpotifyCard.Kind.ARTIST,
            title = title,
            subtitle = "",
            imageUrl = data.obj("visuals")?.obj("avatarImage")?.arr("sources")?.bestSourceUrl(),
        )
    }

    private fun parsePlaylist(data: JsonObject?): SpotifyCard? {
        data ?: return null
        val uri = data.str("uri")?.takeIf { it.startsWith("spotify:playlist:") } ?: return null
        val title = data.str("name").orEmpty()
        if (title.isBlank()) return null
        return SpotifyCard(
            uri = uri,
            id = uri.substringAfterLast(':'),
            kind = SpotifyCard.Kind.PLAYLIST,
            title = title,
            subtitle = data.obj("ownerV2")?.obj("data")?.str("name").orEmpty(),
            imageUrl = data.obj("images")?.arr("items")?.firstOrNull()
                ?.objOrNull()?.arr("sources")?.bestSourceUrl(),
        )
    }

    /** The artists of a track, album or playlist, as one line. */
    private fun artistsOf(data: JsonObject): String {
        val names = data.obj("artists")?.arr("items").orEmpty().mapNotNull { artist ->
            artist.objOrNull()?.obj("profile")?.str("name")?.takeIf { it.isNotBlank() }
        }
        if (names.isNotEmpty()) return names.joinToString(", ")
        return data.str("firstArtist.items.0.profile.name").orEmpty()
    }

    /** A collection, as a row that opens a page of its own. */
    private fun SpotifyCard.row(): SearchResult.Browse = SearchResult.Browse(
        BrowseItem(
            browseId = browseIdOf().orEmpty(),
            title = title,
            subtitle = subtitle,
            thumbnailUrl = imageUrl,
            type = when (kind) {
                SpotifyCard.Kind.ALBUM -> BrowseType.ALBUM
                SpotifyCard.Kind.ARTIST -> BrowseType.ARTIST
                SpotifyCard.Kind.PLAYLIST -> BrowseType.PLAYLIST
                else -> BrowseType.OTHER
            },
        ),
    )
}
