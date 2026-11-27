package com.music.bitchord.data.spotify

import android.content.Context
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.Http
import com.music.bitchord.data.canvas.CANVAS_UA
import com.music.bitchord.data.canvas.SpotifyToken
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One client for the web player's own GraphQL backend.
 *
 * ## Why this is not the public `/v1` API
 *
 * Spotify's public catalogue answers `429 API rate limit exceeded` to a token
 * harvested from the web player, because the limit is on the *client* and the
 * web player's client is shared by everyone who harvests one. Measured on a
 * signed-in session: recently-played, top-tracks and featured-playlists all
 * refused, at once, in the same way.
 *
 * The web player does not use that API. It talks to `api-partner.spotify.com`
 * with a persisted-query name and hash, and that backend answers a harvested
 * token — it is the same backend this app's Spotify library importer already
 * reads a library through, successfully. So this is the client the app uses
 * for the home feed, the browse grid and the collection pages.
 *
 * ## The identity it uses
 *
 * The listener's own token when there is a session, and the visitor's token
 * when there is not. The same request, the same operation, the same answer
 * shape; the only difference is whose taste is in it.
 */
internal object SpotifyGraphQL {

    private const val TAG = "SpotifyGraphQL"
    private const val ENDPOINT = "https://api-partner.spotify.com/pathfinder/v2/query"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * The language Spotify's own labels come back in.
     *
     * A provider rather than a value, because the per-app language picker can
     * change while the app is running and the next request has to follow it:
     * the greeting, the chips and every shelf title come from Spotify in the
     * language the request asks for.
     */
    var language: () -> String? = { null }

    /** The language to send, falling back to English. */
    private fun languageTag(): String =
        language()?.takeIf { it.isNotBlank() } ?: "en"

    /**
     * When Spotify's rate limit lifts, in epoch milliseconds; 0 when there is
     * none.
     *
     * Kept across calls on purpose: the limit is on the client, so it belongs
     * to the session rather than to any one request, and every operation here
     * shares it.
     */
    @Volatile
    private var rateLimitedUntil = 0L

    /** Seconds left on Spotify's rate limit, or 0 when there is none. */
    fun rateLimitSecondsRemaining(): Int =
        ((rateLimitedUntil - System.currentTimeMillis()) / 1000L).toInt().coerceAtLeast(0)

    /**
     * Propagates an overall deadline to token acquisition, header lookup, and
     * OkHttp calls made inside [SpotifyGraphQL], including indirect calls from
     * [SpotifyLibrary], [SpotifyHomeSource], [SpotifyPages], and [SpotifySearch].
     */
    private class CallDeadline(val deadlineNanos: Long) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<CallDeadline>

        fun remainingMs(): Long =
            TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()).coerceAtLeast(0L)
    }

    /**
     * Runs [block] under an overall [timeoutMs] deadline enforced on both
     * token/header acquisition and every OkHttp call in [SpotifyGraphQL].
     */
    suspend fun <T> withCallTimeout(timeoutMs: Long, block: suspend () -> T): T {
        val proposed = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(1L))
        val existing = currentCoroutineContext()[CallDeadline]?.deadlineNanos
        val effective = if (existing != null) minOf(existing, proposed) else proposed
        val remaining = TimeUnit.NANOSECONDS.toMillis(effective - System.nanoTime())
        if (remaining <= 0L) {
            throw SpotifyGraphQLException("Spotify request timed out")
        }
        return try {
            withContext(CallDeadline(effective)) {
                withTimeout(remaining) { block() }
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw SpotifyGraphQLException("Spotify request timed out")
        }
    }

    /**
     * Runs [operation] and returns its `data`.
     *
     * @param callTimeoutMs optional overall deadline covering token lookup and
     *   the HTTP call; null keeps the default client timeouts.
     * @throws SpotifyGraphQLException when the backend refuses the request or
     *   answers with errors of its own.
     */
    suspend fun query(
        operation: String,
        variables: JsonObject,
        callTimeoutMs: Long? = null,
    ): JsonObject {
        if (callTimeoutMs != null) {
            return withCallTimeout(callTimeoutMs) { query(operation, variables, callTimeoutMs = null) }
        }
        return withContext(Dispatchers.IO) {
            // The operation is checked before the token. A name this build has
            // never heard of must not reach the network at all, and going after a
            // token first would spend a session — and, on a phone with no web view
            // yet, a wait — on a request that was never going to be made.
            if (SpotifyOps.hash(operation) == null) {
                throw SpotifyGraphQLException("this build of Spotify no longer offers the '$operation' operation")
            }
            val token = tokenWithinDeadline()
                ?: throw SpotifyGraphQLException("could not get a Spotify token for this session")
            try {
                queryWithToken(operation, variables, token)
            } catch (error: SpotifyGraphQLException) {
                // A stale token is worth one more try with a fresh one; a second
                // refusal is a refusal.
                if (!error.unauthorised) throw error
                SpotifyToken.invalidate()
                val fresh = tokenWithinDeadline() ?: throw error
                queryWithToken(operation, variables, fresh)
            }
        }
    }

    /**
     * Runs [operation] with a token the caller already holds, and returns its
     * `data`.
     *
     * For the callers that come with a token of their own — the importer
     * reads one out of the embed page it has just loaded, and that token is
     * the one the embed's session is signed with. Such a token cannot be
     * renewed here: there is no session behind it to harvest a new one from.
     */
    suspend fun queryWithToken(
        operation: String,
        variables: JsonObject,
        token: String,
        callTimeoutMs: Long? = null,
    ): JsonObject {
        if (callTimeoutMs != null) {
            return withCallTimeout(callTimeoutMs) {
                queryWithToken(operation, variables, token, callTimeoutMs = null)
            }
        }
        return withContext(Dispatchers.IO) {
            val hash = SpotifyOps.hash(operation)
                ?: throw SpotifyGraphQLException("this build of Spotify no longer offers the '$operation' operation")
            queryWithHashAndToken(operation, variables, token, hash)
        }
    }

    /**
     * Runs [operation] against an explicit [hash], taking a token itself.
     *
     * Some clients that work today name a hash this app's operation table does
     * not have — Spotify keeps more than one live at a time — so a caller with
     * a hash of its own is not doing anything wrong, and this is the door it
     * comes through.
     */
    suspend fun queryWithHash(
        operation: String,
        variables: JsonObject,
        hash: String,
        callTimeoutMs: Long? = null,
    ): JsonObject {
        if (callTimeoutMs != null) {
            return withCallTimeout(callTimeoutMs) {
                queryWithHash(operation, variables, hash, callTimeoutMs = null)
            }
        }
        return withContext(Dispatchers.IO) {
            val token = tokenWithinDeadline()
                ?: throw SpotifyGraphQLException("could not get a Spotify token for this session")
            queryWithHashAndToken(operation, variables, token, hash)
        }
    }

    private suspend fun tokenWithinDeadline(): String? {
        val remainingMs = currentCoroutineContext()[CallDeadline]?.remainingMs()
            ?: return token()
        if (remainingMs <= 0L) {
            throw SpotifyGraphQLException("Spotify request timed out getting a token")
        }
        return try {
            withTimeout(remainingMs) { token() }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw SpotifyGraphQLException("Spotify request timed out getting a token")
        }
    }

    private suspend fun headersWithinDeadline(token: String): Map<String, String> {
        val remainingMs = currentCoroutineContext()[CallDeadline]?.remainingMs()
            ?: return headers(token)
        if (remainingMs <= 0L) {
            throw SpotifyGraphQLException("Spotify request timed out preparing headers")
        }
        return try {
            withTimeout(remainingMs) { headers(token) }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw SpotifyGraphQLException("Spotify request timed out preparing headers")
        }
    }

    private suspend fun Call.awaitResponse(): Triple<Int, String, Int?> =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { cancel() }
            enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) {
                        cont.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!cont.isActive) {
                        response.close()
                        return
                    }
                    try {
                        val parsed = response.use { res ->
                            Triple(
                                res.code,
                                res.body?.string().orEmpty(),
                                res.header("Retry-After")?.trim()?.toIntOrNull(),
                            )
                        }
                        if (cont.isActive) {
                            cont.resume(parsed)
                        }
                    } catch (error: Exception) {
                        if (cont.isActive) {
                            cont.resumeWithException(error)
                        }
                    }
                }
            })
        }

    private suspend fun queryWithHashAndToken(
        operation: String,
        variables: JsonObject,
        token: String,
        hash: String,
    ): JsonObject = withContext(Dispatchers.IO) {

        // Checked before the request, not after it: a standing limit is the
        // backend saying "this client is over its budget", and asking again
        // while it stands is what turns one refusal into a page of them.
        val wait = rateLimitSecondsRemaining()
        if (wait > 0) {
            throw SpotifyGraphQLException(
                "Spotify is rate limiting this device; it lifts in about ${wait}s",
                rateLimited = true,
            )
        }

        val requestHeaders = headersWithinDeadline(token)
        val remainingMs = currentCoroutineContext()[CallDeadline]?.remainingMs()
        if (remainingMs != null && remainingMs <= 0L) {
            throw SpotifyGraphQLException("Spotify request timed out before $operation")
        }

        val body = buildJsonObject {
            put("variables", variables)
            put("operationName", operation)
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("version", 1)
                    put("sha256Hash", hash)
                }
            }
        }

        val request = Request.Builder()
            .url(ENDPOINT)
            .post(body.toString().toRequestBody(mediaType))
            .apply { requestHeaders.forEach { (name, value) -> header(name, value) } }
            .build()

        val client = if (remainingMs != null) {
            Http.client.newBuilder()
                .callTimeout(remainingMs, TimeUnit.MILLISECONDS)
                .build()
        } else {
            Http.client
        }
        val (code, payload, retryAfter) = try {
            client.newCall(request).awaitResponse()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            throw SpotifyGraphQLException("the request failed: ${error.message}")
        }

        when {
            code == 429 -> {
                // The header is the server's own answer to "when may I try
                // again", and honouring it is the only thing that makes the
                // wait as short as it can be.
                val seconds = retryAfter ?: DEFAULT_RATE_LIMIT_SECONDS
                rateLimitedUntil = System.currentTimeMillis() + seconds.coerceIn(1, 900) * 1000L
                Log.w(TAG, "$operation: HTTP 429 — Spotify is rate limiting this device")
                throw SpotifyGraphQLException(
                    "Spotify is rate limiting this device; it lifts in about ${seconds}s",
                    rateLimited = true,
                )
            }
            code == 401 ->
                throw SpotifyGraphQLException("Spotify refused $operation (HTTP 401)", unauthorised = true)
            code !in 200..299 -> {
                // Possibly the build named in spotify-app-version, which is kept
                // across starts; read it afresh for the next request.
                SpotifyToken.forgetClientVersion()
                throw SpotifyGraphQLException("Spotify refused $operation (HTTP $code)")
            }
        }

        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: throw SpotifyGraphQLException("Spotify's answer to $operation was not JSON")
        root["errors"]?.jsonArray?.firstOrNull()?.jsonObject?.let { error ->
            val message = error["message"]?.jsonPrimitive?.contentOrNull
            throw SpotifyGraphQLException(message ?: "Spotify's answer to $operation carried an error")
        }
        root["data"]?.jsonObject
            ?: throw SpotifyGraphQLException("Spotify's answer to $operation carried no data")
    }

    /**
     * The bearer token for this session.
     *
     * The listener's own when there is one, and the visitor's otherwise: the
     * public catalogue answers the visitor's, and a listener who has not
     * signed in still gets a home page.
     */
    private suspend fun token(): String? =
        if (AppSettings.spotifySpdcToken.value.isNotBlank()) {
            SpotifyToken.accessToken() ?: SpotifyToken.anonymousToken()
        } else {
            SpotifyToken.anonymousToken()
        }

    /** The headers the web player sends with its own requests. */
    private suspend fun headers(token: String): Map<String, String> = buildMap {
        put("Authorization", "Bearer $token")
        put("Accept", "application/json")
        put("app-platform", "WebPlayer")
        put("Origin", "https://open.spotify.com")
        put("Referer", "https://open.spotify.com/")
        put("User-Agent", CANVAS_UA)
        put("accept-language", languageTag())
        SpotifyToken.clientVersion()?.let { put("spotify-app-version", it) }
        SpotifyToken.clientToken()?.let { put("Client-Token", it) }
    }

    /** How long to hold off after a 429 that named no wait. */
    private const val DEFAULT_RATE_LIMIT_SECONDS = 30
}

/** Raised when Spotify's own backend will not answer. */
internal class SpotifyGraphQLException(
    message: String,
    /** Whether this was a rate limit rather than a refusal. */
    val rateLimited: Boolean = false,
    /** Whether the backend refused the token rather than the request. */
    val unauthorised: Boolean = false,
) : Exception(message)
