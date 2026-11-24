package com.music.bitchord.data.apple

import com.music.bitchord.data.model.APPLE_ALBUM_PREFIX
import com.music.bitchord.data.model.APPLE_ARTIST_PREFIX
import com.music.bitchord.data.model.APPLE_PLAYLIST_PREFIX
import com.music.bitchord.data.model.APPLE_SONG_PREFIX
import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Apple's catalog ids, kept recognisable.
 *
 * A row carries `apple:<kind>:<id>` so it never reads as a YouTube id: the
 * source matcher can send it down the metadata-only path, and the detail loader
 * can tell an album from a playlist. The same rule the Spotify rows follow —
 * provenance travels with the id.
 */
object AppleMusicSearch {

    const val SONG_PREFIX = APPLE_SONG_PREFIX
    const val ALBUM_PREFIX = APPLE_ALBUM_PREFIX
    const val ARTIST_PREFIX = APPLE_ARTIST_PREFIX
    const val PLAYLIST_PREFIX = APPLE_PLAYLIST_PREFIX

    /**
     * Search the catalog and map what comes back, in tab order: songs, albums,
     * artists, playlists. No account and no sign-in — the anonymous bearer
     * reads all of it.
     */
    fun results(term: String, limit: Int = 25): List<SearchResult> {
        val body = AppleMusicApi.search(
            term,
            listOf("songs", "albums", "artists", "playlists"),
            limit,
        ) ?: return emptyList()
        return parse(body)
    }

    /**
     * One page of an Apple search, for whichever tab the filter names.
     *
     * The same shape [com.music.bitchord.data.spotify.SpotifySearch.Page]
     * publishes, so the search pipeline treats the two catalogues alike: rows
     * in this app's shape, and the offset the next page starts at (null when
     * the catalogue is done).
     */
    suspend fun page(query: String, filter: SearchFilter, offset: Int = 0, limit: Int = 25): Page {
        val types = typesFor(filter)
        if (types.isEmpty()) {
            return Page(emptyList(), null, "the filter is not one Apple's catalogue answers")
        }
        return withContext(Dispatchers.IO) {
            val body = AppleMusicApi.search(query, types, limit, offset)
                ?: return@withContext Page(emptyList(), null, "the catalogue did not answer")
            val rows = parse(body, filter)
            // A full page means there may be more; a short one is the end. Apple
            // states no total for a search, so this is the only honest signal.
            val next = if (rows.size >= limit) offset + limit else null
            Page(
                rows = rows,
                nextOffset = next,
                diagnostic = "${rows.size} row(s) for types=${types.joinToString(",")}",
            )
        }
    }

    /** The catalog types a search tab maps to; empty for a tab Apple has none of. */
    private fun typesFor(filter: SearchFilter): List<String> = when (filter) {
        SearchFilter.SONGS -> listOf("songs")
        SearchFilter.ALBUMS -> listOf("albums")
        SearchFilter.ARTISTS -> listOf("artists")
        SearchFilter.PLAYLISTS -> listOf("playlists")
        SearchFilter.ALL -> listOf("songs", "albums", "artists", "playlists")
        else -> emptyList()
    }

    /**
     * One page of Apple's answer.
     *
     * [nextOffset] is where the next page starts, or null at the end of the
     * catalogue; [diagnostic] says what the answer held, because a blank list
     * is three different faults and they have three different fixes.
     */
    data class Page(
        val rows: List<SearchResult>,
        val nextOffset: Int?,
        val diagnostic: String = "",
    )

    /** Pure JSON → rows, so the mapping is testable without a network. */
    internal fun parse(body: JsonObject, filter: SearchFilter = SearchFilter.ALL): List<SearchResult> {
        val results = body["results"]?.jsonObject ?: return emptyList()
        val out = mutableListOf<SearchResult>()
        if (filter == SearchFilter.SONGS || filter == SearchFilter.ALL) {
            out += group(results, "songs").mapNotNull(::song).map { SearchResult.Track(it) }
        }
        if (filter == SearchFilter.ALBUMS || filter == SearchFilter.ALL) {
            out += group(results, "albums").mapNotNull { browse(it, BrowseType.ALBUM, ALBUM_PREFIX) }
                .map { SearchResult.Browse(it) }
        }
        if (filter == SearchFilter.ARTISTS || filter == SearchFilter.ALL) {
            out += group(results, "artists").mapNotNull { browse(it, BrowseType.ARTIST, ARTIST_PREFIX) }
                .map { SearchResult.Browse(it) }
        }
        if (filter == SearchFilter.PLAYLISTS || filter == SearchFilter.ALL) {
            out += group(results, "playlists").mapNotNull { browse(it, BrowseType.PLAYLIST, PLAYLIST_PREFIX) }
                .map { SearchResult.Browse(it) }
        }
        return out
    }

    private fun group(results: JsonObject, key: String): List<JsonObject> =
        results[key]?.jsonObject?.get("data")?.jsonArray
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()

    internal fun song(hit: JsonObject): Song? {
        val id = hit["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val a = hit["attributes"]?.jsonObject ?: return null
        val title = a["name"]?.jsonPrimitive?.contentOrNull ?: return null
        val artist = a["artistName"]?.jsonPrimitive?.contentOrNull ?: return null
        val albumId = a["albumId"]?.jsonPrimitive?.contentOrNull
            ?: hit["relationships"]?.jsonObject
                ?.get("albums")?.jsonObject
                ?.get("data")?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("id")?.jsonPrimitive?.contentOrNull
        val artistId = a["artistId"]?.jsonPrimitive?.contentOrNull
        val millis = a["durationInMillis"]?.jsonPrimitive?.intOrNull
        return Song(
            videoId = SONG_PREFIX + id,
            title = title,
            artist = artist,
            thumbnailUrl = artwork(a),
            durationText = millis?.let(::durationText),
            albumId = albumId?.let { ALBUM_PREFIX + it },
            albumName = a["albumName"]?.jsonPrimitive?.contentOrNull,
            artistId = artistId?.let { ARTIST_PREFIX + it },
        )
    }

    internal fun browse(hit: JsonObject, type: BrowseType, prefix: String): BrowseItem? {
        val id = hit["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val a = hit["attributes"]?.jsonObject ?: return null
        val title = a["name"]?.jsonPrimitive?.contentOrNull ?: return null
        // Albums and songs name an artist; playlists name a curator.
        val subtitle = a["artistName"]?.jsonPrimitive?.contentOrNull
            ?: a["curatorName"]?.jsonPrimitive?.contentOrNull
            ?: ""
        return BrowseItem(
            browseId = prefix + id,
            title = title,
            subtitle = subtitle,
            thumbnailUrl = artwork(a),
            type = type,
        )
    }

    /** Apple's artwork is a `{w}x{h}` template; ask for one square at [px]. */
    internal fun artwork(attributes: JsonObject, px: Int = 480): String? {
        val template = attributes["artwork"]?.jsonObject
            ?.get("url")?.jsonPrimitive?.contentOrNull
            ?: return null
        return template.replace("{w}", px.toString()).replace("{h}", px.toString())
    }

    private fun durationText(millis: Int): String {
        val total = (millis / 1000).coerceAtLeast(0)
        val minutes = total / 60
        val seconds = total % 60
        return "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}