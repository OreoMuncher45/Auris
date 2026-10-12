package com.music.bitchord.data.sources

import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.coroutines.coroutineContext

/**
 * Monochrome's own catalogue API: `{base}/search/tracks` plus direct FLAC
 * files at `{base}/track/{id}` — no manifest to decode, no token to
 * refresh, URLs that do not expire.
 *
 * The host sends no quality fields and labels the file
 * `application/octet-stream`, so quality is read off the file's size: the
 * real average bitrate, and the least resolution that bitrate implies.
 * That never overstates — a file reported 24-bit really is beyond CD.
 * Verified live October 2026: a 74MB Creepin' over 221.5s = 2684kbps.
 */
object MonochromeApi {

    const val BASE = "https://tracks.monochrome.st"
    private const val TAG = "BitChord"

    /** 5xx backoff: one unreachable origin is routinely gone on retry. */
    private val RETRY_DELAYS_MS = listOf(1_000L, 2_000L, 4_000L)

    /**
     * Uncompressed stereo PCM floors: a FLAC never averages above the PCM
     * it encodes, so a file past the first cannot be 16/44.1 and one past
     * the second cannot be 44.1kHz at all.
     */
    const val CD_PCM_BITRATE = 1_411_200
    const val HIRES_PCM_BITRATE = 2_116_800

    /** Below this a "FLAC" is a transcode in a lossless container: refuse it. */
    const val MIN_GENUINE_KBPS = 300

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val client: OkHttpClient by lazy {
        Http.client.newBuilder()
            .callTimeout(20, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    @Serializable
    data class SearchResponse(
        @SerialName("tracks") val tracks: List<MonoTrack> = emptyList(),
    )

    @Serializable
    data class MonoTrack(
        @SerialName("id") val id: Long = 0,
        @SerialName("title") val title: String = "",
        @SerialName("artistNames") val artistNames: List<String> = emptyList(),
        /** Milliseconds, unlike hifi-api seconds. */
        @SerialName("duration") val durationMs: Long? = null,
        @SerialName("artwork") val artwork: String? = null,
        @SerialName("isrc") val isrc: String? = null,
        /** A catalogue entry with no file behind it. */
        @SerialName("playable") val playable: Boolean? = null,
    ) {
        val artist: String get() = artistNames.firstOrNull { it.isNotBlank() } ?: "Unknown artist"
    }

    /** A verified file: its URL, measured bitrate, and implied resolution. */
    data class ResolvedFile(
        val url: String,
        val kbps: Int,
        val bitDepth: Int,
        val sampleRateHz: Int,
    )

    suspend fun searchTracks(query: String, limit: Int = 25): List<MonoTrack> {
        val q = query.trim().takeIf { it.isNotEmpty() } ?: return emptyList()
        return try {
            val body = get("$BASE/search/tracks?q=${encode(q)}&limit=${limit.coerceIn(1, 50)}")
            json.decodeFromString<SearchResponse>(body).tracks
                .filter { it.playable != false && it.id != 0L }
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            TrackLog.d(TAG, "  ✗ monochrome search failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Verifies the file answers and measures it, following 5xx backoff.
     * Null when the host lacks the file (4xx) or stays down: both are
     * misses for the caller to walk past.
     */
    suspend fun resolveFile(id: Long, durationMs: Long?): ResolvedFile? {
        val url = "$BASE/track/$id"
        repeat(RETRY_DELAYS_MS.size + 1) { attempt ->
            coroutineContext.ensureActive()
            val head = try {
                head(url)
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                null
            } ?: return null
            if (head.code == 200 || head.code == 206) {
                return fileFrom(url, head.lengthBytes, durationMs)
            }
            if (head.code < 500 || attempt >= RETRY_DELAYS_MS.size) return null
            delay(RETRY_DELAYS_MS[attempt])
        }
        return null
    }

    private data class Head(val code: Int, val lengthBytes: Long)

    private suspend fun head(url: String): Head? {
        val request = Request.Builder().url(url).head()
            .header("User-Agent", BROWSER_UA)
            .build()
        client.newCall(request).execute().use { response ->
            val length = response.header("content-length")?.toLongOrNull() ?: 0L
            return Head(response.code, length)
        }
    }

    /**
     * File size over duration to average bitrate, then the least resolution
     * that bitrate implies. Unknown size or duration falls back to CD
     * figures rather than refusing a file the catalogue says is playable.
     */
    fun fileFrom(url: String, sizeBytes: Long, durationMs: Long?): ResolvedFile {
        val kbps = if (sizeBytes > 0 && (durationMs ?: 0) > 0) {
            (sizeBytes * 8 * 1000 / durationMs!!).toInt()
        } else {
            CD_PCM_BITRATE / 1000
        }
        return ResolvedFile(
            url = url,
            kbps = kbps,
            bitDepth = if (kbps > CD_PCM_BITRATE / 1000) 24 else 16,
            sampleRateHz = if (kbps > HIRES_PCM_BITRATE / 1000) 96_000 else 44_100,
        )
    }

    // ── Matching ──────────────────────────────────────────────────────
    // ISRC-exact first; anything else must share an artist credit, because
    // the search returns covers next to originals.

    private const val KEEP = "abcdefghijklmnopqrstuvwxyz0123456789 "

    fun simplify(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val kept = StringBuilder()
        for (ch in value.lowercase()) {
            if (ch in KEEP) kept.append(ch)
        }
        return kept.toString().trim()
    }

    /** ISRC hits first, everything else in catalogue order. */
    fun rankByIsrc(items: List<MonoTrack>, isrc: String?): List<MonoTrack> {
        if (items.isEmpty()) return items
        val wanted = isrc?.takeIf { it.isNotBlank() }?.uppercase() ?: return items
        return items.filter { it.isrc?.uppercase() == wanted } +
            items.filter { it.isrc?.uppercase() != wanted }
    }

    /**
     * Drops results that only share the title: an ISRC hit always survives,
     * anything else has to be credited to one of the track's artists.
     */
    fun plausible(items: List<MonoTrack>, artists: List<String>, isrc: String?): List<MonoTrack> {
        if (items.isEmpty()) return items
        val wanted = artists.map { simplify(it) }.filter { it.isNotEmpty() }
        if (wanted.isEmpty()) return items
        val exact = isrc?.takeIf { it.isNotBlank() }?.uppercase().orEmpty()
        return items.filter { item ->
            if (exact.isNotEmpty() && item.isrc?.uppercase() == exact) return@filter true
            item.artistNames.any { credit ->
                val simplified = simplify(credit)
                simplified.isNotEmpty() && wanted.any { simplified.contains(it) || it.contains(simplified) }
            }
        }
    }

    private suspend fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", BROWSER_UA)
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            response.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Empty response")
        }
    }

    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
}
