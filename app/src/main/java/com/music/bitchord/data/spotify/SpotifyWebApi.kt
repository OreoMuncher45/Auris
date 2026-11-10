package com.music.bitchord.data.spotify

import com.music.bitchord.data.Http
import com.music.bitchord.data.canvas.CANVAS_UA
import com.music.bitchord.data.canvas.SpotifyToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * Spotify's public Web API, read by whichever identity the caller can offer.
 *
 * ## Why this and not the web player's own GraphQL
 *
 * Spotify's homepage, as the web player draws it, comes from a private
 * `api-partner` GraphQL endpoint behind persisted query hashes that change with
 * every player build — the app that does it that way has to re-read the hashes
 * out of Spotify's own JavaScript bundle to stay working. This layer reads the
 * documented v1 API instead: browse lists, categories, new releases, search,
 * top and recently played. It is a smaller and steadier surface, it needs no
 * scraping, and what it returns is real Spotify curation rather than a copy of
 * it. What it is not is a byte-for-byte reproduction of the web player's page
 * order, which is a trade this makes deliberately.
 *
 * ## Two identities
 *
 * [Identity.USER] is a call about the listener — their playlists, their top
 * tracks, their history — and it answers null when nobody is signed in, rather
 * than quietly answering as somebody else. [Identity.ANY] is a call about the
 * catalogue, which works signed in or out: signed out it carries the anonymous
 * token the web player mints for every visitor, which is why the Spotify
 * homepage can show music before anyone logs in.
 */
internal object SpotifyWebApi {

    private const val BASE = "https://api.spotify.com/v1"

    /**
     * How long to hold off after a 429 that named no wait.
     *
     * Thirty seconds is short enough not to lose a listener who tapped
     * something, and long enough that the retry is not part of the burst that
     * caused the refusal.
     */
    private const val DEFAULT_RATE_LIMIT_SECONDS = 30
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Which identity a call is made as; see the class note. */
    enum class Identity { ANY, USER }

    // -- catalogue, signed in or out ---------------------------------------

    /** Spotify's own "Made for you"-style editorial list, newest first. */
    suspend fun featuredPlaylists(limit: Int = 20): List<SpotifyCollection> =
        playlistsFrom("browse/featured-playlists", limit).orEmpty()

    /** New albums and singles. */
    suspend fun newReleases(limit: Int = 20): List<SpotifyCollection> {
        val body = get("browse/new-releases", listOf("limit" to limit.toString()), Identity.ANY) ?: return emptyList()
        return albums(body["albums"]?.jsonObject ?: return emptyList())
    }

    /**
     * The browse categories, which are the closest thing the public API has to
     * Spotify's genre rows — Mood, Party, Chill, Focus and the rest.
     */
    suspend fun categories(limit: Int = 20): List<SpotifyCategory> {
        val body = get("browse/categories", listOf("limit" to limit.toString()), Identity.ANY) ?: return emptyList()
        val items = body["categories"]?.jsonObject?.get("items")?.jsonArray ?: return emptyList()
        return items.mapNotNull { element ->
            val category = element as? JsonObject ?: return@mapNotNull null
            val id = category.text("id")
            if (id.isBlank()) return@mapNotNull null
            SpotifyCategory(
                id = id,
                name = category.text("name"),
                imageUrl = category["icons"]?.asArray()?.firstImage(),
            )
        }
    }

    /** A category's playlists — the row a category tile opens. */
    suspend fun categoryPlaylists(categoryId: String, limit: Int = 20): List<SpotifyCollection> =
        playlistsFrom("browse/categories/$categoryId/playlists", limit).orEmpty()

    /** A playlist's tracks, however many the caller asks for. */
    suspend fun playlistTracks(playlistId: String, limit: Int = 50): List<SpotifyTrack> =
        tracksFrom("playlists/$playlistId/tracks", limit)

    /** An album's tracks. */
    suspend fun albumTracks(albumId: String, limit: Int = 50): List<SpotifyTrack> =
        tracksFrom("albums/$albumId/tracks", limit)

    /** Track search, for a provider-scoped search screen. */
    suspend fun searchTracks(query: String, limit: Int = 20): List<SpotifyTrack> {
        if (query.isBlank()) return emptyList()
        val body = get(
            "search",
            listOf("q" to query, "type" to "track", "limit" to limit.toString()),
            Identity.ANY,
        ) ?: return emptyList()
        val items = body["tracks"]?.jsonObject?.get("items")?.jsonArray ?: return emptyList()
        return items.mapNotNull { spotifyTrack(it as? JsonObject ?: return@mapNotNull null) }
    }

    // -- about the listener ------------------------------------------------

    /** The listener's own playlists. Null when nobody is signed in. */
    suspend fun myPlaylists(limit: Int = 20): List<SpotifyCollection>? =
        playlistsFrom("me/playlists", limit, Identity.USER)

    /** What the listener has played lately, most recent first. */
    suspend fun recentlyPlayed(limit: Int = 20): List<SpotifyTrack>? {
        val body = get("me/player/recently-played", listOf("limit" to limit.toString()), Identity.USER)
            ?: return null
        val items = body["items"]?.jsonArray ?: return emptyList()
        return items.mapNotNull { element ->
            val item = (element as? JsonObject)?.get("track") as? JsonObject ?: return@mapNotNull null
            spotifyTrack(item)
        }
    }

    /**
     * The listener's top tracks over [range].
     *
     * `short_term` is the last few weeks, which is what a homepage wants: the
     * long-term list is a summary of a year and changes too slowly to look like
     * anything the listener did today.
     */
    suspend fun topTracks(range: String = "short_term", limit: Int = 20): List<SpotifyTrack>? {
        val body = get("me/top/tracks", listOf("time_range" to range, "limit" to limit.toString()), Identity.USER)
            ?: return null
        val items = body["items"]?.jsonArray ?: return emptyList()
        return items.mapNotNull { spotifyTrack(it as? JsonObject ?: return@mapNotNull null) }
    }

    // -- plumbing ----------------------------------------------------------

    private suspend fun playlistsFrom(
        path: String,
        limit: Int,
        identity: Identity = Identity.ANY,
    ): List<SpotifyCollection>? {
        val body = get(path, listOf("limit" to limit.toString()), identity) ?: return null
        return albums(body["playlists"]?.jsonObject ?: return emptyList())
    }

    private fun albums(container: JsonObject): List<SpotifyCollection> {
        val items = container["items"]?.jsonArray ?: return emptyList()
        return items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.text("id")
            if (id.isBlank()) return@mapNotNull null
            SpotifyCollection(
                id = id,
                name = item.text("name"),
                owner = item["owner"]?.jsonObject?.text("display_name").orEmpty(),
                imageUrl = item["images"]?.asArray()?.firstImage(),
                trackCount = item["tracks"]?.jsonObject?.int("total")
                    ?: item["total_tracks"]?.jsonPrimitive?.intOrNull
                    ?: 0,
            )
        }
    }

    private suspend fun tracksFrom(path: String, limit: Int): List<SpotifyTrack> {
        val body = get(path, listOf("limit" to limit.toString()), Identity.ANY) ?: return emptyList()
        val items = body["items"]?.jsonArray ?: return emptyList()
        return items.mapNotNull { spotifyTrack(it as? JsonObject ?: return@mapNotNull null) }
    }

    private fun spotifyTrack(item: JsonObject): SpotifyTrack? {
        val id = item.text("id")
        if (id.isBlank()) return null
        val artists = item["artists"]?.asArray()
            ?.mapNotNull { (it as? JsonObject)?.text("name")?.takeIf(String::isNotBlank) }
            .orEmpty()
        return SpotifyTrack(
            id = id,
            title = item.text("name"),
            // Joined rather than kept as a list: [SpotifyTrack] is the shape the
            // importer's matcher already takes, so a homepage row hands its
            // track straight to the same matching path an imported playlist
            // uses — one description of a Spotify track in the app, not two.
            artist = artists.joinToString(", "),
            album = item["album"]?.jsonObject?.text("name")?.takeIf { it.isNotBlank() },
            durationMs = item["duration_ms"]?.jsonPrimitive?.intOrNull ?: 0,
            imageUrl = item["album"]?.jsonObject?.get("images")?.asArray()?.firstImage()
                ?: item["images"]?.asArray()?.firstImage(),
        )
    }

    /**
     * One GET, as [identity].
     *
     * A 401 is the token having gone stale between the cache and the call, which
     * is what [SpotifyToken.invalidate] exists for: drop it and try once more
     * rather than failing the row. Anything else that is not a 200 answers null,
     * and every caller treats null as "no rows" — a homepage that loses one shelf
     * to a slow endpoint should still draw the rest.
     */
    private suspend fun get(
        path: String,
        query: List<Pair<String, String>>,
        identity: Identity,
    ): JsonObject? = withContext(Dispatchers.IO) {
        repeat(2) { attempt ->
            val token = when (identity) {
                Identity.USER -> SpotifyToken.accessToken()
                Identity.ANY -> SpotifyToken.accessToken() ?: SpotifyToken.anonymousToken()
            } ?: run {
                record("$path: no token could be minted (${identity.name})")
                return@withContext null
            }

            // Checked before the request, not after it. Spotify's limit is per
            // client, and the web player's client is shared by everyone who
            // harvests a token from it — so a 429 says "this client is over its
            // budget", not "you asked too much this second". Asking again while
            // the limit stands is what turns one refusal into a home page of
            // them, and it is how a listener gets locked out for longer than
            // the limit would have taken.
            val wait = rateLimitSecondsRemaining()
            if (wait > 0) {
                record("$path: skipped — Spotify's rate limit lifts in ${wait}s")
                return@withContext null
            }

            val url = "$BASE/$path".toHttpUrl().newBuilder()
                .apply { query.forEach { (name, value) -> addQueryParameter(name, value) } }
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .header("app-platform", "WebPlayer")
                .header("Origin", "https://open.spotify.com")
                .header("Referer", "https://open.spotify.com/")
                .header("User-Agent", CANVAS_UA)
                .apply { SpotifyToken.clientToken()?.let { header("Client-Token", it) } }
                .build()

            val response = runCatching { Http.client.newCall(request).execute() }.getOrNull()
                ?: run {
                    record("$path: the request did not complete")
                    return@withContext null
                }
            response.use { answered ->
                if (answered.isSuccessful) {
                    return@withContext runCatching {
                        json.parseToJsonElement(answered.body?.string().orEmpty()) as? JsonObject
                    }.getOrNull()
                }
                if (answered.code == 429) {
                    // The header is the server's own answer to "when may I try
                    // again", and honouring it is the only thing that makes the
                    // wait as short as it can be.
                    val retryAfter = answered.header("Retry-After")?.trim()?.toIntOrNull()
                        ?: DEFAULT_RATE_LIMIT_SECONDS
                    rateLimitedUntil = System.currentTimeMillis() + retryAfter.coerceIn(1, 900) * 1000L
                    record(
                        "$path: HTTP 429 — Spotify is rate limiting this device. " +
                            "It lifts in about ${retryAfter}s${bodyHint(answered)}",
                    )
                    return@withContext null
                }
                if (answered.code != 401 || attempt == 1) {
                    // The status, by name, because the three causes of an empty
                    // Spotify home need opposite fixes and "nothing to show"
                    // cannot tell them apart: a 403 is Spotify refusing this
                    // token or endpoint, a 429 is being throttled, and anything
                    // with a body is a request this app got wrong.
                    record("$path: HTTP ${answered.code}${bodyHint(answered)}")
                    return@withContext null
                }
                // Stale token, not a refused one: mint again and take one more
                // run at it.
                SpotifyToken.invalidate()
            }
        }
        null
    }

    /**
     * The first bit of an error body, for the diagnostic line.
     *
     * Spotify says *why* in the body of a refusal, and it is the difference
     * between "this token may not read that" and "that endpoint no longer
     * exists". Truncated, because it is going on a phone screen.
     */
    private fun bodyHint(response: okhttp3.Response): String {
        val text = runCatching { response.peekBody(512).string() }.getOrNull().orEmpty()
        return if (text.isBlank()) "" else " — ${text.replace(Regex("\\s+"), " ").take(160)}"
    }

    /** Which of the last calls failed and how, oldest first; see [record]. */
    private val failures = java.util.concurrent.ConcurrentLinkedQueue<String>()

    /**
     * When Spotify's rate limit lifts, in epoch milliseconds; 0 when there is
     * none.
     *
     * Kept across calls on purpose. The limit is on the *client*, so it belongs
     * to the session rather than to any one request, and every endpoint in this
     * class shares it.
     */
    @Volatile
    private var rateLimitedUntil = 0L

    /** Seconds left on Spotify's rate limit, or 0 when there is none. */
    fun rateLimitSecondsRemaining(): Int =
        ((rateLimitedUntil - System.currentTimeMillis()) / 1000L).toInt().coerceAtLeast(0)

    /** Everything that has gone wrong since [clearProblems]. */
    fun problems(): List<String> = failures.toList()

    /** Clears [problems]; called at the start of a home read. */
    fun clearProblems() {
        failures.clear()
    }

    private fun record(problem: String) {
        if (failures.size < 8) failures += problem
    }

    private fun JsonArray.firstImage(): String? = firstOrNull()
        ?.let { (it as? JsonObject)?.text("url") }
        ?.takeIf { it.isNotBlank() }

    private fun kotlinx.serialization.json.JsonElement.asArray(): JsonArray? = this as? JsonArray

    private fun JsonObject.text(name: String): String =
        (this[name] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.int(name: String): Int = (this[name] as? JsonPrimitive)?.intOrNull ?: 0
}

/**
 * A playlist or an album — anything a tile can open and a list of tracks can be
 * read out of. [owner] is blank for an album, which has no owner to show.
 */
internal data class SpotifyCollection(
    val id: String,
    val name: String,
    val owner: String,
    val imageUrl: String?,
    val trackCount: Int,
)

/** A browse category, which opens a row of playlists rather than a track list. */
internal data class SpotifyCategory(
    val id: String,
    val name: String,
    val imageUrl: String?,
)
