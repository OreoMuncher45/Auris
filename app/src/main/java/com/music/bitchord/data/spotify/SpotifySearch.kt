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
        /**
         * What the answer contained, in numbers.
         *
         * An empty page has three very different causes — the catalogue had
         * nothing, the answer arrived in a shape this build does not read, or
         * Spotify's tracks could not be matched to anything playable — and from
         * the outside all three are a blank list. This is what tells them
         * apart, on screen and in the log.
         */
        val diagnostic: String = "",
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
        if (query.isBlank()) return@withContext Page(emptyList(), null, "the query was blank")
        val limit = limitOf(filter)

        var section: JsonObject? = null
        var answeredBy = ""
        var seen = ""
        val failures = mutableListOf<String>()
        for (attempt in attempts(query, limit, offset)) {
            val root = runCatching {
                if (attempt.hash != null) {
                    SpotifyGraphQL.queryWithHash(attempt.operation, attempt.variables, attempt.hash)
                } else {
                    SpotifyGraphQL.query(attempt.operation, attempt.variables)
                }
            }
                .onFailure { error ->
                    Log.w(TAG, "Spotify's search via ${attempt.operation} failed: ${error.message}")
                    failures += "${attempt.operation}: ${error.message}"
                }
                .getOrNull()
            val answered = root?.obj("searchV2")
            if (answered == null) {
                if (root != null) {
                    Log.w(TAG, "Spotify's ${attempt.operation} answered without a search section")
                    failures += "${attempt.operation}: no search section"
                }
                continue
            }
            if (answered.hasNothing()) {
                Log.d(TAG, "Spotify's ${attempt.operation} answered an empty search")
                continue
            }
            Log.d(TAG, "Spotify's search answered by ${attempt.operation}")
            answeredBy = attempt.operation
            seen = counts(answered)
            section = answered
            break
        }

        // The per-tab family, when nothing above produced a section. Each of
        // those operations serves exactly one kind, so the ones the filter
        // wants are asked in turn.
        if (section == null) {
            val perTab = perTabSections(query, filter, limit, offset, failures)
            if (perTab != null && !perTab.hasNothing()) {
                Log.d(TAG, "Spotify's search answered by the per-tab operations")
                answeredBy = "the per-tab operations"
                seen = counts(perTab)
                section = perTab
            }
        }

        if (section == null) {
            // Not an empty catalogue: an unanswered question. Saying so is the
            // difference between a listener retyping the query and a listener
            // who knows Spotify refused every operation this build offers.
            val why = failures.distinct().take(3).joinToString("; ").ifBlank { "no operation answered" }
            Log.w(TAG, "Spotify answered no search section for \"${query.take(80)}\": $why")
            throw SpotifyGraphQLException("Spotify could not answer that search — $why")
        }
        return@withContext parse(section, filter, limit, answeredBy, seen)
    }

    /** One way of asking Spotify for [query]. */
    private data class Attempt(
        val operation: String,
        val variables: JsonObject,
        /** An explicit persisted-query hash, when this attempt does not use the table. */
        val hash: String? = null,
    )

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
                // Both spellings of the term are sent. The operation's own
                // variable is `term` on this build, but a renamed variable is
                // exactly the thing that moves between builds, an unused
                // variable is ignored, and a missing one fails the request.
                put("term", query)
                put("searchTerm", query)
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
                put("term", query)
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
        // The same operation under the hash the working desktop client uses,
        // with exactly the variables it sends. Spotify keeps more than one
        // persisted hash alive, and a hash that answers is worth trying rather
        // than arguing about which build it came from.
        Attempt(
            "searchDesktop",
            buildJsonObject {
                put("searchTerm", query)
                put("offset", offset)
                put("limit", limit)
                put("numberOfTopResults", 5)
                put("includeAudiobooks", true)
                put("includeArtistHasConcertsField", false)
                put("includePreReleases", true)
                put("includeAuthors", false)
            },
            hash = "fcad5a3e0d5af727fb76966f06971c19cfa2275e6ff7671196753e008611873c",
        ),
    )

    /** The tab-per-operation family, for a build where the two above are gone. */
    private suspend fun perTabSections(
        query: String,
        filter: SearchFilter,
        limit: Int,
        offset: Int,
        failures: MutableList<String>? = null,
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
                put("term", query)
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
                    failures?.add("$operation: ${error.message}")
                }
                .getOrNull()
            val section = root?.obj("searchV2")
            if (section == null) {
                if (root != null) failures?.add("$operation: no search section")
                continue
            }
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

    /** How many of each kind an answer carried, as one line. */
    private fun counts(section: JsonObject): String {
        fun size(key: String) = section.obj(key)?.arr("items")?.size
            ?: section.obj(key)?.arr("itemsV2")?.size ?: 0
        return "tracks=" + size("tracksV2") + " albums=" + size("albumsV2") +
            " artists=" + size("artists") + " playlists=" + size("playlists") +
            " top=" + (section.obj("topResultsV2")?.arr("itemsV2")?.size ?: 0)
    }

    /** Reads a search section into this app's rows. */
    private suspend fun parse(
        section: JsonObject,
        filter: SearchFilter,
        limit: Int,
        answeredBy: String,
        seen: String,
    ): Page {
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
        var found = 0
        var matched = 0

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
            found = trackData.size
            matched = songs.size
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
        val diagnostic = buildString {
            append("answered by ").append(answeredBy.ifBlank { "nothing" })
            append(" (").append(seen.ifBlank { "no counts" }).append(')')
            if (filter == SearchFilter.SONGS || filter == SearchFilter.ALL) {
                append("; ").append(found).append(" tracks, ").append(matched).append(" playable")
            }
            append("; ").append(rows.size).append(" rows")
        }
        Log.d(TAG, "Spotify's search: " + diagnostic)
        return Page(rows, null, diagnostic)
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
