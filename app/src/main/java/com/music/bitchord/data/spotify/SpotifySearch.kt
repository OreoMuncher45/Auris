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
     * ## More than one way to ask
     *
     * The tabs the filter asks for are answered by one operation on some
     * builds and by a family of them on others, and a persisted query that is
     * sent the wrong variables fails as a *whole request* — a 200 with an
     * `errors` array, no `searchV2`, and a page that looks like a catalogue
     * with nothing in it. Two things fix that, and both are here rather than
     * in the parser: the variables each attempt sends are the complete set the
     * web player itself sends for that operation (read off its own bundles,
     * not guessed from the ones that looked necessary), and every attempt is
     * tried in turn until one of them answers with a search section.
     *
     * The first attempt is the one the player's own sections use; the second
     * is its search *page*; the third is the per-tab family that predates both
     * and is still shipped. Which one answered is logged, because the next
     * build will move them again and the log is how that is noticed.
     */
    suspend fun searchPage(
        query: String,
        filter: SearchFilter,
        offset: Int = 0,
    ): Page = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext Page(emptyList(), null)
        val limit = limitOf(filter)

        var section: JsonObject? = null
        for (attempt in attempts(query, limit, offset)) {
            val root = runCatching { SpotifyGraphQL.query(attempt.operation, attempt.variables) }
                .onFailure { error ->
                    Log.w(TAG, "Spotify's search via ${attempt.operation} failed: ${error.message}")
                }
                .getOrNull()
            val answered = root?.obj("searchV2")
            if (answered == null) {
                if (root != null) {
                    Log.w(TAG, "Spotify's ${attempt.operation} answered without a search section")
                }
                continue
            }
            if (answered.hasNothing()) {
                Log.d(TAG, "Spotify's ${attempt.operation} answered an empty search")
                continue
            }
            Log.d(TAG, "Spotify's search answered by ${attempt.operation}")
            section = answered
            break
        }

        // The per-tab family, when nothing above produced a section. Each of
        // those operations serves exactly one kind, so the ones the filter
        // wants are asked in turn.
        if (section == null) {
            val perTab = perTabSections(query, filter, limit, offset)
            if (perTab != null && !perTab.hasNothing()) {
                Log.d(TAG, "Spotify's search answered by the per-tab operations")
                section = perTab
            }
        }

        if (section == null) {
            Log.w(TAG, "Spotify answered no search section for \"${query.take(80)}\"")
            return@withContext Page(emptyList(), null)
        }
        return@withContext parse(section, filter, limit)
    }

    /** One way of asking Spotify for [query]. */
    private data class Attempt(val operation: String, val variables: JsonObject)

    /**
     * The full variable set for each operation, as the web player sends it.
     *
     * Every key here was read off the player's own call sites. A missing one is
     * not a smaller request — the persisted query declares its variables and a
     * request that does not supply them is refused with an error rather than
     * answered with defaults, which is what made search come back empty.
     */
    private fun attempts(query: String, limit: Int, offset: Int): List<Attempt> = listOf(
        Attempt(
            "assistedCurationSearch",
            buildJsonObject {
                put("term", query)
                put("offset", offset)
                put("limit", limit)
                put("numberOfTopResults", limit)
                put("includeEpisodeContentRatingsV2", true)
            },
        ),
        Attempt(
            "searchDesktop",
            buildJsonObject {
                put("searchTerm", query)
                put("offset", offset)
                put("limit", limit)
                put("numberOfTopResults", limit)
                put("includeAudiobooks", false)
                put("includeArtistHasConcertsField", false)
                put("includePreReleases", false)
                put("includeAlbumPreReleases", false)
                put("includeAuthors", false)
                put("includeEpisodeContentRatingsV2", false)
            },
        ),
    )

    /** The tab-per-operation family, for a build where the two above are gone. */
    private suspend fun perTabSections(
        query: String,
        filter: SearchFilter,
        limit: Int,
        offset: Int,
    ): JsonObject? {
        val wanted = when (filter) {
            SearchFilter.SONGS -> listOf("searchTracks" to "tracksV2")
            SearchFilter.ALBUMS -> listOf("searchAlbums" to "albumsV2")
            SearchFilter.ARTISTS -> listOf("searchArtists" to "artists")
            SearchFilter.PLAYLISTS -> listOf("searchPlaylists" to "playlists")
            SearchFilter.ALL -> listOf(
                "searchTracks" to "tracksV2",
                "searchAlbums" to "albumsV2",
                "searchArtists" to "artists",
                "searchPlaylists" to "playlists",
            )
            // Podcasts and videos have no rows of their own in this app's
            // search; the operations above are the only ones worth asking.
            else -> emptyList()
        }
        val sections = HashMap<String, kotlinx.serialization.json.JsonElement>()
        var answeredAny = false
        for ((operation, key) in wanted) {
            val variables = buildJsonObject {
                put("searchTerm", query)
                put("offset", offset)
                put("limit", limit)
                put("numberOfTopResults", limit)
                put("includeAudiobooks", false)
                put("includePreReleases", false)
                put("includeAlbumPreReleases", false)
                put("includeAuthors", false)
                put("includeEpisodeContentRatingsV2", false)
            }
            val root = runCatching { SpotifyGraphQL.query(operation, variables) }
                .onFailure { error ->
                    Log.w(TAG, "Spotify's $operation failed: ${error.message}")
                }
                .getOrNull()
            val section = root?.obj("searchV2") ?: continue
            answeredAny = true
            section[key]?.let { sections[key] = it }
        }
        return if (answeredAny) JsonObject(sections) else null
    }

    /** Whether every list in [this] section is empty. */
    private fun JsonObject.hasNothing(): Boolean {
        val lists = listOf("tracksV2", "albumsV2", "artists", "playlists", "topResultsV2")
        return lists.all { key ->
            obj(key)?.arr("items").isNullOrEmpty() && obj(key)?.arr("itemsV2").isNullOrEmpty()
        }
    }

    /** Reads a search section into this app's rows. */
    private suspend fun parse(section: JsonObject, filter: SearchFilter, limit: Int): Page {
        val trackItems = section.obj("tracksV2")?.arr("items").orEmpty()
        val albumItems = section.obj("albumsV2")?.arr("items").orEmpty()
        val artistItems = section.obj("artists")?.arr("items").orEmpty()
        val playlistItems = section.obj("playlists")?.arr("items").orEmpty()

        // The top results, which are where a newer section build puts
        // everything: each entry carries its own kind, so the four lists above
        // can be empty while the answer is not.
        val top = section.obj("topResultsV2")?.arr("itemsV2").orEmpty()
            .mapNotNull { item -> item.objOrNull()?.obj("item")?.obj("data") }

        /** A `data` object, whether it arrived in its own list or among the top results. */
        fun dataOf(element: kotlinx.serialization.json.JsonElement): JsonObject? =
            element.objOrNull()?.let { it.obj("data") ?: it.obj("item")?.obj("data") }

        val rows = mutableListOf<SearchResult>()

        if (filter == SearchFilter.SONGS || filter == SearchFilter.ALL) {
            val trackData = (trackItems.mapNotNull(::dataOf) + top.filter { it.isTrack() })
            val songs = SpotifyHomeSource.matchTracks(parseTracks(trackData))
            // Said out loud, because this is the one step between a Spotify
            // answer and an empty page: the catalogue can return twelve rows
            // and the listener still see nothing, because none of them matched
            // anything playable.
            if (trackData.isNotEmpty() && songs.isEmpty()) {
                Log.w(TAG, "Spotify's search found ${trackData.size} tracks, none of which matched anything playable")
            }
            songs.forEach { rows += SearchResult.Track(it) }
        }
        if (filter == SearchFilter.ALBUMS || filter == SearchFilter.ALL) {
            (albumItems.mapNotNull(::dataOf) + top.filter { it.isKind("spotify:album:") })
                .mapNotNull(::parseAlbum)
                .forEach { rows += it.row() }
        }
        if (filter == SearchFilter.ARTISTS || filter == SearchFilter.ALL) {
            (artistItems.mapNotNull(::dataOf) + top.filter { it.isKind("spotify:artist:") })
                .mapNotNull(::parseArtist)
                .forEach { rows += it.row() }
        }
        if (filter == SearchFilter.PLAYLISTS || filter == SearchFilter.ALL) {
            (playlistItems.mapNotNull(::dataOf) + top.filter { it.isKind("spotify:playlist:") })
                .mapNotNull(::parsePlaylist)
                .forEach { rows += it.row() }
        }
        return Page(rows, null)
    }

    /** How many rows one tab is asked for. */
    private fun limitOf(filter: SearchFilter): Int = when (filter) {
        SearchFilter.SONGS -> 12
        else -> 8
    }

    // -- parsing ------------------------------------------------------------

    /** Whether this `data` object is the kind [uriPrefix] names. */
    private fun JsonObject.isKind(uriPrefix: String): Boolean =
        str("uri").orEmpty().startsWith(uriPrefix)

    private fun JsonObject.isTrack(): Boolean = isKind("spotify:track:")

    private fun parseTracks(items: List<JsonObject>): List<SpotifyTrack> =
        items.mapNotNull { data ->
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
