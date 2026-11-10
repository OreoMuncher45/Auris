package com.music.bitchord.data.spotify

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.canvas.SpotifyToken
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
import java.util.concurrent.ConcurrentHashMap

/**
 * The Spotify side of the homepage: Spotify's curation, as playable rows.
 *
 * ## What a row is
 *
 * Every row this produces is a row of *tracks*, each already matched to the
 * copy this app will actually play — a YouTube video, or whatever the source
 * ranking hands over instead. That is the shape the switch promises: the
 * algorithm comes from Spotify and the audio keeps coming from the existing
 * path, so a Spotify row plays exactly like a YouTube one.
 *
 * The matching is why rows are capped at [TRACKS_PER_ROW] and why there is a
 * match cache behind them. Matching one track means a YouTube search, so a
 * twenty-track row is twenty searches; the cap keeps the first paint bounded and
 * the cache means the second visit to a row costs nothing at all.
 *
 * ## Signed in, and not
 *
 * Signed in, the rows are the listener's own: what they played recently and
 * what they have had on repeat. Signed out there is no history to read, so the
 * rows come from Spotify's public curation — the featured list — opened up and
 * matched the same way. Both go through one matching path, which is what makes
 * "logged out still shows music" a property of the data rather than a special
 * case in the UI.
 *
 * ## What this is not
 *
 * It is not a byte-for-byte copy of Spotify's own homepage. The private
 * `api-partner` GraphQL that draws that page is behind persisted query hashes
 * that change with every player build, and reading it means scraping Spotify's
 * own JavaScript bundle to keep working. This reads the documented v1 API
 * instead: a smaller, steadier surface, at the cost of different ordering.
 */
internal object SpotifyHomeSource {

    private const val TAG = "SpotifyHome"

    /**
     * How many tracks of a row are matched and shown.
     *
     * A cap rather than the whole collection: a featured playlist can hold
     * hundreds of tracks, and matching them would be hundreds of searches for
     * cards nobody scrolls to. Twelve fills a row at any phone width.
     */
    private const val TRACKS_PER_ROW = 12

    /** How many featured playlists are opened into rows for a signed-out home. */
    private const val FEATURED_ROWS = 3

    /**
     * How many of the listener's own collections become rows.
     *
     * Four is a home screen: Liked Songs and three playlists. More would be a
     * library, and a library this app already imports elsewhere — here it is
     * the shape of a page, and every extra row is another page of tracks and
     * another twelve searches on YouTube.
     */
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

    /** Whether a listener's session can be read; see [SpotifyToken][com.music.bitchord.data.canvas.SpotifyToken]. */
    fun isSignedIn(): Boolean = AppSettings.spotifySpdcToken.value.isNotBlank()

    /**
     * The provider's rows, in the order they should be drawn.
     *
     * Every call is a fresh read of Spotify: this is the feed, and a feed that
     * was cached is a feed that shows yesterday. The *matches* behind it are
     * cached, which is what keeps a re-open cheap.
     *
     * A row that cannot be read — an endpoint that refuses, a token that just
     * expired — is left out rather than drawn empty, so one failure cannot blank
     * the page. What was dropped is logged by name, which is the only way to tell
     * "Spotify refused this" from "there was nothing to show".
     */
    /**
     * The provider's rows, in the order they should be drawn, plus — when there
     * are none — why not.
     *
     * A caller cannot act on "nothing", and neither can the person testing it:
     * an empty Spotify home has three quite different causes (no token minted
     * from the web player, the catalogue endpoints refusing, or the rows being
     * read and then dropped because nothing matched on the YouTube side), and
     * they need opposite fixes. So the reason travels with the result and is
     * shown, rather than being a line in a log nobody has.
     */
    data class HomeOutcome(val shelves: List<HomeShelf>, val reason: String? = null)

    suspend fun shelves(): HomeOutcome = withContext(Dispatchers.IO) {
        SpotifyWebApi.clearProblems()
        // A token first, because everything downstream answers null without one
        // and "Spotify returned nothing" would then be the wrong story entirely.
        // Cached, so this costs the rest of the calls nothing.
        val signedIn = isSignedIn()
        val token = if (signedIn) SpotifyToken.accessToken() else SpotifyToken.anonymousToken()
        if (token == null) {
            return@withContext HomeOutcome(
                emptyList(),
                if (signedIn) {
                    "Couldn't get a Spotify token for your account — the session may have expired."
                } else {
                    "Couldn't get a public Spotify token. Try again in a moment."
                },
            )
        }
        val rows = if (signedIn) signedInRows() else signedOutRows()
        if (rows.isNotEmpty()) return@withContext HomeOutcome(rows)
        // A standing rate limit is the one reason here that is both temporary
        // and actionable, so it leads with when it lifts rather than with which
        // URL refused — and it says what does work, because the answer is not
        // "wait", it is "wait, or let Auris read your library".
        val limited = SpotifyWebApi.rateLimitSecondsRemaining()
        // The endpoint-level problems, if any, are the more specific answer: a
        // refused call says exactly which URL and which status, where the local
        // diagnostic can only say what the effect was.
        val problems = SpotifyWebApi.problems()
        HomeOutcome(
            emptyList(),
            buildString {
                if (limited > 0) {
                    append("Spotify is rate limiting this device. ")
                    append("It lifts in about ${'$'}limited second")
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
     * What went wrong, for the empty state.
     *
     * Written by every step that can fail, so the last thing to fail is what the
     * listener sees. Deliberately plain: it is read off a phone screen by
     * somebody deciding whether to try again, report it, or sign in.
     */
    @Volatile
    private var diagnostic: String? = null

    private fun note(reason: String) {
        diagnostic = reason
        Log.w(TAG, reason)
    }

    /**
     * The listener's own rows.
     *
     * Recently played first: it is the row that says the feed is *theirs*, and
     * it is also the one that changes every time the app is opened, which is what
     * a homepage is for. Top tracks follows, over the last few weeks rather than
     * the year — a year of listening changes too slowly to look like anything
     * that happened today.
     */
    private suspend fun signedInRows(): List<HomeShelf> = coroutineScope {
        // The listener's own library first, and on its own if it answers.
        //
        // This is not a preference, it is the difference between a home screen
        // and a 429. The two backends are not equivalent to Spotify: the web
        // player's own backend is what the rest of this app already reads a
        // Spotify library through, and it answers a harvested token. The public
        // `/v1` catalogue behind [SpotifyWebApi] rate limits per client, and the
        // web player's client is shared by everyone harvesting a token from it —
        // so it refuses, in the same way and at the same time, for every one of
        // them. Measured: a signed-in home asking `/v1` for recently-played,
        // top-tracks and featured playlists came back 429 on all three.
        val library = libraryRows()
        if (library.isNotEmpty()) return@coroutineScope library

        val recent = async { SpotifyWebApi.recentlyPlayed(limit = TRACKS_PER_ROW * 2) }
        val top = async { SpotifyWebApi.topTracks(limit = TRACKS_PER_ROW * 2) }
        val featured = async { featuredRows(count = 2) }

        buildList {
            val recentTracks = recent.await()
            val topTracks = top.await()
            if (recentTracks == null) note("Spotify refused your listening history for this token")
            if (topTracks == null) note("Spotify refused your top tracks for this token")
            row("Recently played", recentTracks)?.let(::add)
            row("On repeat", topTracks)?.let(::add)
        } + featured.await()
    }

    /**
     * The listener's Spotify library, opened into rows.
     *
     * Liked Songs first — it is the one collection every listener has and the
     * one that is unambiguously "their" taste — then their playlists in the
     * order Spotify lists them. The number of rows is capped because each row
     * costs a page of tracks and then a YouTube search per track, so this is
     * four requests and forty-odd searches rather than a library import.
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
     * Rows for a listener who has not signed in.
     *
     * There is no history to read, so the curation is Spotify's own: the featured
     * list, opened into rows. That is what makes the provider useful before a
     * sign-in instead of an empty page begging for one.
     */
    private suspend fun signedOutRows(): List<HomeShelf> = featuredRows(count = FEATURED_ROWS)

    /**
     * Flow's seeds, read from whichever backend will answer.
     *
     * Signed in, the listener's own taste. The `/v1` history calls are tried
     * first because they are the *better* evidence — months of listening rather
     * than one shelf — but a rate-limited client refuses them, and the library
     * is right there with an answer. Falling back to it is what keeps Flow
     * working on a provider whose history endpoints are throttled, instead of
     * handing the mix nothing and letting it come up empty.
     */
    private suspend fun ownedSeeds(): List<SpotifyTrack> {
        val top = runCatching { SpotifyWebApi.topTracks(limit = FLOW_SEEDS) }.getOrNull().orEmpty()
        if (top.isNotEmpty()) return top
        val recent = runCatching { SpotifyWebApi.recentlyPlayed(limit = FLOW_SEEDS) }.getOrNull().orEmpty()
        if (recent.isNotEmpty()) return recent
        return runCatching {
            // Liked Songs: the closest thing to "top tracks" the library has,
            // and the one collection that is unambiguously the listener's own.
            SpotifyLibrary.preview(SpotifyLibrary.LIKED_ID, FLOW_SEEDS)
        }.onFailure { Log.w(TAG, "the Spotify library could not be read for Flow: ${it.message}") }
            .getOrDefault(emptyList())
    }

    /**
     * Spotify's featured playlists, each opened into a row of its first tracks.
     *
     * A playlist *tile* would be the other way to draw this, and it is the way
     * Spotify's own page does it — but a tile needs a screen to open, and this
     * slice has none yet, so a tile here would be a card that does nothing when
     * tapped. Opening the playlist into tracks keeps every card playable with the
     * navigation that already exists.
     */
    private suspend fun featuredRows(count: Int): List<HomeShelf> = coroutineScope {
        val playlists = runCatching { SpotifyWebApi.featuredPlaylists(limit = count) }
            .onFailure { note("Spotify's featured playlists call failed: ${it.message}") }
            .getOrDefault(emptyList())
        if (playlists.isEmpty()) {
            note("Spotify returned no featured playlists for this ${if (isSignedIn()) "account" else "region"}")
            return@coroutineScope emptyList()
        }
        playlists.take(count).map { playlist ->
            async {
                val tracks = runCatching { SpotifyWebApi.playlistTracks(playlist.id, limit = TRACKS_PER_ROW) }
                    .onFailure { note("Spotify returned nothing for '${playlist.name}'") }
                    .getOrDefault(emptyList())
                row(playlist.name, tracks)
            }
        }.awaitAll().filterNotNull()
    }

    /**
     * One row of tracks, matched, or null when nothing in it could be matched.
     *
     * Null means the row is dropped rather than drawn as a heading over an empty
     * space. A match failing is ordinary — a remix, a regional edit, a track
     * YouTube does not carry — so a row is only worth drawing if enough of it
     * survived to be a row at all.
     */
    private suspend fun row(title: String, tracks: List<SpotifyTrack>?): HomeShelf? {
        if (tracks.isNullOrEmpty()) return null
        val wanted = tracks.take(TRACKS_PER_ROW)
        val items = match(wanted)
        if (items.isEmpty()) {
            // The row was read and then lost, which is a different fault from
            // the row never arriving — and the one that looks identical whether
            // the listener is signed in to Spotify or not.
            note(
                "Read ${wanted.size} Spotify tracks ('${wanted.first().title}' by " +
                    "${wanted.first().artist}) and none of them matched on YouTube Music",
            )
            return null
        }
        return HomeShelf(title = title, items = items)
    }

    /**
     * Songs to seed Flow with, split the way the flow engine reads them.
     *
     * Two lists rather than one, because the engine weighs "what this listener
     * keeps coming back to" differently from "what they put on lately": [top]
     * is the former and [recent] the latter, which is the same distinction the
     * YouTube side makes between a liked library and a play history.
     */
    internal data class FlowSeeds(val top: List<Song>, val recent: List<Song>) {
        val isEmpty: Boolean get() = top.isEmpty() && recent.isEmpty()
    }

    /**
     * The provider's contribution to a Flow mix.
     *
     * Signed in, that is the listener's own taste: their top tracks and what
     * they played lately. Signed out there is no history to read, so Spotify's
     * public featured list stands in — it is Spotify's curation and it is the
     * same evidence a new listener's mix would be built from anyway.
     *
     * Returns empty rather than throwing when nothing could be read; the caller
     * falls back to the YouTube seeds rather than handing the engine nothing.
     */
    suspend fun flowSeeds(): FlowSeeds = withContext(Dispatchers.IO) {
        if (isSignedIn()) {
            val owned = ownedSeeds()
            FlowSeeds(top = songs(owned), recent = emptyList())
        } else {
            val featured = runCatching { SpotifyWebApi.featuredPlaylists(limit = 2) }
                .onFailure { Log.w(TAG, "featured playlists could not be read for Flow: ${it.message}") }
                .getOrDefault(emptyList())
            val tracks = featured.flatMap { playlist ->
                runCatching { SpotifyWebApi.playlistTracks(playlist.id, limit = TRACKS_PER_ROW) }
                    .getOrDefault(emptyList())
            }
            FlowSeeds(top = emptyList(), recent = songs(tracks))
        }
    }

    /**
     * Matches each track to something playable, in parallel and bounded.
     *
     * The bound is not politeness: each match is a YouTube search, and a row of
     * twelve set loose at once is twelve searches competing with whatever the
     * listener is already playing. Four at a time keeps a row landing in about a
     * second without the app's own playback waiting behind it.
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
                // looks exactly like a catalogue that refused. The first message
                // is kept for the diagnostic.
                val matched = runCatching { gate.withPermit { SpotifyImporter.matchTrack(track) } }
                    .onFailure { note("Searching YouTube Music for '${track.title}' failed: ${it.message}") }
                    .getOrNull()
                matched?.let {
                    matches[track.id] = it
                    track to it
                }
            }
        }.awaitAll().filterNotNull()
    }

    /** The playable songs behind [tracks], for a seed pool. */
    private suspend fun songs(tracks: List<SpotifyTrack>): List<Song> =
        matched(tracks).map { (_, song) -> song }

    /**
     * The next tracks AutoPlay should queue, from Spotify's own evidence.
     *
     * The pool is the same one Flow is seeded from — top tracks, then what was
     * played lately — because it is the only taste this provider has to go on,
     * minus anything already queued and anything the listener has blocked.
     *
     * Empty when the pool runs dry, which after a few batches it will: Spotify's
     * top and recent lists are finite, and this deliberately does not invent
     * tracks to keep going. The caller falls back to YouTube's radio rather than
     * letting AutoPlay go silent.
     */
    suspend fun autoplayTracks(exclude: Set<String>, limit: Int): List<Song> = withContext(Dispatchers.IO) {
        if (limit <= 0) return@withContext emptyList()
        val seeds = flowSeeds()
        (seeds.top + seeds.recent)
            .distinctBy { it.videoId }
            .filterNot { it.videoId in exclude }
            .filterNot(AppSettings::isSongBlocked)
            .take(limit)
    }

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
}
