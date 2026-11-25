package com.music.bitchord.data.apple

import com.music.bitchord.data.canvas.AppleMusicCanvas
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Apple Music's catalog API, on the anonymous bearer this app already scrapes
 * for motion artwork.
 *
 * One seam for every catalog read — search, albums, playlists, artists — so a
 * change to amp-api (it is undocumented) is one file. Nothing here needs an
 * account: the web player's token reads the public catalog. The listener's own
 * library is a separate concern; see `docs/APPLE_MUSIC_INTEGRATION.md`.
 *
 * Apple's **audio** is DRM-protected and is never fetched here. A row from this
 * API is metadata; playback resolves it through the app's existing source
 * matcher, and a row no source answers is shown unplayable rather than
 * substituted.
 */
object AppleMusicApi {

    private const val AMP_ROOT = "https://amp-api.music.apple.com/v1"
    private const val AMP = "$AMP_ROOT/catalog"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The catalogue's storefront, as the device's region names it. */
    val storefront: String get() = AppleMusicCanvas.storefront

    /**
     * GET [path] under the storefront — `search`, `albums/123`, … — with
     * [query], returning the parsed body.
     *
     * Tried twice: a 401 means the scraped bearer is no longer one Apple
     * honours, and [AppleMusicCanvas] has already dropped it, so the second
     * pass scrapes a fresh one. Anything else is an answer, not a retry.
     */
    fun get(path: String, query: Map<String, String> = emptyMap()): JsonObject? {
        repeat(2) {
            val bearer = AppleMusicCanvas.bearer() ?: return null
            val url = "$AMP/$storefront/$path"
                .toHttpUrl()
                .newBuilder()
                .apply { query.forEach { (name, value) -> addQueryParameter(name, value) } }
                .build()
                .toString()
            val body = AppleMusicCanvas.catalogGet(url, bearer) ?: return@repeat
            return runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        }
        return null
    }

    /**
     * A catalog search over [types] (`songs`, `albums`, `artists`, `playlists`).
     *
     * [offset] pages the answer the way every amp-api list does; the catalog
     * only returns [limit] rows at a time, so a search that asks for more
     * reads the next slice.
     */
    fun search(term: String, types: List<String>, limit: Int = 25, offset: Int = 0): JsonObject? {
        val trimmed = term.trim()
        if (trimmed.isEmpty() || types.isEmpty()) return null
        return get(
            "search",
            mapOf(
                "term" to trimmed,
                "types" to types.joinToString(","),
                "limit" to limit.coerceIn(1, 25).toString(),
                "offset" to offset.coerceAtLeast(0).toString(),
            ),
        )
    }

    /**
     * GET [path] under the *listener's own library* — `library/playlists`,
     * `library/albums/{id}/tracks`, … — with the resolved `Music-User-Token`.
     *
     * Returns null when no credential resolves, so the caller shows "connect
     * Apple Music" rather than an empty library that looks like a real one.
     * The same 401-retry as [get]: a rejected token re-scrapes the bearer.
     */
    suspend fun meGet(path: String, query: Map<String, String> = emptyMap()): JsonObject? {
        val userToken = AppleMusicAuth.directUserToken() ?: return null
        repeat(2) {
            val bearer = AppleMusicCanvas.bearer() ?: return null
            val url = "$AMP_ROOT/me/$path"
                .toHttpUrl()
                .newBuilder()
                .apply { query.forEach { (name, value) -> addQueryParameter(name, value) } }
                .build()
                .toString()
            val body = AppleMusicCanvas.catalogGet(url, bearer, userToken) ?: return@repeat
            return runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        }
        return null
    }
}