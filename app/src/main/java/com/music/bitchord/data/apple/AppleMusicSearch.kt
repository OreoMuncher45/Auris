package com.music.bitchord.data.apple

import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
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

    const val SONG_PREFIX = "apple:song:"
    const val ALBUM_PREFIX = "apple:album:"
    const val ARTIST_PREFIX = "apple:artist:"
    const val PLAYLIST_PREFIX = "apple:playlist:"

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

    /** Pure JSON → rows, so the mapping is testable without a network. */
    internal fun parse(body: JsonObject): List<SearchResult> {
        val results = body["results"]?.jsonObject ?: return emptyList()
        val out = mutableListOf<SearchResult>()
        out += group(results, "songs").mapNotNull(::song).map { SearchResult.Track(it) }
        out += group(results, "albums").mapNotNull { browse(it, BrowseType.ALBUM, ALBUM_PREFIX) }
            .map { SearchResult.Browse(it) }
        out += group(results, "artists").mapNotNull { browse(it, BrowseType.ARTIST, ARTIST_PREFIX) }
            .map { SearchResult.Browse(it) }
        out += group(results, "playlists").mapNotNull { browse(it, BrowseType.PLAYLIST, PLAYLIST_PREFIX) }
            .map { SearchResult.Browse(it) }
        return out
    }

    private fun group(results: JsonObject, key: String): List<JsonObject> =
        results[key]?.jsonObject?.get("data")?.jsonArray
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()

    private fun song(hit: JsonObject): Song? {
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

    private fun browse(hit: JsonObject, type: BrowseType, prefix: String): BrowseItem? {
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