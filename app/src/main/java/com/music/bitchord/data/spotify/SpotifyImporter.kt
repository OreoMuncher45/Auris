package com.music.bitchord.data.spotify

import android.net.Uri
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.Http
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request
import java.util.concurrent.atomic.AtomicInteger

/** Single track metadata extracted from a Spotify playlist. */
data class SpotifyImportTrack(
    val title: String,
    val artist: String,
)

/** The result of fetching and resolving a Spotify playlist's tracks. */
data class SpotifyImportResult(
    val title: String,
    val description: String,
    val thumbnailUrl: String?,
    val totalTracks: Int,
    val resolvedVideoIds: List<String>,
    val unmatchedTracks: List<SpotifyImportTrack>,
)

object SpotifyImporter {

    private const val TAG = "SpotifyImporter"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** A Spotify playlist id is 22 base-62 characters. */
    private val PLAYLIST_ID = Regex("""[A-Za-z0-9]{22}""")

    private fun isSpotifyHost(host: String) =
        host == "spotify.com" || host.endsWith(".spotify.com")

    /** Parses a raw user string or link into a Spotify playlist ID if valid. */
    fun extractPlaylistId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        if (trimmed.startsWith("spotify:playlist:")) {
            val id = trimmed.removePrefix("spotify:playlist:").substringBefore("?").substringBefore("/")
            return id.takeIf { PLAYLIST_ID.matches(it) }
        }

        val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        if (!isSpotifyHost(uri.host?.lowercase().orEmpty())) return null
        val segments = uri.pathSegments.orEmpty()
        val playlistIdx = segments.indexOf("playlist")
        if (playlistIdx == -1 || playlistIdx + 1 >= segments.size) return null
        return segments[playlistIdx + 1].takeIf { PLAYLIST_ID.matches(it) }
    }

    /**
     * Fetches public Spotify playlist details from Spotify's embed endpoint.
     */
    suspend fun fetchPlaylistTracks(playlistId: String): Pair<String, List<SpotifyImportTrack>> =
        withContext(Dispatchers.IO) {
            val url = "https://open.spotify.com/embed/playlist/$playlistId"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            val html = Http.client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful || body.isBlank()) {
                    error("Failed to load Spotify playlist (HTTP ${response.code})")
                }
                body
            }

            // Extract <script id="__NEXT_DATA__" type="application/json">...</script>
            val scriptRegex = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            val match = scriptRegex.find(html)
                ?: error("Could not parse Spotify playlist metadata. Make sure the playlist is public.")

            val jsonString = match.groupValues[1]
            val root = json.parseToJsonElement(jsonString).jsonObject

            val props = root["props"]?.jsonObject
                ?.get("pageProps")?.jsonObject
            val stateData = props?.get("state")?.jsonObject
                ?.get("data")?.jsonObject
                ?.get("entity")?.jsonObject
                ?: props?.get("entity")?.jsonObject
                ?: error("Spotify playlist entity missing or private")

            val playlistTitle = stateData["name"]?.jsonPrimitive?.content
                ?: stateData["title"]?.jsonPrimitive?.content
                ?: "Imported Spotify Playlist"

            val trackListJson = stateData["trackList"]?.jsonArray
                ?: stateData["tracks"]?.jsonArray
                ?: JsonArray(emptyList())

            val tracks = mutableListOf<SpotifyImportTrack>()
            for (element in trackListJson) {
                val obj = element.jsonObject
                val title = obj["title"]?.jsonPrimitive?.content
                    ?: obj["name"]?.jsonPrimitive?.content
                    ?: continue
                val subtitle = obj["subtitle"]?.jsonPrimitive?.content
                    ?: obj["artists"]?.jsonArray?.joinToString(", ") {
                        it.jsonObject["name"]?.jsonPrimitive?.content.orEmpty()
                    }
                    ?: ""
                if (title.isNotBlank()) {
                    tracks.add(SpotifyImportTrack(title = title.trim(), artist = subtitle.trim()))
                }
            }

            if (tracks.isEmpty()) {
                error("No tracks found in public Spotify playlist.")
            }

            // The embed page stops at its first 100 tracks. The web player's
            // own query, with the anonymous token the embed hands out, pages
            // through the rest; if it refuses, the 100 already read stand.
            if (tracks.size >= EMBED_TRACK_LIMIT) {
                val token = props?.get("state")?.jsonObject
                    ?.get("settings")?.jsonObject
                    ?.get("session")?.jsonObject
                    ?.get("accessToken")?.jsonPrimitive?.content
                if (token != null) {
                    runCatching { fetchRemainingTracks(playlistId, token, tracks.size) }
                        .getOrNull()
                        ?.let { tracks.addAll(it) }
                }
            }

            Pair(playlistTitle, tracks)
        }

    /**
     * The YouTube Music song that is [track], or null if there is none: the
     * best candidate by title, artist, length and album, and failing that the
     * search's first hit.
     */
    suspend fun matchTrack(track: SpotifyTrack): Song? {
        val target = TrackMatcher.Target(
            title = track.title,
            artist = track.artist,
            durationSec = track.durationMs.takeIf { it > 0 }?.div(1000),
            album = track.album,
        )
        // The queries, in the order the matcher wants them: the title with the
        // artist, then the title alone.
        //
        // The second one is not a nicety. A Spotify title carries the
        // packaging — the bracketed feature, the question mark — and handing
        // that to a catalogue verbatim asks it to match on words it has never
        // stored. "I KNOW ? Travis Scott" finds nothing; "I KNOW ?" finds the
        // recording. A row that found no counterpart was greyed out and
        // unplayable, and the reason was a query one word too long.
        val queries = TrackMatcher.queries(target)
        val query = queries.firstOrNull()
            ?: listOf(track.title, track.artist).filter { it.isNotBlank() }.joinToString(" ")
        // YouTube first, and only when it is switched on. Its search is the
        // widest catalogue and the best matcher input, but a listener who
        // turned YouTube off did so because they do not want its streams —
        // and asking it anyway is how a Spotify search came back with ten
        // tracks and "0 playable".
        if (SourceRegistry.youtubeEnabled()) {
            val result = YtMusicRepository.search(query, SearchFilter.SONGS)
            result.onFailure { error ->
                Log.w(TAG, "YouTube could not be asked for '${track.title}': ${error.message}")
            }
            val candidates = result.getOrNull()
                ?.filterIsInstance<SearchResult.Track>()
                ?.map { it.song }
                .orEmpty()
            (TrackMatcher.best(candidates, target) ?: candidates.firstOrNull())?.let { return it }
        }
        // Then the sources themselves. Their rows already carry their own ids
        // (see [MusicSource.search]), so a hit here is directly playable and
        // needs no substitution at play time: the queue entry points at the
        // source that answered.
        for (source in SourceRegistry.activeForPlayback()) {
            if (source.kind == SourceKind.YOUTUBE) continue
            for (ask in queries) {
                val rows = runCatching { source.search(ask, limit = SOURCE_MATCH_ROWS) }
                    .onFailure { error ->
                        Log.w(TAG, "'${source.displayName}' could not be asked for '${track.title}': ${error.message}")
                    }
                    .getOrNull()
                    .orEmpty()
                val best = TrackMatcher.best(rows, target) ?: continue
                Log.d(TAG, "'${track.title}' matched on ${source.displayName}")
                return best
            }
        }
        return null
    }

    /** Rows one source is asked for when matching a Spotify track. */
    private const val SOURCE_MATCH_ROWS = 5

    /** Tracks the embed page lists before it cuts off. */
    private const val EMBED_TRACK_LIMIT = 100
    private const val PAGE_SIZE = 100

    /**
     * Tracks from [start] to the end of the playlist, a page at a time.
     *
     * The embed page stops at its first hundred tracks, and the rest come
     * from the web player's own query. That query's hash changes with every
     * Spotify build — it used to be written down here, and it was stale, so
     * every long playlist stopped at a hundred tracks without saying so. The
     * hash now comes from the operation table, which is read off the player's
     * own bundles, and the request is the same one the rest of the app makes.
     */
    private suspend fun fetchRemainingTracks(
        playlistId: String,
        token: String,
        start: Int,
    ): List<SpotifyImportTrack> {
        val out = mutableListOf<SpotifyImportTrack>()
        var offset = start
        while (true) {
            val variables = buildJsonObject {
                put("uri", "spotify:playlist:$playlistId")
                put("offset", offset)
                put("limit", PAGE_SIZE)
                put("enableWatchFeedEntrypoint", false)
            }
            val data = SpotifyGraphQL.queryWithToken("fetchPlaylist", variables, token)
            val content = data.obj("playlistV2")?.obj("content") ?: break
            val items = content.arr("items").orEmpty()
            for (item in items) {
                val data = item.jsonObject.obj("itemV2")?.obj("data") ?: continue
                val title = data.str("name")?.trim().orEmpty()
                if (title.isEmpty()) continue
                val artist = data.obj("artists")?.arr("items").orEmpty().mapNotNull { artist ->
                    artist.jsonObject.obj("profile")?.str("name")
                }.joinToString(", ")
                out.add(SpotifyImportTrack(title = title, artist = artist))
            }
            val total = content.int("totalCount") ?: break
            offset += items.size
            if (items.isEmpty() || offset >= total) break
        }
        return out
    }

    /**
     * Resolves a list of Spotify tracks to full Song objects using Innertube search.
     * Reports real-time progress via [onProgress].
     */
    suspend fun resolveToSongs(
        tracks: List<SpotifyImportTrack>,
        onProgress: (completed: Int, total: Int) -> Unit,
    ): Pair<List<Song>, List<SpotifyImportTrack>> = coroutineScope {
        val total = tracks.size
        val completedCount = AtomicInteger(0)
        val semaphore = Semaphore(4) // Bounded concurrency for search queries

        val deferredResults = tracks.map { track ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val query = "${track.title} ${track.artist}".trim()
                    val searchResult = YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull()
                    val matchedSong = searchResult?.filterIsInstance<SearchResult.Track>()
                        ?.firstOrNull()?.song

                    val done = completedCount.incrementAndGet()
                    onProgress(done, total)

                    if (matchedSong != null) {
                        Pair(matchedSong, null)
                    } else {
                        Pair(null, track)
                    }
                }
            }
        }

        val results = deferredResults.awaitAll()
        val songs = results.mapNotNull { it.first }
        val unmatched = results.mapNotNull { it.second }

        Pair(songs, unmatched)
    }

    // ── Small JSON helpers, matching the shape the web player answers in ──

    private fun JsonObject.obj(key: String): JsonObject? =
        this[key]?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonObject }.getOrNull() }

    private fun JsonObject.arr(key: String): JsonArray? =
        this[key]?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonArray }.getOrNull() }

    private fun JsonObject.str(key: String): String? =
        this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.intOrNull
}
