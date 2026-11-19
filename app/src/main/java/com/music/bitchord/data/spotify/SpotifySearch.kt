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

        val failures = mutableListOf<String>()
        val pages = mutableListOf<Page>()
        val answeredBy = mutableListOf<String>()

        // The operations the web player itself sends for the search tabs, with
        // the variables it sends them and the paths it reads them from.
        //
        // The assisted-curation operation used to be asked first, and it
        // answers: it carries a search section, and the counts read eight
        // tracks out of it. But it is not the operation the results page uses,
        // and its rows are not the rows the results page draws — so the search
        // said "eight tracks" and drew none. The tab operations are the ones
        // whose answers are known: `searchTracks` answers
        // `searchV2.tracksV2.items[].item.data`, and the others answer their
        // own section's `items[].data`.
        for ((operation, tab) in tabOperations(filter)) {
            val answer = ask(operation, tabVariables(query, tab, offset)) { failures += it }
            val section = answer?.obj("searchV2") ?: answer ?: continue
            if (section.hasNothing()) {
                Log.d(TAG, "Spotify's $operation answered an empty search")
                continue
            }
            Log.d(TAG, "Spotify's search answered by $operation")
            answeredBy += operation
            pages += parse(section, tab, operation, counts(section))
        }

        // The older families, when the tab operations had nothing to say.
        // They are still asked: the backend ships both, and which one is
        // alive changes between builds.
        if (pages.none { it.rows.isNotEmpty() }) {
            for (attempt in attempts(query, limitOf(filter), offset)) {
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
                answeredBy += attempt.operation
                pages += parse(answered, filter, attempt.operation, counts(answered))
                if (pages.any { it.rows.isNotEmpty() }) break
            }
        }

        if (pages.isEmpty()) {
            // Not an empty catalogue: an unanswered question. Saying so is the
            // difference between a listener retyping the query and a listener
            // who knows Spotify refused every operation this build offers.
            val why = failures.distinct().take(3).joinToString("; ").ifBlank { "no operation answered" }
            Log.w(TAG, "Spotify answered no search section for \"${query.take(80)}\": $why")
            throw SpotifyGraphQLException("Spotify could not answer that search — $why")
        }
        return@withContext merge(pages, answeredBy)
    }

    /**
     * The pages of several operations as one page.
     *
     * The "All" tab is four questions, and the answer to each is a page of
     * its own. They are put together here rather than in the caller, so the
     * diagnostic still names every operation that answered and every row
     * that came out of it.
     */
    private fun merge(pages: List<Page>, answeredBy: List<String>): Page {
        if (pages.size == 1) return pages.first()
        val rows = pages.flatMap { it.rows }
        val seen = pages.joinToString("; ") { it.diagnostic }
        val diagnostic = buildString {
            append("answered by ").append(answeredBy.joinToString(", ").ifBlank { "nothing" })
            if (seen.isNotBlank()) append(" — ").append(seen)
            append("; ").append(rows.size).append(" rows")
        }
        Log.d(TAG, "Spotify's search: " + diagnostic)
        return Page(rows, pages.first().nextOffset, diagnostic)
    }

    /**
     * One operation, asked once, and the answer's `data` or null.
     *
     * The failure is handed back rather than thrown: one operation refusing
     * is not the search refusing, and the caller needs to know which of them
     * it was.
     */
    private suspend fun ask(
        operation: String,
        variables: JsonObject,
        onFailure: (String) -> Unit,
    ): JsonObject? = runCatching { SpotifyGraphQL.query(operation, variables) }
        .onFailure { error ->
            Log.w(TAG, "Spotify's search via $operation failed: ${error.message}")
            onFailure("$operation: ${error.message}")
        }
        .getOrNull()

    /**
     * The operation and the tab each kind of result is asked for.
     *
     * The web player asks one operation per tab, and the answer of each is
     * that tab's section and nothing else. The "All" tab is all four of them
     * asked together, which is what this returns for it.
     */
    private fun tabOperations(filter: SearchFilter): List<Pair<String, SearchFilter>> = when (filter) {
        SearchFilter.SONGS -> listOf("searchTracks" to SearchFilter.SONGS)
        SearchFilter.ALBUMS -> listOf("searchAlbums" to SearchFilter.ALBUMS)
        SearchFilter.ARTISTS -> listOf("searchArtists" to SearchFilter.ARTISTS)
        SearchFilter.PLAYLISTS -> listOf("searchPlaylists" to SearchFilter.PLAYLISTS)
        SearchFilter.ALL -> listOf(
            "searchTracks" to SearchFilter.SONGS,
            "searchAlbums" to SearchFilter.ALBUMS,
            "searchArtists" to SearchFilter.ARTISTS,
            "searchPlaylists" to SearchFilter.PLAYLISTS,
        )
        // Podcasts and videos have no rows of their own in this app's search.
        else -> emptyList()
    }

    /**
     * The variables a tab operation is sent, exactly as the web player sends
     * them.
     *
     * The same set the reference client sends: the term is `searchTerm` (not
     * `term`, which is the assisted-curation spelling), and the flags are the
     * ones the results page sends with them. A missing variable fails the
     * request outright, and an extra one is a variable the operation never
     * declared — so the set is copied rather than assembled.
     */
    private fun tabVariables(query: String, tab: SearchFilter, offset: Int): JsonObject {
        val limit = limitOf(tab)
        return buildJsonObject {
            put("searchTerm", query)
            put("offset", offset)
            put("limit", limit)
            put("numberOfTopResults", limit)
            // Audiobooks, authors and episode ratings are not asked for:
            // there is nothing here that can play a book or an episode. See
            // [SpotifyLongForm]. The variables are still *sent* — the
            // persisted query declares them, and a missing variable fails the
            // request outright — but their values say no.
            put("includeAudiobooks", false)
            put("includeAuthors", false)
            put("includePreReleases", false)
            put("includeAlbumPreReleases", false)
            put("includeEpisodeContentRatingsV2", false)
        }
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
                put("includeEpisodeContentRatingsV2", false)
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
                put("includeAudiobooks", false)
                put("includeArtistHasConcertsField", false)
                put("includePreReleases", true)
                put("includeAuthors", false)
            },
            hash = "fcad5a3e0d5af727fb76966f06971c19cfa2275e6ff7671196753e008611873c",
        ),
    )

    /** Whether every list in [this] section is empty. */
    private fun JsonObject.hasNothing(): Boolean {
        val lists = listOf("tracksV2", "albumsV2", "artists", "playlists", "topResultsV2")
        return lists.all { key -> itemsOf(key).isEmpty() }
    }

    /**
     * The entries of one section, from both of the keys it may arrive under.
     *
     * A section's list is `items` in the web player's own readers and
     * `itemsV2` in its counts — and the two are not the same list, nor is
     * either of them the same across sections. Reading one key and counting
     * another is how an answer that carried eight tracks produced zero rows:
     * the count saw eight, the parser saw none, and the search said Spotify
     * had nothing.
     *
     * So every list is read from both, and the entries are told apart by what
     * they are rather than by where they sit — see [entityIn].
     */
    private fun JsonObject.itemsOf(key: String): List<kotlinx.serialization.json.JsonElement> {
        val section = obj(key) ?: return emptyList()
        return section.arr("items")?.toList().orEmpty() + section.arr("itemsV2")?.toList().orEmpty()
    }

    /** How many of each kind an answer carried, as one line. */
    private fun counts(section: JsonObject): String {
        fun size(key: String) = section.itemsOf(key).size
        return "tracks=" + size("tracksV2") + " albums=" + size("albumsV2") +
            " artists=" + size("artists") + " playlists=" + size("playlists") +
            " top=" + size("topResultsV2")
    }

    /**
     * Reads a search section into this app's rows.
     *
     * Internal rather than private so that a test can hand it the answers
     * themselves: the shape of Spotify's answer is not a contract anybody
     * reads, and the only way to know this reads the shapes is to write them
     * down and read them.
     */
    internal fun parse(
        section: JsonObject,
        filter: SearchFilter,
        answeredBy: String = "a test",
        seen: String = "",
    ): Page {
        val trackItems = section.itemsOf("tracksV2")
        val albumItems = section.itemsOf("albumsV2")
        val artistItems = section.itemsOf("artists")
        val playlistItems = section.itemsOf("playlists")

        // The top results, which are where a newer section build puts
        // everything: each entry carries its own kind, so the four lists above
        // can be empty while the answer is not.
        val topItems = section.itemsOf("topResultsV2")

        val rows = mutableListOf<SearchResult>()
        var found = 0
        var matched = 0

        if (filter == SearchFilter.SONGS || filter == SearchFilter.ALL) {
            val tracks = entitiesOf(trackItems + topItems, EntityKind.TRACK)
            // Every track Spotify returned is a row. This used to keep only the
            // ones that matched something playable, which turned "the match
            // failed" into "the catalogue is empty" — a search for a real song
            // could come back with nothing at all while Spotify had answered
            // with ten of them. Playability is the player's problem, not the
            // search's: see [SpotifyHomeSource.songFor].
            val songs = tracks.mapNotNull(::parseTrack).map(SpotifyHomeSource::songFor)
            found = songs.size
            matched = songs.size
            songs.forEach { rows += SearchResult.Track(it) }
        }
        if (filter == SearchFilter.ALBUMS || filter == SearchFilter.ALL) {
            entitiesOf(albumItems + topItems, EntityKind.ALBUM)
                .mapNotNull(::parseAlbum)
                .forEach { rows += it.row() }
        }
        if (filter == SearchFilter.ARTISTS || filter == SearchFilter.ALL) {
            entitiesOf(artistItems + topItems, EntityKind.ARTIST)
                .mapNotNull(::parseArtist)
                .forEach { rows += it.row() }
        }
        if (filter == SearchFilter.PLAYLISTS || filter == SearchFilter.ALL) {
            entitiesOf(playlistItems + topItems, EntityKind.PLAYLIST)
                .mapNotNull(::parsePlaylist)
                .forEach { rows += it.row() }
        }
        val diagnostic = buildString {
            append("answered by ").append(answeredBy.ifBlank { "nothing" })
            append(" (").append(seen.ifBlank { "no counts" }).append(')')
            if (filter == SearchFilter.SONGS || filter == SearchFilter.ALL) {
                append("; ").append(found).append(" tracks, ").append(matched).append(" playable")
                if (found == 0) {
                    // The shape of what actually arrived, so the next answer
                    // can be read rather than guessed at. An empty page with
                    // no shape in it is the one thing that cannot be acted on.
                    val sample = (trackItems + topItems).firstOrNull()
                    append("; first entry ").append(sample?.let(::shapeOf) ?: "none")
                    append("; section ").append(sectionKeys(section))
                }
            }
            append("; ").append(rows.size).append(" rows")
        }
        Log.d(TAG, "Spotify's search: " + diagnostic)
        return Page(rows, nextOffsetOf(section), diagnostic)
    }

    /**
     * Where the next page starts, when the answer said.
     *
     * The tab sections carry `pagingInfo.nextOffset` beside their items, and
     * it is the only thing in the answer that says whether there is more
     * catalogue to come. A search that cannot page is a search that can only
     * ever show the first eight rows of a catalogue with eight thousand.
     */
    private fun nextOffsetOf(section: JsonObject): Int? {
        for (key in listOf("tracksV2", "albumsV2", "artists", "playlists", "topResultsV2")) {
            val offset = section.obj(key)?.obj("pagingInfo")?.int("nextOffset") ?: continue
            if (offset > 0) return offset
        }
        return null
    }

    /** The keys the answer's search section carries, for the log. */
    private fun sectionKeys(section: JsonObject): String =
        section.keys.joinToString(",").ifBlank { "none" }

    /**
     * How many rows one tab is asked for.
     *
     * The numbers the web player asks with: twenty for the songs tab and
     * thirty for the others. A request that asks for fewer than the reference
     * does is a request whose answer nobody has read.
     */
    private fun limitOf(filter: SearchFilter): Int = when (filter) {
        SearchFilter.SONGS -> 20
        else -> 30
    }

    // -- parsing ------------------------------------------------------------

    /**
     * The kinds of entity a search answer carries.
     *
     * Each names the `__typename` the web player's own readers dispatch on and
     * the prefix of the uri it reads the id from. Both are needed: a build
     * that states one of them and not the other is a build this has to read.
     */
    private enum class EntityKind(val typename: String, val uriPrefix: String) {
        TRACK("Track", "spotify:track:"),
        ALBUM("Album", "spotify:album:"),
        ARTIST("Artist", "spotify:artist:"),
        PLAYLIST("Playlist", "spotify:playlist:"),
    }

    /** How deep the search for an entity inside an entry may go. */
    private const val MAX_ENTITY_DEPTH = 8

    /**
     * The entities of one kind among [items], wherever each one put itself.
     *
     * Deduplicated by identity, because the same entity can arrive twice: in
     * its own section and among the top results.
     */
    private fun entitiesOf(
        items: List<kotlinx.serialization.json.JsonElement>,
        kind: EntityKind,
    ): List<JsonObject> {
        val found = LinkedHashMap<String, JsonObject>()
        for (item in items) {
            val entity = entityIn(item, kind) ?: continue
            val key = entityKey(entity, kind) ?: continue
            found.putIfAbsent(key, entity)
        }
        return found.values.toList()
    }

    /**
     * The entity of [kind] inside [element], wherever the answer put it.
     *
     * Every entity in an answer is wrapped, and the wrapping is not the same
     * everywhere: a section's entry holds its entity under `data`, a top
     * result holds it under `item` and then under `data` again, and a build
     * that moved a field moved the depth with it. So the entity is looked for
     * rather than addressed — the first object in the entry that *is* a track
     * (or an album, an artist, a playlist) is the one.
     *
     * The walk stops at an entity of another kind: the tracks inside an album
     * and the albums inside an artist are not what the track tab asked for,
     * and reading them would put rows in the list that Spotify never offered.
     */
    private fun entityIn(
        element: kotlinx.serialization.json.JsonElement,
        kind: EntityKind,
    ): JsonObject? {
        var answer: JsonObject? = null

        fun walk(node: kotlinx.serialization.json.JsonElement, depth: Int) {
            if (answer != null || depth > MAX_ENTITY_DEPTH) return
            val obj = node.objOrNull()
            if (obj == null) {
                // A list is a list of things to look inside, not a wall: the
                // answer's own shelves put their rows in one
                // (`SearchSectionEntity.items`), and a walk that stopped at
                // the list found the shelf and nothing under it. The rows of
                // a shelf are entities of their own kind, so the walk goes
                // through.
                val list = node.arrOrNull() ?: return
                for (element in list) {
                    walk(element, depth + 1)
                    if (answer != null) return
                }
                return
            }
            if (obj.isEntity(kind)) {
                answer = obj
                return
            }
            if (obj.isEntityOfAnotherKind(kind)) return
            for ((_, value) in obj) {
                walk(value, depth + 1)
                if (answer != null) return
            }
        }

        walk(element, 0)
        return answer
    }

    /** Whether this object is an entity of [kind]. */
    private fun JsonObject.isEntity(kind: EntityKind): Boolean {
        val uri = str("uri").orEmpty()
        if (uri.startsWith(kind.uriPrefix)) return true
        if (str("__typename").equals(kind.typename, ignoreCase = true)) return true
        if (kind != EntityKind.TRACK) return false
        // The catalogue's older shape: no uri and no typename, a bare id and a
        // name, and the album or the runtime beside them. The album or the
        // runtime is what tells a track from an album in a list that states
        // neither.
        val id = str("id").orEmpty()
        if (id.isBlank() || id.contains(':')) return false
        if (str("name").isNullOrBlank()) return false
        return obj("album") != null || obj("duration") != null ||
            obj("duration_ms") != null || obj("artists") != null
    }

    /** Whether this object is an entity of some kind other than [kind]. */
    private fun JsonObject.isEntityOfAnotherKind(kind: EntityKind): Boolean {
        val uri = str("uri").orEmpty()
        val typename = str("__typename").orEmpty()
        // A show, an episode or a book is an entity of a kind this app does
        // not play, and the walk must stop at it rather than descend into it:
        // an episode object carries a track-shaped block of its own, and that
        // block is not a row the songs tab ever offered. See
        // [SpotifyLongForm].
        if (SpotifyLongForm.isLongFormUri(uri)) return true
        if (typename.equals("Show", ignoreCase = true) ||
            typename.equals("Episode", ignoreCase = true) ||
            typename.equals("Audiobook", ignoreCase = true)
        ) return true
        return EntityKind.entries.any { other ->
            other != kind &&
                (uri.startsWith(other.uriPrefix) || typename.equals(other.typename, ignoreCase = true))
        }
    }

    /** What identifies an entity of [kind] — its id, or its uri. */
    private fun entityKey(entity: JsonObject, kind: EntityKind): String? = when (kind) {
        EntityKind.TRACK -> spotifyTrackIdOf(entity)
        else -> entity.str("uri")?.takeIf { it.startsWith(kind.uriPrefix) }
    }

    /**
     * A track's Spotify id, from its `uri` or from the `id` next to it.
     *
     * All three spellings are real: a `uri` of `spotify:track:<id>`, an `id`
     * that is the same uri, and a bare `id`. Requiring one of them and
     * dropping everything without is how a row that had an id all along was
     * discarded.
     */
    private fun spotifyTrackIdOf(data: JsonObject): String? {
        val uri = data.str("uri").orEmpty()
        if (uri.startsWith("spotify:track:")) return uri.substringAfterLast(':')
        val id = data.str("id").orEmpty()
        if (id.startsWith("spotify:track:")) return id.substringAfterLast(':')
        return id.takeIf { it.isNotBlank() && !id.contains(':') }
    }

    /**
     * A title, from wherever this shape states one.
     *
     * A plain `name` in the catalogue's own answers; a `text` or a
     * `transformedLabel` under it in the shapes the home feed uses. A row
     * with no title is not a row anyone can pick.
     */
    private fun titleOf(data: JsonObject): String =
        data.str("name").orEmpty().ifBlank {
            data.str("name.text").orEmpty().ifBlank {
                data.str("title.text").orEmpty().ifBlank {
                    data.str("title.transformedLabel").orEmpty()
                }
            }
        }

    /** One track, from its own object. */
    private fun parseTrack(data: JsonObject): SpotifyTrack? {
        val id = spotifyTrackIdOf(data) ?: return null
        val title = titleOf(data)
        if (title.isBlank()) return null
        val album = data.obj("albumOfTrack") ?: data.obj("album")
        return SpotifyTrack(
            id = id,
            title = title,
            artist = artistsOf(data),
            album = album?.str("name"),
            durationMs = data.obj("duration")?.int("totalMilliseconds")
                ?: data.obj("trackDuration")?.int("totalMilliseconds")
                ?: data.obj("duration")?.int("milliseconds")
                ?: data.int("duration_ms")
                ?: 0,
            imageUrl = album?.obj("coverArt")?.arr("sources")?.bestSourceUrl()
                ?: data.obj("coverArt")?.arr("sources")?.bestSourceUrl()
                ?: data.arr("visualIdentity.sixteenByNineCoverImage.image.data.sources")
                    ?.bestSourceUrl()
                ?: data.obj("images")?.arr("items")?.firstOrNull()?.objOrNull()
                    ?.arr("sources")?.bestSourceUrl(),
        )
    }

    private fun parseAlbum(data: JsonObject): SpotifyCard? {
        val uri = data.str("uri")?.takeIf { it.startsWith("spotify:album:") } ?: return null
        val title = titleOf(data)
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

    private fun parseArtist(data: JsonObject): SpotifyCard? {
        val uri = data.str("uri")?.takeIf { it.startsWith("spotify:artist:") } ?: return null
        val title = data.obj("profile")?.str("name").orEmpty().ifBlank { data.str("name").orEmpty() }
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

    private fun parsePlaylist(data: JsonObject): SpotifyCard? {
        val uri = data.str("uri")?.takeIf { it.startsWith("spotify:playlist:") } ?: return null
        val title = titleOf(data)
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

    /**
     * The shape of what arrived, for the log.
     *
     * The key names of an entry and of the first object inside it — enough to
     * say "the entity is under `item.data`" or "there is no `data` at all",
     * which is the difference between a parser that is looking in the wrong
     * place and one that is being handed something else.
     */
    private fun shapeOf(element: kotlinx.serialization.json.JsonElement): String {
        val obj = element.objOrNull() ?: return element.toString().take(120)
        val keys = obj.keys.joinToString(",")
        val nested = obj.values.firstNotNullOfOrNull { value ->
            value.objOrNull()?.keys?.joinToString(",")
        }
        return if (nested != null) "{$keys} -> {$nested}" else "{$keys}"
    }

    /** The artists of a track, album or playlist, as one line. */
    private fun artistsOf(data: JsonObject): String {
        // A plain byline, for the shapes that state one ("MISFIRE" rather than a
        // list of profiles). `str` answers null for an object, so this cannot
        // shadow the structured reading below.
        data.str("artists")?.takeIf { it.isNotBlank() }?.let { return it }
        data.str("artist")?.takeIf { it.isNotBlank() }?.let { return it }
        // A list of artists rather than a list of profiles: the older shape
        // puts the name on the artist itself.
        data.arr("artists")?.mapNotNull { artist ->
            artist.objOrNull()?.str("name")?.takeIf { it.isNotBlank() }
        }?.takeIf { it.isNotEmpty() }?.let { return it.joinToString(", ") }
        val names = data.obj("artists")?.arr("items").orEmpty().mapNotNull { artist ->
            val obj = artist.objOrNull() ?: return@mapNotNull null
            // The catalogue states an artist as a profile with a name inside
            // it; the older shape states the name on the artist itself.
            obj.obj("profile")?.str("name")?.takeIf { it.isNotBlank() }
                ?: obj.str("name")?.takeIf { it.isNotBlank() }
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
