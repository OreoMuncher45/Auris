package com.music.bitchord.data.spotify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A Spotify playlist's page id in the detail stack: this plus the playlist id (or [SpotifyLibrary.LIKED_ID]). */
const val SPOTIFY_PAGE_PREFIX = "spotify:playlist:"

/** A Spotify album's page id: this plus the album id. */
const val SPOTIFY_ALBUM_PREFIX = "spotify:album:"

/** A Spotify artist's page id: this plus the artist id. */
const val SPOTIFY_ARTIST_PREFIX = "spotify:artist:"

/** A Spotify show's page id: this plus the show id. */
const val SPOTIFY_SHOW_PREFIX = "spotify:show:"

/** A Spotify category's page id: this plus the page id. */
const val SPOTIFY_CATEGORY_PREFIX = "spotify:page:"

data class SpotifyPlaylist(
    val id: String,
    val name: String,
    val owner: String?,
    val imageUrl: String?,
)

data class SpotifyTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Int,
    val imageUrl: String?,
)

object SpotifyLibrary {
    /** The pseudo-playlist id for the Liked Songs collection, which has no playlist uri. */
    const val LIKED_ID = "liked"
    /** Spotify's own Liked Songs artwork, the purple heart; the collection has no cover of its own. */
    private const val LIKED_COVER = "https://misc.scdn.co/liked-songs/liked-songs-640.png"

    /**
     * Playlists in the library, however many pages it takes.
     *
     * @param maxPages stop after this many pages; 0 reads all of them. The
     *   library import wants the lot, because it is importing a library. The
     *   home screen wants *a* shelf, and paging through four hundred playlists
     *   to fill one is a lot of requests to answer a question nobody asked —
     *   on a backend that rate limits per client, that difference is the whole
     *   reason this parameter exists.
     */
    suspend fun playlists(maxPages: Int = 0): List<SpotifyPlaylist> = withContext(Dispatchers.IO) {
        val collected = mutableListOf<SpotifyPlaylist>()
        var offset = 0
        var pages = 0
        while (true) {
            val (items, total, raw) = playlistPage(offset)
            collected += items
            offset += raw
            pages++
            if (raw == 0 || offset >= total) break
            if (maxPages > 0 && pages >= maxPages) break
        }
        // Liked Songs is not a playlist to Spotify's library query, so it is
        // always offered first rather than depending on it turning up there.
        listOf(SpotifyPlaylist(id = LIKED_ID, name = "Liked Songs", owner = null, imageUrl = LIKED_COVER)) + collected
    }

    /**
     * Every track of a playlist, however long. [onPage] gets the running list
     * after each page, so a thousand-song playlist fills in as it loads
     * rather than appearing all at once at the end.
     */
    suspend fun tracks(
        playlistId: String,
        onPage: (List<SpotifyTrack>) -> Unit = {},
    ): List<SpotifyTrack> = withContext(Dispatchers.IO) {
        val collected = mutableListOf<SpotifyTrack>()
        var offset = 0
        while (true) {
            val (items, total, raw) = if (playlistId == LIKED_ID) {
                likedPage(offset)
            } else {
                trackPage(playlistId, offset)
            }
            collected += items
            offset += raw
            onPage(collected.toList())
            if (raw == 0 || offset >= total) break
        }
        collected
    }

    /**
     * The first [limit] tracks of a playlist, in one request.
     *
     * The counterpart to [tracks]: that reads a playlist, this reads enough of
     * one to draw it as a row on a home screen. Same backend, same credentials
     * — the difference is that this one asks for a page and stops.
     */
    suspend fun preview(playlistId: String, limit: Int = 12): List<SpotifyTrack> = withContext(Dispatchers.IO) {
        val page = if (playlistId == LIKED_ID) {
            likedPage(offset = 0, limit = limit)
        } else {
            trackPage(playlistId, offset = 0, limit = limit)
        }
        page.first
    }

    private suspend fun playlistPage(offset: Int): Triple<List<SpotifyPlaylist>, Int, Int> {
        val variables = buildJsonObject {
            putJsonArray("filters") { add("Playlists") }
            put("order", null as String?)
            put("textFilter", "")
            putJsonArray("features") {
                add("LIKED_SONGS")
                add("YOUR_EPISODES_V2")
                add("PRERELEASES")
                add("EVENTS")
            }
            put("limit", 50)
            put("offset", offset)
            put("flatten", true)
            putJsonArray("expandedFolders") {}
            put("folderUri", null as String?)
            put("includeFoldersWhenFlattening", false)
        }
        return parsePlaylistPage(gql("libraryV3", variables))
    }

    private suspend fun trackPage(
        playlistId: String,
        offset: Int,
        limit: Int = 100,
    ): Triple<List<SpotifyTrack>, Int, Int> {
        val variables = buildJsonObject {
            put("uri", "spotify:playlist:$playlistId")
            put("offset", offset)
            put("limit", limit)
            put("enableWatchFeedEntrypoint", false)
        }
        return parseTrackPage(gql("fetchPlaylist", variables))
    }

    /**
     * The playlist's cover at its largest. The library list only carries a
     * thumbnail, which looks soft once it fills a playlist page's header, so
     * the page asks for the real image after it opens.
     */
    suspend fun cover(playlistId: String): String? = withContext(Dispatchers.IO) {
        if (playlistId == LIKED_ID) return@withContext LIKED_COVER
        val variables = buildJsonObject {
            put("uri", "spotify:playlist:$playlistId")
            put("offset", 0)
            put("limit", 1)
            put("enableWatchFeedEntrypoint", false)
        }
        val root = gql("fetchPlaylist", variables)
        largestSource(root.obj("data")?.obj("playlistV2")?.obj("images"))
    }

    private suspend fun likedPage(
        offset: Int,
        limit: Int = 100,
    ): Triple<List<SpotifyTrack>, Int, Int> {
        val variables = buildJsonObject {
            put("offset", offset)
            put("limit", limit)
        }
        return parseLikedPage(gql("fetchLibraryTracks", variables))
    }

    /**
     * Runs a persisted operation and hands back the answer in the shape the
     * parsers above read: the whole document, with its `data` under `data`.
     *
     * The client that runs it is [SpotifyGraphQL], which knows the current
     * hash for the operation, the headers the web player sends and what to do
     * with a refusal. The hashes used to be written down here, and they had
     * gone stale: Spotify changes the hash of an operation with every build,
     * and a stale hash is refused in a way that looks exactly like an empty
     * library.
     */
    private suspend fun gql(operation: String, variables: JsonObject): JsonObject {
        val data = SpotifyGraphQL.query(operation, variables)
        return buildJsonObject { put("data", data) }
    }
}

/** The playlists kept, the library's total, and how many entries the page held before filtering. */
internal fun parsePlaylistPage(root: JsonObject): Triple<List<SpotifyPlaylist>, Int, Int> {
    val library = root.obj("data")?.obj("me")?.obj("libraryV3")
        ?: throw IllegalStateException("Spotify library response was empty")
    val rawItems = library.arr("items").orEmpty()
    val items = rawItems.mapNotNull { element ->
        val wrapper = element.jsonObject.obj("item") ?: return@mapNotNull null
        val typeName = wrapper.str("__typename").orEmpty()
        if (!typeName.contains("Playlist", ignoreCase = true)) return@mapNotNull null
        val data = wrapper.obj("data") ?: return@mapNotNull null
        if (data.str("__typename") != "Playlist") return@mapNotNull null
        val uri = wrapper.str("_uri") ?: return@mapNotNull null
        val name = data.str("name").orEmpty()
        if (name.isBlank()) return@mapNotNull null
        SpotifyPlaylist(
            id = uri.substringAfterLast(":"),
            name = name,
            owner = data.obj("ownerV2")?.obj("data")?.str("name"),
            imageUrl = coverUrl(data.obj("images")),
        )
    }
    return Triple(items, library.int("totalCount") ?: rawItems.size, rawItems.size)
}

/** The tracks kept, the playlist's total, and how many entries the page held before filtering. */
internal fun parseTrackPage(root: JsonObject): Triple<List<SpotifyTrack>, Int, Int> {
    val content = root.obj("data")?.obj("playlistV2")?.obj("content")
        ?: throw IllegalStateException("Spotify playlist response was empty")
    val rawItems = content.arr("items").orEmpty()
    val items = rawItems.mapNotNull { element ->
        val data = element.jsonObject.obj("itemV2")?.obj("data") ?: return@mapNotNull null
        val title = data.str("name").orEmpty()
        if (title.isBlank()) return@mapNotNull null
        val uri = data.str("uri") ?: data.str("_uri") ?: return@mapNotNull null
        val artist = data.obj("artists")?.arr("items").orEmpty().mapNotNull { artist ->
            artist.jsonObject.obj("profile")?.str("name")?.takeIf { it.isNotBlank() }
        }.joinToString(", ")
        val album = data.obj("albumOfTrack")
        SpotifyTrack(
            id = uri.substringAfterLast(":"),
            title = title,
            artist = artist,
            album = album?.str("name"),
            durationMs = data.obj("duration")?.int("totalMilliseconds") ?: 0,
            imageUrl = album?.obj("coverArt")?.arr("sources")?.lastUrl(),
        )
    }
    return Triple(items, content.int("totalCount") ?: rawItems.size, rawItems.size)
}

/** The Liked Songs page: `me.library.tracks`, each entry wrapping the track under `track`. */
internal fun parseLikedPage(root: JsonObject): Triple<List<SpotifyTrack>, Int, Int> {
    val tracks = root.obj("data")?.obj("me")?.obj("library")?.obj("tracks")
        ?: throw IllegalStateException("Spotify liked songs response was empty")
    val rawItems = tracks.arr("items").orEmpty()
    val items = rawItems.mapNotNull { element ->
        val wrapper = element.jsonObject.obj("track") ?: return@mapNotNull null
        val data = wrapper.obj("data") ?: return@mapNotNull null
        val title = data.str("name").orEmpty()
        if (title.isBlank()) return@mapNotNull null
        val uri = wrapper.str("_uri") ?: data.str("uri") ?: return@mapNotNull null
        val album = data.obj("albumOfTrack")
        SpotifyTrack(
            id = uri.substringAfterLast(":"),
            title = title,
            artist = data.obj("artists")?.arr("items").orEmpty().mapNotNull { artist ->
                artist.jsonObject.obj("profile")?.str("name")?.takeIf { it.isNotBlank() }
            }.joinToString(", "),
            album = album?.str("name"),
            durationMs = (data.obj("trackDuration") ?: data.obj("duration"))?.int("totalMilliseconds") ?: 0,
            imageUrl = album?.obj("coverArt")?.arr("sources")?.lastUrl(),
        )
    }
    return Triple(items, tracks.int("totalCount") ?: rawItems.size, rawItems.size)
}

/**
 * The widest source of an image's first entry. Spotify lists a playlist's
 * sizes in no fixed order — a 60px thumbnail can come last — so the list is
 * read by width rather than by position.
 */
private fun largestSource(images: JsonObject?): String? =
    images?.arr("items")?.firstOrNull()?.jsonObject?.arr("sources")
        ?.mapNotNull { source ->
            val url = source.jsonObject.str("url") ?: return@mapNotNull null
            url to (source.jsonObject.int("width") ?: 0)
        }
        ?.maxByOrNull { it.second }
        ?.first

private fun coverUrl(images: JsonObject?): String? = largestSource(images)

private fun JsonArray.lastUrl(): String? =
    mapNotNull { it.jsonObject.str("url") }.lastOrNull()

private fun JsonObject.obj(key: String): JsonObject? =
    this[key]?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonObject }.getOrNull() }

private fun JsonObject.arr(key: String): JsonArray? =
    this[key]?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonArray }.getOrNull() }

private fun JsonObject.str(key: String): String? =
    this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull

private fun JsonObject.int(key: String): Int? =
    this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.intOrNull
