package com.music.bitchord.data.spotify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
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

    /** How many tracks one page of a playlist asks for. */
    private const val PAGE_SIZE = 100

    /**
     * How many pages one playlist may take.
     *
     * A bound, not a plan: a playlist with no stated length is asked again
     * until Spotify answers with an empty page, and this is what stops a
     * server that always answers with something from asking forever.
     */
    private const val MAX_PAGES = 100
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
        var pages = 0
        while (pages < MAX_PAGES) {
            val page = if (playlistId == LIKED_ID) {
                likedPage(offset)
            } else {
                trackPage(playlistId, offset)
            }
            collected += page.items
            offset += page.raw
            onPage(collected.toList())
            pages++
            if (page.raw == 0) break
            val total = page.total
            if (total != null) {
                if (offset >= total) break
            } else {
                // The playlist never said how long it is, and that is not a
                // licence to stop after the first page. A page that came
                // back with eight entries is eight entries of a longer
                // playlist: treating the page's own size as the playlist's
                // total is what made a daily mix stop at eight songs with
                // nothing more to come. So the next page is asked, and the
                // one after that, until Spotify answers with an empty page
                // — a short page is not the end, because the server caps
                // its pages and a capped page is always short.
            }
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
        page.items
    }

    private suspend fun playlistPage(offset: Int): Triple<List<SpotifyPlaylist>, Int, Int> {
        val variables = buildJsonObject {
            putJsonArray("filters") { add("Playlists") }
            put("order", null as String?)
            put("textFilter", "")
            putJsonArray("features") {
                add("LIKED_SONGS")
                // Not asked for: this app has no episode player, and the
                // library's "Your Episodes" shelf is a list of things that
                // cannot be queued. See [SpotifyLongForm].
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
        limit: Int = PAGE_SIZE,
    ): TrackPage {
        // The variables are the ones the web player itself sends for a
        // playlist's contents. The operation, the hash and the shape of the
        // answer are one thing — `fetchPlaylist`, `fetchPlaylistContents`
        // and `fetchPlaylistMetadata` are the same persisted query under
        // three names — and what decides the answer is the variables.
        val variables = buildJsonObject {
            put("uri", "spotify:playlist:$playlistId")
            put("offset", offset)
            put("limit", limit)
            // Episodes are not asked for, and not rated: there is nothing here
            // that can play one. See [SpotifyLongForm].
            put("includeEpisodeContentRatingsV2", false)
        }
        return parseTrackPage(gql("fetchPlaylistContents", variables))
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
        limit: Int = PAGE_SIZE,
    ): TrackPage {
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
        val wrapper = element.objOrNull()?.obj("item") ?: return@mapNotNull null
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

/**
 * One page of a playlist's tracks.
 *
 * [total] is how long the playlist is, when Spotify said — and null when it
 * did not, which is a different thing from "the playlist ends here". The
 * difference is the whole bug: a page's own size is not a playlist's length,
 * and reading it as one stopped a long playlist after one short page.
 */
internal data class TrackPage(
    val items: List<SpotifyTrack>,
    val total: Int?,
    val raw: Int,
)

/** The tracks kept, the playlist's total when it is known, and how many entries the page held. */
internal fun parseTrackPage(root: JsonObject): TrackPage {
    val playlist = root.obj("data")?.obj("playlistV2")
        ?: throw IllegalStateException("Spotify playlist response was empty")
    val content = playlist.obj("content")
        ?: throw IllegalStateException("Spotify playlist response was empty")
    val rawItems = content.arr("items").orEmpty()
    val items = rawItems.mapNotNull { element ->
        val data = element.objOrNull()?.obj("itemV2")?.obj("data") ?: return@mapNotNull null
        val title = data.str("name").orEmpty()
        if (title.isBlank()) return@mapNotNull null
        val uri = data.str("uri") ?: data.str("_uri") ?: return@mapNotNull null
        // Only a *track* is a row. A playlist can hold an episode or a book,
        // and neither of those can be queued — see [SpotifyLongForm].
        if (!SpotifyLongForm.isMusicTrackUri(uri)) return@mapNotNull null
        val artist = data.obj("artists")?.arr("items").orEmpty().mapNotNull { artist ->
            artist.objOrNull()?.obj("profile")?.str("name")?.takeIf { it.isNotBlank() }
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
    val total = content.int("totalCount")
        ?: playlist.int("totalCount")
        ?: playlist.int("totalLength")
    return TrackPage(items = items, total = total, raw = rawItems.size)
}

/** The Liked Songs page: `me.library.tracks`, each entry wrapping the track under `track`. */
internal fun parseLikedPage(root: JsonObject): TrackPage {
    val tracks = root.obj("data")?.obj("me")?.obj("library")?.obj("tracks")
        ?: throw IllegalStateException("Spotify liked songs response was empty")
    val rawItems = tracks.arr("items").orEmpty()
    val items = rawItems.mapNotNull { element ->
        val wrapper = element.objOrNull()?.obj("track") ?: return@mapNotNull null
        val data = wrapper.obj("data") ?: return@mapNotNull null
        val title = data.str("name").orEmpty()
        if (title.isBlank()) return@mapNotNull null
        val uri = wrapper.str("_uri") ?: data.str("uri") ?: return@mapNotNull null
        // Only a *track* is a row; see [SpotifyLongForm].
        if (!SpotifyLongForm.isMusicTrackUri(uri)) return@mapNotNull null
        val album = data.obj("albumOfTrack")
        SpotifyTrack(
            id = uri.substringAfterLast(":"),
            title = title,
            artist = data.obj("artists")?.arr("items").orEmpty().mapNotNull { artist ->
                artist.objOrNull()?.obj("profile")?.str("name")?.takeIf { it.isNotBlank() }
            }.joinToString(", "),
            album = album?.str("name"),
            durationMs = (data.obj("trackDuration") ?: data.obj("duration"))?.int("totalMilliseconds") ?: 0,
            imageUrl = album?.obj("coverArt")?.arr("sources")?.lastUrl(),
        )
    }
    val library = root.obj("data")?.obj("me")?.obj("library")
    val total = tracks.int("totalCount") ?: library?.int("totalCount")
    return TrackPage(items = items, total = total, raw = rawItems.size)
}

/**
 * The widest source of an image's first entry. Spotify lists a playlist's
 * sizes in no fixed order — a 60px thumbnail can come last — so the list is
 * read by width rather than by position.
 */
private fun largestSource(images: JsonObject?): String? =
    images?.arr("items")?.firstOrNull()?.objOrNull()?.arr("sources")
        ?.mapNotNull { source ->
            val source = source.objOrNull() ?: return@mapNotNull null
            val url = source.str("url") ?: return@mapNotNull null
            url to (source.int("width") ?: 0)
        }
        ?.maxByOrNull { it.second }
        ?.first

private fun coverUrl(images: JsonObject?): String? = largestSource(images)

private fun JsonArray.lastUrl(): String? =
    mapNotNull { it.objOrNull()?.str("url") }.lastOrNull()

