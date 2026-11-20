package com.music.bitchord.data.sources.module

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Recovers TIDAL's public-catalog calls when its old anonymous web token is
 * refused. TIDAL started answering those calls with 401/subStatus 6007
 * ("A user is required for this endpoint"); the same URL answers when asked
 * with a client-credentials catalog token instead.
 *
 * This is not a listener login and grants no account, library or playback
 * access. The extension still uses its signed session for streams. Only an
 * HTTPS TIDAL `/v1/` request which already carries `x-tidal-token` and which
 * actually answered 401 is retried, at the same URL, with a Bearer token. No
 * credential is sent to an extension-chosen host.
 *
 * The catalog client is the public one published by Monochrome at
 * https://github.com/monochrome-music/monochrome/commit/c9e0f862cbd7b9b8850493affe00dddf4b4fd266
 * Like the extension's old web tokens, it identifies an application, not a
 * listener. Access tokens live only in memory and expire with the server's
 * own lifetime, five minutes early so a search does not race their expiry.
 */
internal class TidalCatalogAuth(
    private val tokenEndpoint: String = TOKEN_ENDPOINT,
    private val tokenClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val isCatalogRequest: (Request) -> Boolean = Companion::isTidalCatalogRequest,
) : Interceptor {

    private data class CachedToken(val value: String, val expiresAtMs: Long)

    @Volatile
    private var cached: CachedToken? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val original = chain.proceed(request)
        if (original.code != 401 || !isCatalogRequest(request)) return original

        // A refused original is still the honest answer when catalog auth is
        // unavailable. Do not consume or close its body until a retry is ready.
        val token = runCatching { accessToken() }.getOrElse { return original }
        original.close()
        val retry = request.newBuilder()
            .removeHeader(PUBLIC_TOKEN_HEADER)
            .header(AUTHORIZATION_HEADER, "Bearer $token")
            .build()
        return chain.proceed(retry)
    }

    @Synchronized
    private fun accessToken(): String {
        cached?.takeIf { clock() < it.expiresAtMs }?.let { return it.value }

        // Monochrome's working request carries both HTTP Basic and the form's
        // client fields. Keep that exact contract rather than relying on a
        // server accepting either representation.
        val form = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("client_secret", CLIENT_SECRET)
            .add("grant_type", "client_credentials")
            .build()
        val request = Request.Builder()
            .url(tokenEndpoint)
            .header("Authorization", Credentials.basic(CLIENT_ID, CLIENT_SECRET))
            .post(form)
            .build()
        tokenClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("TIDAL catalog auth HTTP ${response.code}")
            val body = response.body.string()
            val json = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrElse {
                throw IOException("TIDAL catalog auth returned invalid JSON")
            }
            val token = json["access_token"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            if (token.isEmpty()) throw IOException("TIDAL catalog auth returned no access token")
            val lifetimeSeconds = (json["expires_in"]?.jsonPrimitive?.longOrNull ?: DEFAULT_LIFETIME_SECONDS)
                .coerceAtLeast(MINIMUM_LIFETIME_SECONDS)
            val refreshAfterMs = (lifetimeSeconds - EXPIRY_MARGIN_SECONDS)
                .coerceAtLeast(1L) * 1_000L
            cached = CachedToken(token, clock() + refreshAfterMs)
            return token
        }
    }

    companion object {
        private const val TOKEN_ENDPOINT = "https://auth.tidal.com/v1/oauth2/token"
        private const val CLIENT_ID = "txNoH4kkV41MfH25"
        private const val CLIENT_SECRET = "dQjy0MinCEvxi1O4UmxvxWnDjt4cgHBPw8ll6nYBk98="
        private const val PUBLIC_TOKEN_HEADER = "x-tidal-token"
        private const val AUTHORIZATION_HEADER = "Authorization"
        private const val DEFAULT_LIFETIME_SECONDS = 14_400L
        private const val MINIMUM_LIFETIME_SECONDS = 301L
        private const val EXPIRY_MARGIN_SECONDS = 300L

        /** The shared process-wide token cache and interceptor. */
        val shared: TidalCatalogAuth by lazy { TidalCatalogAuth() }

        /**
         * Only the public v1 catalog is eligible. In particular, a Zarz signed
         * download, an arbitrary extension URL, or an HTTP lookalike must never
         * receive this token.
         */
        fun isTidalCatalogRequest(request: Request): Boolean {
            val url = request.url
            return url.isHttps &&
                url.host.lowercase() in TIDAL_HOSTS &&
                url.encodedPath.startsWith("/v1/") &&
                request.header(PUBLIC_TOKEN_HEADER) != null &&
                request.header(AUTHORIZATION_HEADER) == null
        }

        private val TIDAL_HOSTS = setOf(
            "tidal.com",
            "www.tidal.com",
            "listen.tidal.com",
            "api.tidal.com",
            "api.tidalhifi.com",
        )
    }
}
