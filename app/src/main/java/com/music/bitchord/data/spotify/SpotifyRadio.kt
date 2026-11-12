package com.music.bitchord.data.spotify

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.Http
import com.music.bitchord.data.canvas.CANVAS_UA
import com.music.bitchord.data.canvas.SpotifyToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * Spotify's own radio: the station its algorithm makes for a seed.
 *
 * ## What this is
 *
 * "Start radio" on a track, on an artist, or on an album is not a search and
 * not a playlist — it is a *playlist Spotify generates* from that seed, and
 * the web player asks for it by name: the seed goes to the inspired-by-mix
 * endpoint, and the answer is a playlist URI. The tracks of that playlist are
 * then read like any other playlist's.
 *
 * That is the difference between "the next songs are similar" and "these are
 * the songs Spotify's own algorithm picked". This app asked YouTube for the
 * second thing while showing the listener a Spotify catalogue, which is why
 * the radio was never Spotify's.
 *
 * ## The endpoint
 *
 * ```
 * GET https://spclient.wg.spotify.com/inspiredby-mix/v2/seed_to_playlist/{seedUri}
 * ```
 *
 * It is not the public `/v1` API — it is the same `spclient` the web player
 * itself calls, and it answers with the same credentials the rest of the
 * Spotify reads use. The answer is a list of media items; the first one is
 * the radio playlist.
 */
internal object SpotifyRadio {

    private const val TAG = "SpotifyRadio"

    /** Where the web player asks for a station. */
    private const val MIX = "https://spclient.wg.spotify.com/inspiredby-mix/v2/seed_to_playlist/"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * The radio playlist Spotify makes for [seedUri], or null.
     *
     * [seedUri] is a track, an artist or an album — `spotify:track:…` and so
     * on. Null means Spotify would not make a station for it: an unknown
     * seed, a refused request, or a listener who is not signed in (the
     * inspired-by mix is personalised, and an anonymous caller gets nothing
     * rather than a public list).
     */
    suspend fun radioPlaylist(seedUri: String): String? = withContext(Dispatchers.IO) {
        if (seedUri.isBlank()) return@withContext null
        val url = (MIX + seedUri).toHttpUrl().newBuilder()
            .addQueryParameter("response-format", "json")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("app-platform", "WebPlayer")
            .header("Origin", "https://open.spotify.com")
            .header("Referer", "https://open.spotify.com/")
            .header("User-Agent", CANVAS_UA)
            .apply {
                SpotifyToken.accessToken()?.let { header("Authorization", "Bearer $it") }
                SpotifyToken.clientToken()?.let { header("Client-Token", it) }
            }
            .build()
        val body = runCatching {
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "the station for $seedUri was refused: HTTP ${response.code}")
                    return@withContext null
                }
                response.body?.string().orEmpty()
            }
        }.getOrElse { error ->
            Log.w(TAG, "the station for $seedUri could not be asked for: ${error.message}")
            return@withContext null
        }
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@withContext null
        val uri = root["mediaItems"]?.let { items ->
            (items as? kotlinx.serialization.json.JsonArray)?.firstOrNull()
                ?.let { it as? JsonObject }?.get("uri")
                ?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content
        }?.trim().orEmpty()
        if (uri.isBlank()) {
            Log.w(TAG, "the station for $seedUri came back without a playlist")
            return@withContext null
        }
        uri
    }

    /**
     * The tracks of the radio station for [seedUri].
     *
     * Empty when Spotify would not make a station, or when the playlist it
     * made could not be read. The caller falls back to its own seeds, and says
     * so rather than pretending the station was empty.
     */
    suspend fun radioTracks(seedUri: String, limit: Int = 20): List<SpotifyTrack> = withContext(Dispatchers.IO) {
        val playlist = radioPlaylist(seedUri) ?: return@withContext emptyList()
        val variables = buildJsonObject {
            put("uri", playlist)
            put("offset", 0)
            put("limit", limit)
            put("includeEpisodeContentRatingsV2", false)
        }
        val root = runCatching { SpotifyGraphQL.query("fetchPlaylistContents", variables) }.getOrElse { error ->
            Log.w(TAG, "the station for $seedUri could not be read: ${error.message}")
            return@withContext emptyList()
        }
        parseTrackPage(buildJsonObject { put("data", root) }).items
    }
}
