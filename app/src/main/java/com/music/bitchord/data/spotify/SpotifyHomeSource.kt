package com.music.bitchord.data.spotify

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.canvas.SpotifyToken
import com.music.bitchord.data.model.HomeChip
import com.music.bitchord.data.model.HomeHeader
import com.music.bitchord.data.model.HomeShelf
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The Spotify side of the homepage: Spotify's own curation, as the web player
 * draws it.
 *
 * ## The shape
 *
 * The page is the web player's home feed: a greeting, a row of chips, a grid
 * of quick-access tiles, and then a list of titled shelves of cards. Every
 * card is a collection — a playlist, an album, an artist — and not a track,
 * because that is what Spotify's own home holds and because it is the only
 * shape this app can draw without searching: a shelf of tracks has to be
 * matched to something playable before it can be shown, and matching a track
 * means a YouTube search. Twelve tracks a shelf, ten shelves, is a hundred
 * and twenty searches before the page is finished. A shelf of cards costs
 * none; the search happens when the listener opens one.
 *
 * ## Signed in, and not
 *
 * Signed in, the feed is the listener's own: their shortcuts, their mixes,
 * the things they jumped back into. Signed out there is nobody's taste to
 * read, so the page falls back through what a visitor can reach — the
 * catalogue's own browse grid and its new releases — and says plainly which
 * of those it got.
 *
 * The backend is the web player's own (`api-partner`), not the public `/v1`
 * catalogue. The public catalogue rate limits *per client*, and the web
 * player's client is shared by everyone who harvests a token from it: measured
 * on a signed-in session, recently-played, top-tracks and featured-playlists
 * all came back `429 API rate limit exceeded`, at once, in the same way.
 *
 * ## What this is not
 *
 * It is not a byte-for-byte copy of the web player's page. It is the same
 * feed, read through this app's own GraphQL client and drawn with this app's
 * own cards — and when the feed cannot be read, the page says why instead of
 * showing nothing.
 */
internal object SpotifyHomeSource {

    private const val TAG = "SpotifyHome"

    /**
     * How many tracks of a row are matched and shown.
     *
     * A cap rather than the whole collection: matching one track means a
     * YouTube search, and a row of twenty is twenty searches for cards
     * nobody scrolls to. Twelve fills a row at any phone width.
     */
    private const val TRACKS_PER_ROW = 12

    /**
     * How many tiles the quick-access grid holds.
     *
     * Eight is two rows of four on a phone, which is what Spotify's own grid
     * holds and what fits above the fold without the shelves being pushed
     * out of sight.
     */
    private const val SHORTCUTS = 8

    /** How many of the listener's own collections become rows. */
    private const val LIBRARY_ROWS = 4

    /** How many matches may be in flight at once; each one is a search. */
    private const val MATCH_CONCURRENCY = 4

    /** How many of the listener's top tracks and recent plays seed a Flow mix. */
    private const val FLOW_SEEDS = 24

    /**
     * Spotify track id to the track this app will play for it.
     *
     * In memory for now, and deliberately: a match is a *search result*, and a
     * stale one is a wrong song played confidently. A row that has been seen
     * once in a session loads instantly; a fresh process asks again.
     */
    private val matches = ConcurrentHashMap<String, Song>()

    /**
     * The other way round: this app's track id to the Spotify track it came
     * from.
     *
     * Needed because a station is asked for by a Spotify URI, and the queue
     * holds this app's own ids — the track that is playing was matched from
     * Spotify, and without the other direction the station it seeds could
     * only be guessed at.
     */
    private val spotifyIds = ConcurrentHashMap<String, String>()

    /** Whether a listener's session can be read. */
    fun isSignedIn(): Boolean = AppSettings.spotifySpdcToken.value.isNotBlank()

    /**
     * The page, plus — when there is none — why not.
     *
     * A caller cannot act on "nothing", and neither can the person testing
     * it: an empty Spotify home has several quite different causes (no token
     * minted from the web player, the backend refusing, the feed read and
     * then found to hold no cards), and they need opposite fixes. So the
     * reason travels with the result and is shown.
     */
    data class HomeOutcome(
        val shelves: List<HomeShelf> = emptyList(),
        val header: HomeHeader? = null,
        val reason: String? = null,
    ) {
        /** Whether the page has anything to draw. */
        val isEmpty: Boolean
            get() = shelves.isEmpty() && (header?.shortcuts.isNullOrEmpty())
    }

    /**
     * The page, as it should be drawn.
     *
     * @param facet the chip in force; "" for the whole page.
     */
    suspend fun page(facet: String = ""): HomeOutcome = withContext(Dispatchers.IO) {
        SpotifyWebApi.clearProblems()
        // The diagnostic belongs to this read: a failure two minutes ago says
        // nothing about this one.
        diagnostic = null
        // A token first, because everything downstream answers null without one
        // and "Spotify returned nothing" would then be the wrong story entirely.
        // Cached, so this costs the rest of the calls nothing.
        val signedIn = isSignedIn()
        val token = if (signedIn) SpotifyToken.accessToken() else SpotifyToken.anonymousToken()
        if (token == null) {
            return@withContext HomeOutcome(
                reason = if (signedIn) {
                    "Couldn't get a Spotify token for your account — the session may have expired."
                } else {
                    "Couldn't get a public Spotify token. Try again in a moment."
                },
            )
        }

        // Spotify's own feed first. It is the page the web player draws, and
        // it is the only one of these that is personalised.
        val feed = runCatching { homeFeed(facet) }.getOrElse { error ->
            note("Spotify's home feed could not be read: ${error.message}")
            null
        }
        if (feed != null && feed.sections.isNotEmpty()) {
            return@withContext HomeOutcome(
                shelves = feed.shelves.mapNotNull { shelf -> cardShelf(shelf) },
                header = HomeHeader(
                    greeting = feed.greeting.takeIf { it.isNotBlank() },
                    shortcuts = feed.shortcuts.take(SHORTCUTS).mapNotNull { card -> card.item() },
                    chips = feed.chips.map { it.item() },
                    chip = facet,
                ),
            )
        }

        // No feed. A visitor can still reach the public catalogue, and that
        // is a page with something on it rather than a page asking for a
        // sign-in it does not need.
        val fallback = runCatching { publicShelves() }.getOrElse { error ->
            note("Spotify's public catalogue could not be read: ${error.message}")
            emptyList()
        }
        if (fallback.isNotEmpty()) {
            return@withContext HomeOutcome(
                shelves = fallback,
                // No greeting: there is no feed to greet with. The page's own
                // heading says what the page is, which is more use than a
                // heading that repeats the service's name.
                header = HomeHeader(),
            )
        }

        // Signed in, the listener's own library is still there to be read —
        // it is the same backend, and it does not rate limit the way the
        // public catalogue does.
        if (signedIn) {
            val library = libraryRows()
            if (library.isNotEmpty()) return@withContext HomeOutcome(shelves = library)
        }

        val limited = SpotifyGraphQL.rateLimitSecondsRemaining()
        val problems = SpotifyWebApi.problems()
        HomeOutcome(
            reason = buildString {
                if (limited > 0) {
                    append("Spotify is rate limiting this device. ")
                    append("It lifts in about ${limited} second")
                    append(if (limited == 1) "." else "s.")
                    if (!signedIn) {
                        append(" Signing in lets Auris read your library, which Spotify does not limit.")
                    }
                    append("\n\n")
                }
                append(diagnostic ?: "Spotify returned no rows")
                if (problems.isNotEmpty()) {
                    append("\n\n")
                    append(problems.joinToString("\n"))
                }
            },
        )
    }

    /**
     * Spotify's home feed, as the web player reads it.
     *
     * The variables are the web player's own: the time zone the page greets
     * the listener in, the browser id it was given, the chip in force, and
     * how many cards a shelf may hold.
     */
    private suspend fun homeFeed(facet: String): SpotifyHomeFeed {
        val variables = buildJsonObject {
            put("timeZone", java.util.TimeZone.getDefault().id)
            put("sp_t", SpotifyToken.spToken().orEmpty())
            put("facet", facet)
            put("sectionItemsLimit", 10)
            put("homeEndUserIntegration", "INTEGRATION_WEB_PLAYER")
            put("includeEpisodeContentRatingsV2", false)
            put("includeHomeChapterVideoCards", false)
        }
        return SpotifyHomeFeedParser.parse(SpotifyGraphQL.query("home", variables))
    }

    /**
     * The public catalogue, for a listener who has not signed in.
     *
     * The browse grid first — the same genre and mood cards the web player
     * shows on its search page — and then what is new. Both are read through
     * the same backend as everything else, so neither of them is the
     * rate-limited public API that gave the old home a 429 on every call.
     */
    private suspend fun publicShelves(): List<HomeShelf> = coroutineScope {
        val browse = async { browseCategories() }
        val fresh = async { newReleases() }
        buildList {
            browse.await()?.let(::add)
            fresh.await()?.let(::add)
        }
    }

    /** The catalogue's own grid of genres and moods. */
    private suspend fun browseCategories(): HomeShelf? {
        val variables = buildJsonObject {
            putJsonObject("pagePagination") {
                put("offset", 0)
                put("limit", 10)
            }
            putJsonObject("sectionPagination") {
                put("offset", 0)
                put("limit", 99)
            }
            put("browseEndUserIntegration", "INTEGRATION_WEB_PLAYER")
        }
        val root = runCatching { SpotifyGraphQL.query("browseAll", variables) }.getOrElse { error ->
            Log.w(TAG, "the browse grid could not be read: ${error.message}")
            return null
        }
        val cards = SpotifyBrowseCards.parse(root)
        if (cards.isEmpty()) return null
        return HomeShelf(title = "Browse", items = cards.mapNotNull { it.item() })
    }

    /** What is new in the catalogue. */
    private suspend fun newReleases(): HomeShelf? {
        val variables = buildJsonObject {
            put("offset", 0)
            put("limit", 20)
            put("onlyUnPlayedItems", false)
        }
        val root = runCatching { SpotifyGraphQL.query("queryWhatsNewFeed", variables) }.getOrElse { error ->
            Log.w(TAG, "the new-releases feed could not be read: ${error.message}")
            return null
        }
        val cards = SpotifyBrowseCards.parseWhatsNew(root)
        if (cards.isEmpty()) return null
        return HomeShelf(title = "New releases", items = cards.mapNotNull { it.item() })
    }

    /**
     * One shelf of cards, or null when it has none.
     *
     * A shelf with nothing in it is not drawn: it would be a heading over a
     * blank space.
     */
    private fun cardShelf(shelf: SpotifyHomeSection): HomeShelf? {
        val items = shelf.cards.mapNotNull { card -> card.item() }
        if (items.isEmpty()) return null
        return HomeShelf(title = shelf.title, subtitle = shelf.subtitle, items = items)
    }

    /**
     * What went wrong, for the empty state.
     *
     * Written by every step that can fail, so the last thing to fail is what
     * the listener sees.
     */
    @Volatile
    private var diagnostic: String? = null

    private fun note(reason: String) {
        diagnostic = reason
        Log.w(TAG, reason)
    }

    /**
     * The listener's Spotify library, opened into rows.
     *
     * Liked Songs first — it is the one collection every listener has and the
     * one that is unambiguously "their" taste — then their playlists in the
     * order Spotify lists them. The number of rows is capped because each row
     * costs a page of tracks and then a YouTube search per track.
     */
    private suspend fun libraryRows(): List<HomeShelf> = coroutineScope {
        val playlists = runCatching { SpotifyLibrary.playlists(maxPages = 1) }.getOrElse { error ->
            note("Couldn't read your Spotify library: ${error.message}")
            return@coroutineScope emptyList()
        }
        if (playlists.isEmpty()) {
            note("Your Spotify library has no playlists in it")
            return@coroutineScope emptyList()
        }
        playlists.take(LIBRARY_ROWS).map { playlist ->
            async {
                val tracks = runCatching { SpotifyLibrary.preview(playlist.id, TRACKS_PER_ROW) }
                    .onFailure { note("Couldn't read '${playlist.name}': ${it.message}") }
                    .getOrDefault(emptyList())
                row(playlist.name, tracks)
            }
        }.awaitAll().filterNotNull()
    }

    /**
     * Flow's seeds, read from whichever backend will answer.
     *
     * Signed in, the listener's own taste. The web player's own library is
     * asked first: Liked Songs is the closest thing to "top tracks" the
     * library has, and it is the one collection that is unambiguously the
     * listener's own. The `/v1` history calls come after it because they are
     * *better* evidence - months of listening rather than one shelf - but a
     * rate-limited client refuses them, and a refused call is one source of
     * seeds not answering rather than a reason to build nothing.
     */
    private suspend fun ownedSeeds(): List<SpotifyTrack> {
        val liked = runCatching { SpotifyLibrary.preview(SpotifyLibrary.LIKED_ID, FLOW_SEEDS) }
            .onFailure { Log.w(TAG, "the Spotify library could not be read for Flow: ${it.message}") }
            .getOrDefault(emptyList())
        if (liked.isNotEmpty()) return liked
        val top = runCatching { SpotifyWebApi.topTracks(limit = FLOW_SEEDS) }.getOrNull().orEmpty()
        if (top.isNotEmpty()) return top
        val recent = runCatching { SpotifyWebApi.recentlyPlayed(limit = FLOW_SEEDS) }.getOrNull().orEmpty()
        if (recent.isNotEmpty()) return recent
        return emptyList()
    }

    /**
     * Seeds for a listener who has not signed in.
     *
     * There is no history to read, so the public catalogue stands in: the
     * home feed's own playlists - the editorial ones the web player shows a
     * visitor - and their tracks. The public API's featured list is the
     * fallback, and it is rate limited per client, so it is asked last.
     */
    private suspend fun publicSeeds(): List<SpotifyTrack> {
        val feed = runCatching { homeFeed("") }.getOrNull()
        val playlistIds = feed?.sections.orEmpty()
            .flatMap { section -> section.cards }
            .filter { card -> card.kind == SpotifyCard.Kind.PLAYLIST }
            .map { card -> card.id }
            .distinct()
            .take(2)
        val tracks = playlistIds.flatMap { id ->
            runCatching { SpotifyLibrary.preview(id, TRACKS_PER_ROW) }
                .onFailure { Log.w(TAG, "the public playlist '$id' could not be read for Flow: ${it.message}") }
                .getOrDefault(emptyList())
        }
        if (tracks.isNotEmpty()) return tracks
        val featured = runCatching { SpotifyWebApi.featuredPlaylists(limit = 2) }
            .onFailure { Log.w(TAG, "featured playlists could not be read for Flow: ${it.message}") }
            .getOrDefault(emptyList())
        return featured.flatMap { playlist ->
            runCatching { SpotifyWebApi.playlistTracks(playlist.id, limit = TRACKS_PER_ROW) }
                .getOrDefault(emptyList())
        }
    }

    /**
     * One row of tracks, matched, or null when nothing in it could be matched.
     *
     * Null means the row is dropped rather than drawn as a heading over an
     * empty space. A match failing is ordinary — a remix, a regional edit, a
     * track YouTube does not carry.
     */
    private suspend fun row(title: String, tracks: List<SpotifyTrack>?): HomeShelf? {
        if (tracks.isNullOrEmpty()) return null
        val wanted = tracks.take(TRACKS_PER_ROW)
        val items = match(wanted)
        if (items.isEmpty()) {
            note(
                "Read ${wanted.size} Spotify tracks ('${wanted.first().title}' by " +
                    "${wanted.first().artist}) and none of them matched on YouTube Music",
            )
            return null
        }
        return HomeShelf(title = title, items = items)
    }

    /**
     * The provider's contribution to a Flow mix.
     *
     * Signed in, that is the listener's own taste: their top tracks and what
     * they played lately. Signed out there is no history to read, so Spotify's
     * public featured list stands in.
     */
    suspend fun flowSeeds(): FlowSeeds = withContext(Dispatchers.IO) {
        if (isSignedIn()) {
            val owned = ownedSeeds()
            FlowSeeds(top = songs(owned), recent = emptyList())
        } else {
            FlowSeeds(top = emptyList(), recent = songs(publicSeeds()))
        }
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
                // read down with it — the exception leaves awaitAll, the home
                // read is caught as "nothing to show", and a search that failed
                // looks exactly like a catalogue that refused.
                val matched = runCatching { gate.withPermit { SpotifyImporter.matchTrack(track) } }
                    .onFailure { note("Searching YouTube Music for '${track.title}' failed: ${it.message}") }
                    .getOrNull()
                matched?.let {
                    matches[track.id] = it
                    rememberSpotifyId(it.videoId, track.id)
                    track to it
                }
            }
        }.awaitAll().filterNotNull()
    }

    /** The playable songs behind [tracks], for a seed pool. */
    private suspend fun songs(tracks: List<SpotifyTrack>): List<Song> =
        matched(tracks).map { (_, song) -> song }

    /**
     * The playable songs behind [tracks], for a search.
     *
     * A Spotify track is metadata: the audio comes from YouTube Music and the
     * addons, so a Spotify row is worth showing only once it has been matched
     * to something that can play. The match is remembered both ways, so a
     * station can later be asked for by the Spotify id behind the track that
     * is playing.
     */
    internal suspend fun matchTracks(tracks: List<SpotifyTrack>): List<Song> =
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
        rememberSpotifyId(key, track.id)
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

    /**
     * The next tracks AutoPlay should queue, from Spotify's own algorithm.
     *
     * The seed is the song that is playing, and what comes next is Spotify's
     * answer to it: the station Spotify would start for that song, or the
     * artists Spotify puts beside that song's artist. Then the catalogue's own
     * lists. Not the listener's liked list — that is what this used to serve,
     * and a queue that repeats the songs you already loved is not AutoPlay, it
     * is a shuffle of your library.
     */
    suspend fun autoplayTracks(
        exclude: Set<String>,
        limit: Int,
        seed: Song? = null,
        station: Boolean = false,
    ): List<Song> = withContext(Dispatchers.IO) {
        if (limit <= 0) return@withContext emptyList()

        if (seed != null) {
            // An explicit station is Spotify's own mix of the track and
            // nothing else; a queue top-up takes the same answer, because
            // Spotify's station is the best thing this provider can say. The
            // artist graph behind it is still Spotify's algorithm, so an
            // unknown seed falls through to it rather than to the library.
            val algorithmic = SpotifyAlgorithm.more(seed, limit)
                .distinctBy { it.videoId }
                .filterNot { it.videoId in exclude }
                .filterNot(AppSettings::isSongBlocked)
                .take(limit)
            if (algorithmic.isNotEmpty()) return@withContext algorithmic
        }

        // Nothing from the algorithm. An explicit station says so, rather than
        // becoming a shuffle of the liked list: that substitution is the fault
        // this path exists to end — a "radio" that was really the songs the
        // listener had already saved, wearing another provider's name.
        if (station) {
            Log.w(TAG, "Spotify's own station answered nothing; leaving the radio empty rather than filling it from the library")
            return@withContext emptyList()
        }

        // A top-up keeps going from the catalogue instead of stopping. See
        // [catalogueSeeds] for why it is not [flowSeeds].
        catalogueSeeds(exclude, limit)
    }

    /**
     * The catalogue's own seeds for a queue Spotify's algorithm could not
     * answer: the listener's most-played, then what they played lately, then
     * the public lists.
     *
     * Deliberately not [flowSeeds]. That pool starts with Liked Songs on
     * purpose — they are the most unambiguous evidence of taste a library
     * holds, and a *mix* built from them is the listener's own. A queue
     * top-up is not a mix: falling back onto the liked list is how "next
     * up" became a replay of what was already saved.
     */
    private suspend fun catalogueSeeds(exclude: Set<String>, limit: Int): List<Song> {
        val tracks = runCatching { SpotifyWebApi.topTracks(limit = FLOW_SEEDS) }.getOrNull().orEmpty()
            .ifEmpty { runCatching { SpotifyWebApi.recentlyPlayed(limit = FLOW_SEEDS) }.getOrNull().orEmpty() }
            .ifEmpty { publicSeeds() }
        if (tracks.isEmpty()) return emptyList()
        return songs(tracks)
            .distinctBy { it.videoId }
            .filterNot { it.videoId in exclude }
            .filterNot(AppSettings::isSongBlocked)
            .take(limit)
    }

    /**
     * Remembers which Spotify track [videoId] came from, so a station can later
     * be asked for by the Spotify id behind the row that is playing.
     *
     * Filled from every path that turns a Spotify track into a row or a match —
     * including the playlist and collection pages, which match through
     * [SpotifyImporter] directly and used to leave this map empty. An empty map
     * is what made "start radio" fall back to the listener's liked list: the
     * station was never asked, because the id behind the seed was unknown.
     */
    internal fun rememberSpotifyId(videoId: String, trackId: String) {
        if (videoId.isBlank() || trackId.isBlank()) return
        spotifyIds[videoId] = trackId
    }

    /**
     * The Spotify track id behind [song], or null.
     *
     * Known for a track that was matched from Spotify in this process, and
     * recovered from the id itself for a row that carries one — `spotify:<id>`
     * says where it came from, and a queue restored from disk has no map left
     * to say it a second time. See [SPOTIFY_KEY_PREFIX].
     */
    internal fun spotifyIdOf(song: Song): String? =
        spotifyIds[song.videoId]
            ?: song.videoId.removePrefix(SPOTIFY_KEY_PREFIX)
                .takeIf { song.videoId.startsWith(SPOTIFY_KEY_PREFIX) && it.isNotBlank() && it != song.videoId }

    /** One row of [tracks], matched, or an empty list when none of them were. */
    private suspend fun match(tracks: List<SpotifyTrack>): List<ShelfItem> =
        matched(tracks).map { (track, song) ->
            ShelfItem(
                title = song.title.ifBlank { track.title },
                subtitle = song.artist.ifBlank { track.artist },
                thumbnailUrl = song.thumbnailUrl ?: track.imageUrl,
                videoId = song.videoId,
                browseId = null,
            )
        }

    /**
     * Songs to seed Flow with, split the way the flow engine reads them.
     *
     * Two lists rather than one, because the engine weighs "what this listener
     * keeps coming back to" differently from "what they put on lately".
     */
    internal data class FlowSeeds(val top: List<Song>, val recent: List<Song>) {
        val isEmpty: Boolean get() = top.isEmpty() && recent.isEmpty()
    }
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

/** The chip, as this app draws one. */
internal fun SpotifyChip.item(): HomeChip =
    HomeChip(id = id, label = label, subChips = subChips.map { it.item() })
