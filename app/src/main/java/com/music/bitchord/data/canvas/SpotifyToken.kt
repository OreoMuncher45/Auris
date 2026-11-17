package com.music.bitchord.data.canvas

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.Http
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

/**
 * The bearer token behind Spotify's own web player, minted from the
 * listener's session cookie ([AppSettings.spotifySpdcToken]) rather than an
 * app credential — there is no public API for a track's Canvas, so this walks
 * the same door the web player itself uses to fetch one.
 *
 * Call [init] once at process start, same as [CanvasCache] and the other
 * app-scoped singletons — the WebView harvest below needs a [Context] and
 * none of the suspend call chain that reaches [accessToken] has one to hand.
 */
internal object SpotifyToken {

    private const val TAG = "SpotifyToken"
    private const val BRIDGE_NAME = "BitChordSpotifyTokenBridge"
    private const val HARVEST_TIMEOUT_MS = 20_000L
    private const val DEFAULT_TOKEN_LIFETIME_MS = 3_600_000L

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val harvestMutex = Mutex()

    @Volatile private var appContext: Context? = null

    /**
     * A cached token, the moment it is good until, and the cookie it was
     * minted from (null for the visitor's), as one immutable value.
     *
     * One reference rather than three fields, so a reader sees a token with
     * its own cookie and expiry and never one session's token beside another
     * session's cookie. A hit is a hit only for the cookie the caller is
     * asking with: the settings write lands before the [invalidate] that
     * follows it, and in between the old listener's token must not be handed
     * to the new one. The cookie is held in memory only.
     */
    private data class CachedToken(val token: String, val expiresAt: Long, val cookie: String?) {
        fun usableFor(cookie: String?, now: Long): Boolean =
            this.cookie == cookie && now < expiresAt - 30_000
    }

    /** The listener's token; see [CachedToken]. */
    @Volatile private var access: CachedToken? = null
    @Volatile private var cachedClientId: String? = null

    /**
     * The token the web player mints for a visitor with no session, cached
     * apart from the signed-in one.
     *
     * A separate cache rather than one slot, because they are different
     * identities: the page mints an anonymous token first and the signed-in one
     * a moment later, so a single slot would have the arrival of the visitor's
     * token overwrite the listener's (or the other way round) depending on
     * which request happened to be reading it.
     */
    @Volatile private var anonymous: CachedToken? = null
    @Volatile private var anonymousClientId: String? = null

    @Volatile private var cachedSession: SessionInfo? = null
    @Volatile private var cachedClientToken: String? = null
    @Volatile private var clientTokenExpiresAtMs = 0L

    private data class SessionInfo(val clientVersion: String, val deviceId: String)
    private data class HarvestedToken(val token: String, val expiresAt: Long, val clientId: String?)

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun invalidate() = synchronized(stateLock) {
        access = null
        cachedClientId = null
        anonymous = null
        anonymousClientId = null
        cachedClientToken = null
        clientTokenExpiresAtMs = 0L
        cachedSession = null
        epoch++
        // The kept copies go too: a refusal is the backend saying these are
        // done with, and a cold start must not bring them back. Under the same
        // lock as [restore], so a restore cannot finish publishing the old
        // listener's token after this has cleared it.
        restored = true
        store()?.edit()?.apply { TOKEN_KEYS.forEach(::remove) }?.apply()
    }

    /**
     * Drops the client version, in memory and on disk, so the next request
     * reads the web player's page again.
     *
     * For a refusal that is not about the token: Spotify's response to a stale
     * build is not a documented status, and a kept version that Spotify has
     * stopped accepting would otherwise be sent for the rest of its 12 hours.
     * Costs one page read after an error, at most. The device id stays.
     */
    fun forgetClientVersion() = synchronized(stateLock) {
        cachedSession = null
        store()?.edit()?.remove(K_CLIENT_VERSION)?.remove(K_CLIENT_VERSION_AT)?.apply()
        Unit
    }

    /**
     * Guards [restore] against [invalidate]. Its own lock, not this object's
     * monitor: [session] holds that across a network call, and neither of
     * these should wait on one.
     */
    private val stateLock = Any()

    /**
     * Bumped by every [invalidate]. Work that reaches the network captures it
     * first and publishes only if it is unchanged, checked under [stateLock]:
     * a harvest or mint started for the listener who just left must not land
     * in the cache the next one reads. The network work itself holds no lock.
     */
    @Volatile private var epoch = 0L

    // ---- Kept across process starts ----------------------------------------

    /**
     * Where the tokens above are kept between process starts.
     *
     * Minting one means loading the whole web player in an offscreen WebView,
     * which measured 2.8–3.9 s on a cold start, ahead of every Spotify request
     * the app makes and the home page first among them. A token is good for the
     * lifetime the page stated whether or not the process that harvested it is
     * still alive, so a cold start inside that window reuses it instead.
     *
     * App-private, beside the `sp_dc` cookie these are minted from, which
     * outlives every one of them. The listener's token is kept with a hash of
     * the cookie it came from and is never restored under another.
     */
    private fun store(): SharedPreferences? =
        appContext?.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    /** Whether [restore] has run; it reads the store once per process. */
    @Volatile private var restored = false

    /**
     * Reads the kept tokens into the in-memory cache, once.
     *
     * Lazily, from the first caller rather than from [init]: that runs on the
     * main thread at process start, and the callers here are already off it.
     * Only live tokens are adopted; an expired one is the same as none.
     */
    private fun restore() {
        if (restored) return
        synchronized(stateLock) {
            if (restored) return
            restored = true
            val prefs = store() ?: return
            val now = System.currentTimeMillis()
            fun live(expiresKey: String) = prefs.getLong(expiresKey, 0L).takeIf { now < it - 30_000 }

            val cookie = AppSettings.spotifySpdcToken.value
            if (cookie.isNotBlank() && prefs.getString(K_ACCESS_COOKIE, null) == cookieKey(cookie)) {
                val expires = live(K_ACCESS_EXPIRES)
                val token = prefs.getString(K_ACCESS, null)
                if (expires != null && token != null) {
                    access = CachedToken(token, expires, cookie)
                    prefs.getString(K_ACCESS_CLIENT_ID, null)?.let { cachedClientId = it }
                }
            }
            val anonymousExpires = live(K_ANONYMOUS_EXPIRES)
            val anonymous = prefs.getString(K_ANONYMOUS, null)
            if (anonymousExpires != null && anonymous != null) {
                this.anonymous = CachedToken(anonymous, anonymousExpires, null)
                prefs.getString(K_ANONYMOUS_CLIENT_ID, null)?.let { anonymousClientId = it }
            }
            val clientExpires = live(K_CLIENT_EXPIRES)
            val client = prefs.getString(K_CLIENT, null)
            if (clientExpires != null && client != null) {
                cachedClientToken = client
                clientTokenExpiresAtMs = clientExpires
            }
        }
    }

    /** Writes [harvested] beside the cache it just landed in. Off the caller's thread. */
    private fun keep(mode: Mode, harvested: HarvestedToken, cookie: String?) {
        val editor = store()?.edit() ?: return
        if (mode == Mode.SIGNED_IN) {
            if (cookie.isNullOrBlank()) return
            editor.putString(K_ACCESS, harvested.token)
                .putLong(K_ACCESS_EXPIRES, harvested.expiresAt)
                .putString(K_ACCESS_COOKIE, cookieKey(cookie))
            harvested.clientId?.let { editor.putString(K_ACCESS_CLIENT_ID, it) }
        } else {
            editor.putString(K_ANONYMOUS, harvested.token)
                .putLong(K_ANONYMOUS_EXPIRES, harvested.expiresAt)
            harvested.clientId?.let { editor.putString(K_ANONYMOUS_CLIENT_ID, it) }
        }
        editor.apply()
    }

    /** The cookie, as something that can be compared but not replayed. */
    private fun cookieKey(cookie: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(cookie.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private const val STORE = "bitchord_spotify_tokens"

    private val SPOTIFY_ORIGINS = listOf("https://open.spotify.com/", "https://accounts.spotify.com/")

    /**
     * The shared client with a deadline on the whole call, for the session
     * page and the client token. The token harvest waits for the session read,
     * which waits on this object's monitor while a client token is minted
     * under it, and the shared client's timeouts are per read: a response
     * trickling in could hold the harvest past its own deadline and every
     * harvest queued behind it.
     */
    private val boundedClient by lazy {
        Http.client.newBuilder().callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
    }
    private const val K_ACCESS = "access"
    private const val K_ACCESS_EXPIRES = "access_expires"
    private const val K_ACCESS_COOKIE = "access_cookie"
    private const val K_ACCESS_CLIENT_ID = "access_client_id"
    private const val K_ANONYMOUS = "anonymous"
    private const val K_ANONYMOUS_EXPIRES = "anonymous_expires"
    private const val K_ANONYMOUS_CLIENT_ID = "anonymous_client_id"
    private const val K_CLIENT = "client"
    private const val K_CLIENT_EXPIRES = "client_expires"
    private const val K_DEVICE_ID = "device_id"
    private const val K_CLIENT_VERSION = "client_version"
    private const val K_CLIENT_VERSION_AT = "client_version_at"

    /** How long a kept client version is sent before the page is read again. */
    private const val SESSION_MAX_AGE_MS = 12 * 60 * 60 * 1000L

    /** Everything [invalidate] drops. The device id stays: it is the install's. */
    private val TOKEN_KEYS = listOf(
        K_ACCESS, K_ACCESS_EXPIRES, K_ACCESS_COOKIE, K_ACCESS_CLIENT_ID,
        K_ANONYMOUS, K_ANONYMOUS_EXPIRES, K_ANONYMOUS_CLIENT_ID,
        K_CLIENT, K_CLIENT_EXPIRES, K_CLIENT_VERSION, K_CLIENT_VERSION_AT,
    )

    /**
     * The current bearer token, or null when there is no cookie to mint one
     * from, [init] was never called, or the harvest failed. Cached until
     * shortly before it expires so a skip through a queue doesn't pay for
     * this per track.
     *
     * Minted by loading the real web player in an offscreen WebView with the
     * listener's cookie applied and reading the token it mints for itself —
     * see [harvestViaWebView] for why signing the request ourselves isn't
     * enough.
     */
    suspend fun accessToken(): String? = token(Mode.SIGNED_IN)

    /**
     * A bearer token that belongs to nobody: the one Spotify's web player mints
     * for a visitor with no session.
     *
     * The harvest below has always received one of these and thrown it away,
     * because an anonymous token cannot read a track's Canvas — which is what
     * this class was written for. It *can* read the public catalogue: browse
     * lists, new releases, categories, search, an album's or a playlist's
     * tracks. That is what a homepage has to be built from for a listener who
     * has not signed in, and it is the difference between a provider switch that
     * shows nothing until you log in and one that shows music immediately.
     *
     * Returns null when [init] was never called or the harvest failed, like its
     * signed-in sibling — never because there is no cookie, which is the whole
     * point of it.
     */
    suspend fun anonymousToken(): String? = token(Mode.ANONYMOUS)

    /** Which of the two identities [token] is minting for. */
    private enum class Mode { SIGNED_IN, ANONYMOUS }

    /**
     * A live cached token, or a fresh harvest for [mode].
     *
     * One body for both identities, because everything except the cookie and
     * the cache the answer lands in is identical: the same offscreen web player,
     * the same hook, the same expiry the page states. The signed-in mode is the
     * one that needs a cookie, and it is refused outright without one rather
     * than silently answering with the visitor's token — a caller that asked for
     * the listener's library must not be handed a stranger's view of it.
     */
    private suspend fun token(mode: Mode): String? {
        // Before the cookie is read: an invalidation between reading it and
        // publishing what was minted with it (including one that lands while
        // this call waits for [harvestMutex]) must reject the result.
        val startEpoch = epoch
        val cookie = AppSettings.spotifySpdcToken.value.takeIf { mode == Mode.SIGNED_IN }
        if (mode == Mode.SIGNED_IN && cookie.isNullOrBlank()) return null
        restore()

        fun cached(): CachedToken? = if (mode == Mode.SIGNED_IN) access else anonymous
        cached()?.takeIf { it.usableFor(cookie, System.currentTimeMillis()) }?.let { return it.token }

        return harvestMutex.withLock {
            // A call queued behind an invalidation asked for a session that is
            // gone; whatever is cached now belongs to the next one.
            if (epoch != startEpoch) return@withLock null
            cached()?.takeIf { it.usableFor(cookie, System.currentTimeMillis()) }?.let { return@withLock it.token }

            val context = appContext
            if (context == null) {
                Log.w(TAG, "SpotifyToken.init was never called; no context for the harvest")
                return@withLock null
            }

            // The session page is needed by the first request after this one
            // (its client version and the client token both come off it), and
            // it does not need the token — so it is read while the web player
            // loads instead of after it.
            val harvested = coroutineScope {
                launch(Dispatchers.IO) { session() }
                withContext(Dispatchers.Main) {
                    harvestViaWebView(context, cookie, acceptAnonymous = mode == Mode.ANONYMOUS)
                }
            }
            if (harvested == null) {
                Log.w(TAG, "token harvest failed or timed out")
                return@withLock null
            }

            val published = synchronized(stateLock) {
                if (epoch != startEpoch) return@synchronized false
                if (mode == Mode.SIGNED_IN) {
                    access = CachedToken(harvested.token, harvested.expiresAt, cookie)
                    harvested.clientId?.let { cachedClientId = it }
                } else {
                    anonymous = CachedToken(harvested.token, harvested.expiresAt, null)
                    // Only when nothing better exists: the client id feeds the
                    // client-token request, and the listener's own client id is the
                    // one the rest of the app's signed-in calls should be using.
                    if (cachedClientId == null) {
                        harvested.clientId?.let { anonymousClientId = it }
                    }
                }
                keep(mode, harvested, cookie)
                true
            }
            if (!published) {
                // Minted for a session that was invalidated mid-harvest.
                Log.w(TAG, "discarding a token harvested before the session changed")
                return@withLock null
            }
            Log.d(
                TAG,
                "harvested ${if (mode == Mode.ANONYMOUS) "anonymous " else ""}access token, " +
                    "good until ${java.util.Date(harvested.expiresAt)}",
            )
            harvested.token
        }
    }

    /**
     * Spotify retired the endpoint this used to hit directly with a signed
     * request; its replacement (`/api/token`) checks a TOTP the web player
     * computes from a secret buried in its own JS bundle. That part is
     * reproducible — the secret is short-lived but published — and doing so
     * does get a 200 back with a token. What it doesn't get back is a token
     * anything downstream honours: api.spotify.com and spclient both turn a
     * forged one away with a 429 that reads exactly like rate limiting, right
     * up until it fires on the very first request of a session. So rather
     * than sign the request ourselves, this loads open.spotify.com for real
     * in an offscreen WebView with the cookie applied, and reads the token
     * the page mints for itself by hooking fetch/XHR before its own bundle
     * runs.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun harvestViaWebView(
        context: Context,
        cookie: String?,
        acceptAnonymous: Boolean,
    ): HarvestedToken? {
        val deferred = CompletableDeferred<HarvestedToken?>()

        // No cookie for the anonymous visitor, and none applied: sending a stale
        // one here would make the page mint the listener's token while the
        // caller believes it is reading the public catalogue.
        val cookieManager = CookieManager.getInstance().apply {
            setAcceptCookie(true)
            if (cookie != null) {
                setCookie("https://open.spotify.com/", "sp_dc=$cookie; Domain=.spotify.com; Path=/; Secure")
                setCookie("https://accounts.spotify.com/", "sp_dc=$cookie; Domain=.spotify.com; Path=/; Secure")
                flush()
            } else {
                // A listener who has signed out leaves their cookie in the
                // jar, and the page would mint *their* token for the visitor's
                // harvest — which is then kept on disk as the visitor's. Both
                // the host-only and the domain-wide form are expired, since
                // which one a cookie used is not recorded.
                SPOTIFY_ORIGINS.forEach { origin ->
                    val host = origin.substringAfter("://").trimEnd('/')
                    setCookie(origin, "sp_dc=; Max-Age=0; Path=/")
                    setCookie(origin, "sp_dc=; Max-Age=0; Path=/; Domain=$host")
                    setCookie(origin, "sp_dc=; Max-Age=0; Path=/; Domain=.spotify.com")
                }
                flush()
            }
        }
        // The player caches its token in web storage and skips a fresh
        // /api/token request if a live one is already sitting there, leaving
        // the hook with nothing to see — wipe storage so every harvest forces
        // a real mint.
        runCatching { WebStorage.getInstance().deleteAllData() }

        var webView: WebView? = null
        return try {
            webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = CANVAS_UA
                cookieManager.setAcceptThirdPartyCookies(this, true)
                addJavascriptInterface(TokenBridge(deferred, acceptAnonymous), BRIDGE_NAME)

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        // Guarded: these callbacks fire after the harvest's own
                        // try block has closed, on the main thread, and the web
                        // view is destroyed as soon as a token arrives — so a
                        // page that finishes loading a moment later reaches a
                        // destroyed view. Throwing there is a process crash for
                        // work whose entire failure mode is meant to be "no
                        // token this time".
                        runCatching { view.evaluateJavascript(HOOK_SCRIPT, null) }
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        // Re-assert in case the player navigated client-side
                        // after the onPageStarted injection ran.
                        runCatching { view.evaluateJavascript(HOOK_SCRIPT, null) }
                    }
                }
                loadUrl("https://open.spotify.com/")
            }

            withTimeoutOrNull(HARVEST_TIMEOUT_MS) { deferred.await() }
        } catch (e: Throwable) {
            // Throwable, not Exception: this runs on the main thread at app
            // start, and a WebView that refuses to come up throws things that
            // are not Exceptions. Every failure here means "no token", which the
            // caller already handles, so none of it is worth a crash.
            Log.w(TAG, "token harvest threw: ${e.message}")
            null
        } finally {
            runCatching {
                webView?.removeJavascriptInterface(BRIDGE_NAME)
                webView?.stopLoading()
                webView?.destroy()
            }
        }
    }

    /**
     * Receives raw `/api/token` response bodies from the hooked page.
     *
     * [acceptAnonymous] is the difference between the two identities this is
     * asked for: reading a Canvas needs the listener's own token, so a visitor's
     * is skipped and waited out, while reading the public catalogue is exactly
     * what the visitor's token is for.
     */
    private class TokenBridge(
        private val deferred: CompletableDeferred<HarvestedToken?>,
        private val acceptAnonymous: Boolean,
    ) {
        @JavascriptInterface
        fun onTokenPayload(payload: String?) {
            if (payload.isNullOrBlank() || deferred.isCompleted) return
            runCatching {
                val root = json.parseToJsonElement(payload).jsonObject
                val token = root["accessToken"]?.jsonPrimitive?.contentOrNull
                val anonymous = root["isAnonymous"]?.jsonPrimitive?.contentOrNull
                    ?.toBooleanStrictOrNull() ?: false
                // The player mints an anonymous token before the cookie takes
                // effect; that one can't read canvases, so a signed-in harvest
                // keeps waiting for the logged-in one.
                if (token.isNullOrBlank() || (anonymous && !acceptAnonymous)) return
                val expiresAt = root["accessTokenExpirationTimestampMs"]?.jsonPrimitive?.contentOrNull
                    ?.toLongOrNull()?.takeIf { it > System.currentTimeMillis() }
                    ?: (System.currentTimeMillis() + DEFAULT_TOKEN_LIFETIME_MS)
                val clientId = root["clientId"]?.jsonPrimitive?.contentOrNull
                deferred.complete(HarvestedToken(token, expiresAt, clientId))
            }
        }
    }

    private val HOOK_SCRIPT = """
        (function () {
          if (window.__bitchordTokenHook) return;
          window.__bitchordTokenHook = true;
          var report = function (body) {
            try { $BRIDGE_NAME.onTokenPayload(body); } catch (e) {}
          };
          var isToken = function (u) {
            try { return String(u).indexOf('/api/token') !== -1; } catch (e) { return false; }
          };
          var origFetch = window.fetch;
          if (origFetch) {
            window.fetch = function (input, init) {
              var url = (input && input.url) ? input.url : input;
              var result = origFetch.apply(this, arguments);
              if (isToken(url)) {
                try {
                  result.then(function (res) {
                    res.clone().text().then(report).catch(function () {});
                  }).catch(function () {});
                } catch (e) {}
              }
              return result;
            };
          }
          var origOpen = XMLHttpRequest.prototype.open;
          XMLHttpRequest.prototype.open = function (method, url) {
            this.__bitchordUrl = url;
            return origOpen.apply(this, arguments);
          };
          var origSend = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.send = function () {
            var xhr = this;
            try {
              xhr.addEventListener('load', function () {
                if (isToken(xhr.__bitchordUrl)) {
                  try { report(xhr.responseText); } catch (e) {}
                }
              });
            } catch (e) {}
            return origSend.apply(this, arguments);
          };
        })();
    """.trimIndent()

    /**
     * The second header these endpoints have started demanding alongside the
     * bearer token — api.spotify.com and spclient both turn away a request
     * that only carries [accessToken] with a 429. Best-effort: a caller with
     * no client token just sends the bearer alone and takes whatever the
     * endpoint does with that. Requires [accessToken] to have already
     * succeeded once, since the client id it needs comes off that response.
     */
    @Synchronized
    fun clientToken(): String? {
        restore()
        val startEpoch = epoch
        val now = System.currentTimeMillis()
        cachedClientToken?.let { if (now < clientTokenExpiresAtMs - 30_000) return it }

        val clientId = cachedClientId ?: anonymousClientId
        if (clientId == null) {
            Log.w(TAG, "no client id yet (access token not minted); skipping client token")
            return null
        }
        val session = session() ?: return null

        val payload = buildJsonObject {
            putJsonObject("client_data") {
                put("client_version", session.clientVersion)
                put("client_id", clientId)
                putJsonObject("js_sdk_data") {
                    put("device_brand", "unknown")
                    put("device_model", "unknown")
                    put("os", "android")
                    put("os_version", android.os.Build.VERSION.RELEASE.orEmpty())
                    put("device_id", session.deviceId)
                    put("device_type", "smartphone")
                }
            }
        }

        // The ByteArray overload, deliberately: the String overload of
        // toRequestBody rewrites a charset-less MediaType to
        // "application/json; charset=utf-8", and clienttoken 400s on that
        // exact header rather than the bare "application/json" the real web
        // player sends.
        val request = Request.Builder()
            .url("https://clienttoken.spotify.com/v1/clienttoken")
            .post(payload.toString().toByteArray(Charsets.UTF_8).toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json")
            .header("User-Agent", CANVAS_UA)
            .build()

        var lastCode = -1
        val body = runCatching {
            boundedClient.newCall(request).execute().use { response ->
                lastCode = response.code
                if (response.isSuccessful) response.body?.string() else null
            }
        }.onFailure { Log.w(TAG, "client-token request threw: ${it.message}") }.getOrNull()
        if (body == null) {
            Log.w(TAG, "client-token request failed, http $lastCode")
            return null
        }

        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        if (root == null) {
            Log.w(TAG, "client-token response wasn't JSON")
            return null
        }
        val responseType = root["response_type"]?.jsonPrimitive?.contentOrNull
        if (responseType != "RESPONSE_GRANTED_TOKEN_RESPONSE") {
            Log.w(TAG, "client-token request rejected: $responseType")
            return null
        }
        val granted = root["granted_token"]?.jsonObject
        val token = granted?.get("token")?.jsonPrimitive?.contentOrNull
        if (token == null) {
            Log.w(TAG, "client-token response had no granted_token.token")
            return null
        }
        val ttlSeconds = granted["expires_after_seconds"]?.jsonPrimitive?.contentOrNull
            ?.toLongOrNull() ?: 3600L

        synchronized(stateLock) {
            // Minted for a session that has since been invalidated: not this one's.
            if (epoch != startEpoch) return null
            cachedClientToken = token
            clientTokenExpiresAtMs = now + ttlSeconds * 1000
            store()?.edit()
                ?.putString(K_CLIENT, token)
                ?.putLong(K_CLIENT_EXPIRES, clientTokenExpiresAtMs)
                ?.apply()
        }
        Log.d(TAG, "minted client token, good for ${ttlSeconds}s")
        return token
    }

    /**
     * The web player's own build number, as the page states it.
     *
     * Two things need it: the `spotify-app-version` header the web player's
     * own backend expects, and the operation table's freshness — Spotify ships
     * a build about once a day, and a request that names an old one is
     * refused.
     */
    suspend fun clientVersion(): String? = withContext(Dispatchers.IO) { session()?.clientVersion }

    /**
     * The `sp_t` cookie the web player hands to every visitor.
     *
     * The home feed asks for it by name, and a browser id is what it is: one
     * per install, kept across launches, so Spotify sees a returning visitor
     * rather than a new one every time the app starts.
     */
    suspend fun spToken(): String? = withContext(Dispatchers.IO) { session()?.deviceId }

    /**
     * [SessionInfo.clientVersion] and [SessionInfo.deviceId] both come off the
     * plain, unauthenticated web player page — the client version from a JSON
     * blob it embeds for itself, the device id from the `sp_t` cookie it hands
     * out to every visitor. Fetched once and held for the process: neither
     * changes inside a session.
     *
     * Kept across process starts too, so a cold start with a live token goes
     * straight to its request rather than reading the page first. The device id
     * is the install's for good, as the browser cookie it stands for would be;
     * the client version is trusted for [SESSION_MAX_AGE_MS], about one of
     * Spotify's daily builds, and [invalidate] drops it with the tokens.
     *
     * Synchronized so the read started beside the harvest and the first
     * request's headers share one fetch rather than racing two.
     */
    @Synchronized
    private fun session(): SessionInfo? {
        cachedSession?.let { return it }
        val startEpoch = epoch
        val prefs = store()
        val keptDevice = prefs?.getString(K_DEVICE_ID, null)
        val keptVersion = prefs?.getString(K_CLIENT_VERSION, null)
        val versionAge = System.currentTimeMillis() - (prefs?.getLong(K_CLIENT_VERSION_AT, 0L) ?: 0L)
        if (keptDevice != null && keptVersion != null && versionAge in 0 until SESSION_MAX_AGE_MS) {
            return SessionInfo(keptVersion, keptDevice).also { kept ->
                synchronized(stateLock) { if (epoch == startEpoch) cachedSession = kept }
            }
        }

        val request = Request.Builder()
            .url("https://open.spotify.com")
            .header("User-Agent", CANVAS_UA)
            .build()
        val (html, deviceId) = runCatching {
            boundedClient.newCall(request).execute().use { response ->
                val body = response.body?.string()
                val spT = response.headers("Set-Cookie").firstNotNullOfOrNull { header ->
                    Regex("""sp_t=([^;]+)""").find(header)?.groupValues?.get(1)
                }
                body to spT
            }
        }.getOrNull() ?: (null to null)
        if (html == null) {
            Log.w(TAG, "couldn't load the web player page for session info")
            return null
        }

        val configB64 = Regex("""<script id="appServerConfig" type="text/plain">([^<]+)</script>""")
            .find(html)?.groupValues?.get(1)
        if (configB64 == null) {
            Log.w(TAG, "web player page had no appServerConfig block")
            return null
        }
        val clientVersion = runCatching {
            val configJson = String(Base64.getDecoder().decode(configB64), Charsets.UTF_8)
            json.parseToJsonElement(configJson).jsonObject["clientVersion"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        if (clientVersion == null) {
            Log.w(TAG, "appServerConfig had no clientVersion")
            return null
        }

        val session = SessionInfo(
            clientVersion,
            keptDevice ?: deviceId ?: java.util.UUID.randomUUID().toString(),
        )
        // Still this caller's answer either way; only cached and kept when no
        // invalidation came in while the page was being read.
        synchronized(stateLock) {
            if (epoch == startEpoch) {
                cachedSession = session
                prefs?.edit()
                    ?.putString(K_DEVICE_ID, session.deviceId)
                    ?.putString(K_CLIENT_VERSION, session.clientVersion)
                    ?.putLong(K_CLIENT_VERSION_AT, System.currentTimeMillis())
                    ?.apply()
            }
        }
        return session
    }
}
