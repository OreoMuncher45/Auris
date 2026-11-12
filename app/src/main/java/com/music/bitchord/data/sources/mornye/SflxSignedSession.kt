package com.music.bitchord.data.sources.mornye

import android.util.Base64
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.Http
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The `signedSession` half of the Mornye extension contract, in this app.
 *
 * Some extensions cannot be asked anything until the host has proved who it is:
 * their gateway hands out a short-lived session only to a client that signs
 * every request with a key derived from that session. Mornye defines the whole
 * exchange — bootstrap, verification challenge, grant exchange, refresh — and
 * an extension calls it through `session.signedFetch(method, path, body,
 * headers)`. Without it those extensions install, search, and then fail at the
 * one step the listener actually wanted.
 *
 * ## This is a port, not an interpretation
 *
 * Every constant, header name, canonical line and retry rule below is taken
 * from `rust_backend/crates/extensions/src/signed_session/` in Mornye, because
 * the gateway on the other side implements exactly that. A signature that is
 * almost right is a 403 that looks like a bug in the extension:
 *
 *  - timestamp `%04d-%02d-%02dT%02d:%02d:%02d.%03dZ`, UTC;
 *  - window = `unixSeconds / timeWindowSeconds` (floored), key =
 *    `HMAC-SHA256(sessionSecret, "<window>:<sessionId>")`;
 *  - signature = base64url-no-pad of `HMAC-SHA256(rollingKey, signingString)`
 *    over ten lines, the path *escaped* and its query excluded;
 *  - body hash = lowercase hex SHA-256 of the exact body bytes.
 *
 * ## Blocking on purpose
 *
 * `sessionCall` is synchronous in the extension's JavaScript, so this runs on
 * the interpreter's thread and does its own network I/O there. That is the
 * same trade Mornye makes: the extension is inside a call it cannot proceed
 * without, and one blocked interpreter is cheaper than a promise the extension
 * was never written to await.
 */
class SflxSignedSession(
    private val extensionId: String,
    private val config: SignedSessionConfig,
    directory: File,
    /** Told when the gateway wants the listener to verify; the URL to open. */
    private val onVerificationRequired: (String) -> Unit = {},
) {

    /** The install id is the identity the gateway remembers; one per record. */
    private val recordFile: File = File(directory, config.fileName())

    private val lock = Any()

    /** A challenge the listener has been sent to, so the grant can be matched. */
    @Volatile
    private var pending: Pending? = null

    /** Set when the gateway said this session generation must be reverified. */
    @Volatile
    private var blockedGeneration: String = ""

    /** Guards [awaitVerification]; notified when a grant is redeemed. */
    private val verificationMonitor = Object()

    @Volatile
    private var granted = false

    /** Why the last verification attempt produced no page. Shown in the UI. */
    @Volatile
    private var lastVerificationError: String = ""

    private data class Pending(
        val authUrl: String,
        val callback: String,
        val state: String,
        val createdAt: Long,
    )

    // ── JS surface ───────────────────────────────────────────────────────

    /**
     * One call from `session.*`, answered as JSON text.
     *
     * A failure is answered in the shape that method documents rather than
     * thrown: the extension's own error path reads `result.ok` or
     * `result.success`, and an exception crossing the bridge surfaces as a
     * stack trace inside its code instead of the sentence it was written to
     * show.
     */
    fun sessionCall(method: String, argsJson: String): String {
        val name = method.trim()
        return try {
            val args = runCatching { JSONArray(argsJson.ifBlank { "[]" }) }.getOrDefault(JSONArray())
            when (name) {
                "status" -> status().toString()
                "clear" -> {
                    clear()
                    JSONObject().put("success", true).toString()
                }
                "completeGrant" -> {
                    val grant = text(args, 0)
                    if (grant.isEmpty()) {
                        JSONObject().put("success", false).put("error", "no grant").toString()
                    } else {
                        completeGrant(grant)
                        JSONObject().put("success", true).toString()
                    }
                }
                "signedFetch" -> {
                    if (args.length() < 2) {
                        JSONObject().put("ok", false).put("error", "method and path are required").toString()
                    } else {
                        signedFetch(
                            method = text(args, 0),
                            path = text(args, 1),
                            body = text(args, 2),
                            headers = headersOf(args.opt(3)),
                        ).toString()
                    }
                }
                else -> JSONObject().put("success", false).put("error", "unknown signed session method").toString()
            }
        } catch (error: Throwable) {
            if (error is InterruptedException) throw error
            Log.w(TAG, "sflx[$extensionId] session.$name failed: ${error.message}")
            when (name) {
                "status" -> JSONObject().put("authenticated", false).put("error", messageOf(error)).toString()
                "signedFetch" -> JSONObject().put("ok", false).put("error", messageOf(error)).toString()
                else -> JSONObject().put("success", false).put("error", messageOf(error)).toString()
            }
        }
    }

    fun status(): JSONObject = synchronized(lock) {
        val record = runCatching { load() }.getOrDefault(Record())
        val blocked = isBlocked(record)
        JSONObject()
            .put("authenticated", usable(record) && !blocked)
            .put("verification_required", blocked)
            .put("expires_at", record.expiresAt)
            .put("install_id", record.installId)
            .put("session_id", record.sessionId)
            .put("app_version", config.appVersion)
            .put("platform", config.platform)
            .also { if (blocked) pending?.authUrl?.let { url -> it.put("auth_url", url) } }
    }

    fun clear() = synchronized(lock) {
        val record = load()
        record.clear()
        save(record)
        blockedGeneration = ""
        pending = null
    }

    /** What the UI needs to draw the row, without running any JavaScript. */
    data class Snapshot(
        val configured: Boolean,
        val authenticated: Boolean,
        val verificationRequired: Boolean,
        val expiresAt: String,
        val authUrl: String,
        val error: String,
    )

    fun snapshot(): Snapshot = runCatching {
        val status = status()
        Snapshot(
            configured = true,
            authenticated = status.optBoolean("authenticated"),
            verificationRequired = status.optBoolean("verification_required"),
            expiresAt = status.optString("expires_at"),
            authUrl = status.optString("auth_url"),
            error = lastVerificationError,
        )
    }.getOrElse {
        Snapshot(true, false, false, "", "", it.message.orEmpty())
    }

    /** The URL the listener should be shown for a pending verification, if any. */
    fun verificationUrl(): String? = pending?.authUrl?.takeIf { it.isNotBlank() }

    /** The `state` of the challenge this host issued, so a callback can be matched to it. */
    fun pendingState(): String? = pending?.state?.takeIf { it.isNotBlank() }

    /**
     * Bootstraps, if there is anything to bootstrap, and answers with the URL
     * the listener must visit — or null when the gateway already knows us.
     */
    fun requestVerification(): String? = runCatching {
        val bootstrap = bootstrap()
        if (bootstrap is Bootstrap.Challenge) {
            lastVerificationError = ""
            bootstrap.url
        } else {
            lastVerificationError = "this gateway already knows this install"
            null
        }
    }.onFailure { error ->
        lastVerificationError = error.message ?: "the gateway did not answer"
    }.getOrNull()

    // ── Bootstrap ────────────────────────────────────────────────────────

    private sealed interface Bootstrap {
        data class Session(val record: Record) : Bootstrap
        data class Challenge(val url: String, val callback: String, val state: String) : Bootstrap
    }

    private fun bootstrap(): Bootstrap {
        synchronized(lock) {
            pending?.let { challenge ->
                if (challenge.authUrl.isNotBlank() && now() - challenge.createdAt < CHALLENGE_LIFETIME_MS) {
                    return Bootstrap.Challenge(challenge.authUrl, challenge.callback, challenge.state)
                }
                pending = null
            }
            val record = load()
            if (usable(record) && !isBlocked(record)) return Bootstrap.Session(record)

            val url = withQuery(
                endpoint(config.bootstrap)
                    ?: throw SflxException("signed session baseUrl is not usable"),
                listOf("app_version" to config.appVersion, "install_id" to record.installId),
            )
            // Mornye retries a failed bootstrap once after dropping its pooled
            // connections: the first attempt after a gateway restart can fail
            // on a socket the server has already forgotten.
            var response = request("GET", url, "", emptyMap())
                ?: request("GET", url, "", emptyMap())
                ?: throw SflxException("signed-session bootstrap did not answer")
            if (response.code !in 200..299) {
                val retry = retryAfterSeconds(response)
                throw SflxException(
                    "signed-session bootstrap returned HTTP ${response.code}" +
                        if (retry > 0) "; retry-after $retry" else "",
                )
            }

            val answer = runCatching { JSONObject(response.body) }.getOrElse {
                throw SflxException("signed-session bootstrap answered with no JSON")
            }
            val sessionId = answer.field("session_id")
            val secret = answer.field("session_secret")
            val expires = answer.field("expires_at")
            if (sessionId.isNotEmpty() && secret.isNotEmpty() && expires.isNotEmpty()) {
                record.sessionId = sessionId
                record.sessionSecret = secret
                record.expiresAt = expires
                save(record)
                blockedGeneration = ""
                pending = null
                return Bootstrap.Session(record)
            }

            var authUrl = answer.field("auth_url").ifEmpty { answer.field("challenge_url") }
            var state = base64UrlNoPad(randomBytes(32))
            authUrl.toHttpUrlOrNull()?.queryParameter("state")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                state = it
            }
            val callback = withQuery(config.callbackUrl, listOf("state" to state))
            if (authUrl.isNotEmpty() && authUrl.toHttpUrlOrNull()?.queryParameter("state").isNullOrEmpty()) {
                authUrl = withQuery(authUrl, listOf("state" to state))
            }
            if (authUrl.isEmpty() && answer.field("challenge_id").isNotEmpty()) {
                authUrl = challengeUrl(answer.field("challenge_id"), state)
            }
            if (authUrl.isEmpty()) {
                throw SflxException("signed-session bootstrap returned neither a session nor a challenge")
            }
            val challenge = Pending(authUrl, callback, state, now())
            pending = challenge
            return Bootstrap.Challenge(challenge.authUrl, challenge.callback, challenge.state)
        }
    }

    /** `GET <challenge>?id=<challenge>&cb=<callback?cb_version=v2grant&state=…>` */
    private fun challengeUrl(challengeId: String, state: String): String {
        val callback = withQuery(
            config.callbackUrl,
            listOf("cb_version" to "v2grant", "state" to state),
        )
        val base = endpoint(config.challenge) ?: return ""
        return withQuery(base, listOf("id" to challengeId, "cb" to callback))
    }

    // ── Grant exchange ───────────────────────────────────────────────────

    /**
     * Redeems the grant the verification page returned.
     *
     * The state check is the reason this is a parameter at all: a callback the
     * app did not itself send the listener to is a grant for somebody else's
     * challenge.
     */
    fun completeGrant(grant: String, state: String? = null): Boolean {
        val trimmed = grant.trim()
        if (trimmed.isEmpty()) return false
        synchronized(lock) {
            val challenge = pending
            if (state != null && challenge != null && challenge.state.isNotEmpty() && state != challenge.state) {
                Log.w(TAG, "sflx[$extensionId] grant state did not match the challenge; ignoring")
                return false
            }
            val record = load()
            val body = JSONObject()
                .put("grant", trimmed)
                .put("install_id", record.installId)
                .put("app_version", config.appVersion)
                .put("platform", config.platform)
                .toString()
            val url = endpoint(config.exchange)
                ?: throw SflxException("signed session baseUrl is not usable")

            var exchanged: JSONObject? = null
            for (attempt in 0 until 3) {
                val response = request(
                    "POST",
                    url,
                    body,
                    mapOf("content-type" to "application/json"),
                ) ?: throw SflxException("session exchange did not answer")
                val retry = retryAfterSeconds(response)
                if (response.code == 429 && attempt < 2) {
                    Thread.sleep(retry.coerceIn(1, 300) * 1_000L)
                    continue
                }
                if (response.code !in 200..299) {
                    throw SflxException("session exchange failed: HTTP ${response.code}")
                }
                exchanged = runCatching { JSONObject(response.body) }.getOrNull()
                break
            }
            val answer = exchanged ?: throw SflxException("session exchange did not complete")
            val sessionId = answer.field("session_id")
            val secret = answer.field("session_secret")
            val expires = answer.field("expires_at")
            if (sessionId.isEmpty() || secret.isEmpty() || expires.isEmpty()) {
                throw SflxException("session exchange response missing session fields")
            }
            record.sessionId = sessionId
            record.sessionSecret = secret
            record.expiresAt = expires
            save(record)
            blockedGeneration = ""
            pending = null
            granted = true
            synchronized(verificationMonitor) { verificationMonitor.notifyAll() }
            Log.d(TAG, "sflx[$extensionId] signed session exchanged and stored")
            return true
        }
    }

    // ── Refresh ──────────────────────────────────────────────────────────

    private fun refreshDue(record: Record): Boolean {
        if (config.refresh.isBlank() || !usable(record)) return false
        val expires = parseTime(record.expiresAt) ?: return false
        val now = now()
        return now < expires && expires - now <= REFRESH_WINDOW_MS
    }

    private fun refresh(record: Record) {
        val response = signedRequest(
            record,
            "POST",
            config.refresh,
            JSONObject().put("install_id", record.installId).toString(),
            emptyMap(),
        ) ?: return
        if (response.code !in 200..299) {
            Log.w(TAG, "sflx[$extensionId] session refresh HTTP ${response.code}")
            return
        }
        val answer = runCatching { JSONObject(response.body) }.getOrNull() ?: return
        var changed = false
        answer.field("session_id").takeIf { it.isNotEmpty() && it != record.sessionId }?.let {
            record.sessionId = it
            changed = true
        }
        answer.field("session_secret").takeIf { it.isNotEmpty() && it != record.sessionSecret }?.let {
            record.sessionSecret = it
            changed = true
        }
        answer.field("expires_at").takeIf { it.isNotEmpty() && it != record.expiresAt }?.let {
            record.expiresAt = it
            changed = true
        }
        if (changed) save(record)
    }

    // ── Signed fetch ─────────────────────────────────────────────────────

    /**
     * `session.signedFetch(method, path, body, headers)`, ported rule for rule.
     *
     * Deliberately *not* held under [lock]: when the gateway asks for
     * verification this waits for the listener to finish, and the grant is
     * redeemed from the UI thread — which would take that lock and deadlock
     * against the call that is waiting for it. The record is read once and
     * mutated under the lock in the places that mutate it.
     */
    fun signedFetch(
        method: String,
        path: String,
        body: String,
        headers: Map<String, String>,
        depth: Int = 0,
    ): JSONObject {
        granted = false
        val verb = method.trim().uppercase(Locale.ROOT)
        var record = synchronized(lock) { load() }
        val missing = when {
            record.sessionId.isEmpty() || record.sessionSecret.isEmpty() -> "signed session is not authenticated"
            (parseTime(record.expiresAt) ?: Long.MAX_VALUE) < now() -> {
                record.clear()
                synchronized(lock) { save(record) }
                "signed session expired"
            }
            else -> null
        }
        if (missing != null) {
            return bootstrapThenVerify(missing, method, path, body, headers, depth)
        }
        if (isBlocked(record)) {
            val bootstrap = bootstrap()
            if (bootstrap is Bootstrap.Challenge) {
                return afterChallenge(bootstrap.url, method, path, body, headers, depth)
            }
            record = synchronized(lock) { load() }
            if (!usable(record) || isBlocked(record)) {
                return JSONObject()
                    .put("ok", false)
                    .put("error", "verification_required: signed-session generation is blocked")
            }
        }
        if (refreshDue(record)) {
            runCatching { refresh(record) }
            val latest = synchronized(lock) { load() }
            if (!usable(latest) || isBlocked(latest)) {
                val bootstrap = bootstrap()
                if (bootstrap is Bootstrap.Challenge) {
                    return afterChallenge(bootstrap.url, method, path, body, headers, depth)
                }
                return JSONObject().put("ok", false).put("error", "signed session is not authenticated")
            }
            record = latest
        }

        var sessionRetries = 0
        var providerRetries = 0
        var requestAuthRetry = false
        while (true) {
            val response = signedRequest(record, verb, path, body, headers)
                ?: return JSONObject().put("ok", false).put("error", "signed request did not answer")
            val contract = ErrorContract.of(response.body)

            if (contract.providerRetry(response.code)) {
                if (providerRetries >= 2) return responseValue(response)
                providerRetries++
                Thread.sleep(providerDelayMs(response, contract))
                continue
            }
            if (contract.requestAuthInvalid(response.code)) {
                val latest = synchronized(lock) { load() }
                if (!requestAuthRetry && usable(latest) && !latest.sameSession(record)) {
                    requestAuthRetry = true
                    record = latest
                    continue
                }
                return responseValue(response)
            }
            val action = contract.gatewayAction(response.code)
            if (action.isEmpty()) return responseValue(response)

            val latest = synchronized(lock) { load() }
            if (usable(latest) && !latest.sameSession(record)) {
                if (sessionRetries >= 1) {
                    return JSONObject().put("ok", false).put("error", "signed-session retry limit reached")
                }
                sessionRetries++
                record = latest
                continue
            }
            if (action == "bootstrap_session" && latest.sameSession(record)) {
                synchronized(lock) {
                    blockedGeneration = ""
                    latest.clear()
                    save(latest)
                }
            } else if (action == "verify" && latest.sameSession(record)) {
                blockedGeneration = record.generation()
            }
            val bootstrap = bootstrap()
            if (bootstrap is Bootstrap.Challenge) {
                return afterChallenge(bootstrap.url, method, path, body, headers, depth)
            }
            val bootstrapped = synchronized(lock) { load() }
            if (usable(bootstrapped) && !bootstrapped.sameSession(record) && sessionRetries < 1) {
                sessionRetries++
                record = bootstrapped
                continue
            }
            return responseValue(response)
        }
    }

    private fun bootstrapThenVerify(
        reason: String,
        method: String,
        path: String,
        body: String,
        headers: Map<String, String>,
        depth: Int,
    ): JSONObject {
        val bootstrap = bootstrap()
        if (bootstrap !is Bootstrap.Challenge) {
            return JSONObject().put("ok", false).put("error", reason)
        }
        return afterChallenge(bootstrap.url, method, path, body, headers, depth)
    }

    /**
     * Waits for the listener to finish verifying, then retries once.
     *
     * The wait is the feature: the extension's call resumes with a working
     * session instead of failing while the listener is still looking at a
     * captcha, which is the difference between "the song starts when I finish"
     * and "press play again and hope".
     */
    private fun afterChallenge(
        url: String,
        method: String,
        path: String,
        body: String,
        headers: Map<String, String>,
        depth: Int,
    ): JSONObject {
        if (depth < MAX_VERIFY_RETRIES && awaitVerification(url)) {
            val record = synchronized(lock) { load() }
            if (usable(record)) {
                granted = false
                return signedFetch(method, path, body, headers, depth + 1)
            }
        }
        return verificationRequired(url)
    }

    /** True when a grant arrived before the deadline, false otherwise. */
    private fun awaitVerification(url: String): Boolean {
        if (granted) return true
        runCatching { onVerificationRequired(url) }
        val deadline = now() + VERIFY_WAIT_MS
        while (!granted && now() < deadline) {
            runCatching { Thread.sleep(250) }
            if (Thread.currentThread().isInterrupted) break
        }
        return granted
    }

    private fun verificationRequired(url: String): JSONObject {
        if (url.isNotBlank()) runCatching { onVerificationRequired(url) }
        return JSONObject()
            .put("ok", false)
            .put("needsVerification", true)
            .put("error", "VERIFY_REQUIRED")
            .put("open_auth_url", url)
            .put("auth_url", url)
    }

    // ── Signing ──────────────────────────────────────────────────────────

    /** The whole canonical form: ten lines, escaped path, no query. */
    private fun signedHeaders(
        record: Record,
        method: String,
        url: String,
        body: String,
        atMillis: Long,
        nonce: String,
    ): Map<String, String> {
        val parsed = url.toHttpUrlOrNull() ?: throw SflxException("invalid signed session URL")
        val timestamp = timestamp(atMillis)
        val bodyHash = sha256Hex(body.toByteArray(Charsets.UTF_8))
        val window = (atMillis / 1000L) / config.timeWindowSeconds
        val rolling = base64UrlNoPad(
            hmacSha256(
                record.sessionSecret.toByteArray(Charsets.UTF_8),
                "$window:${record.sessionId}".toByteArray(Charsets.UTF_8),
            ),
        )
        val signing = listOf(
            config.schemeLabel,
            method,
            parsed.encodedPath,
            "",
            bodyHash,
            timestamp,
            nonce,
            record.sessionId,
            config.appVersion,
            config.platform,
        ).joinToString("\n")
        val signature = base64UrlNoPad(
            hmacSha256(rolling.toByteArray(Charsets.UTF_8), signing.toByteArray(Charsets.UTF_8)),
        )
        val prefix = config.headerPrefix
        return mapOf(
            "${prefix}Session" to record.sessionId,
            "${prefix}Timestamp" to timestamp,
            "${prefix}Nonce" to nonce,
            "${prefix}Body-SHA256" to bodyHash,
            "${prefix}Signature" to signature,
            "${prefix}App-Version" to config.appVersion,
            "${prefix}Platform" to config.platform,
        )
    }

    private fun signedRequest(
        record: Record,
        method: String,
        path: String,
        body: String,
        extras: Map<String, String>,
    ): HttpAnswer? {
        val url = endpoint(path) ?: throw SflxException("signed session baseUrl is not usable")
        val headers = signedHeaders(record, method, url, body, now(), randomHex(12))
            .toMutableMap()
        // Mornye's own request adds this when the extension did not; a gateway
        // that content-negotiates answers differently without it.
        if (headers.keys.none { it.equals("accept", ignoreCase = true) }) {
            headers["accept"] = "application/json"
        }
        if (body.isNotEmpty()) headers["content-type"] = "application/json"
        headers.putAll(extras.mapKeys { it.key.lowercase(Locale.ROOT) })
        return request(method, url, body, headers)
    }

    // ── HTTP ─────────────────────────────────────────────────────────────

    private class HttpAnswer(
        val code: Int,
        val body: String,
        val headers: Map<String, String>,
    )

    private fun request(
        method: String,
        url: String,
        body: String,
        headers: Map<String, String>,
    ): HttpAnswer? = runCatching {
        val builder = Request.Builder().url(url)
            // The reference host identifies itself as `SpotiFLAC-Mobile/<app
            // version>`, and the gateway fingerprints it: the browser
            // fingerprint headers and this one were captured from a request
            // that was accepted. An app calling itself something else is a
            // request the gateway has no reason to trust, and "no reason to
            // trust" here is a 403 on the one call that has to work.
            .header("User-Agent", "SpotiFLAC-Mobile/${config.appVersion}")
            // The same two headers the reference sends, and for the same
            // reason: the gateway answers differently without them.
            .header("Accept", "application/json")
        headers.forEach { (key, value) ->
            if (!key.equals("user-agent", ignoreCase = true)) builder.header(key, value)
        }
        val payload = if (method == "GET" || method == "HEAD") null
        else body.toRequestBody("application/json".toMediaType())
        builder.method(method, payload)
        Http.client.newCall(builder.build()).execute().use { response ->
            HttpAnswer(
                code = response.code,
                body = response.body?.string().orEmpty(),
                headers = buildMap {
                    for (name in response.headers.names()) {
                        put(name, response.headers[name].orEmpty())
                    }
                },
            )
        }
    }.onFailure {
        Log.w(TAG, "sflx[$extensionId] signed request to ${url.take(120)} failed: ${it.message}")
    }.getOrNull()

    // ── Record on disk ───────────────────────────────────────────────────

    private class Record {
        var installId: String = ""
        var sessionId: String = ""
        var sessionSecret: String = ""
        var expiresAt: String = ""
        var namespace: String = ""
        var baseUrl: String = ""
        var appVersion: String = ""
        var platform: String = ""

        fun clear() {
            sessionId = ""
            sessionSecret = ""
            expiresAt = ""
        }

        fun generation(): String {
            if (sessionId.isEmpty() || sessionSecret.isEmpty()) return ""
            return sha256Hex("$sessionId\n$sessionSecret".toByteArray(Charsets.UTF_8))
        }

        fun sameSession(other: Record): Boolean =
            sessionId.isNotEmpty() && sessionId == other.sessionId && sessionSecret == other.sessionSecret
    }

    private fun load(): Record {
        val record = Record()
        val bytes = runCatching { recordFile.takeIf { it.isFile }?.readBytes() }.getOrNull()
        if (bytes != null && bytes.size <= MAX_RECORD_BYTES) {
            runCatching {
                val root = JSONObject(bytes.decodeToString())
                record.installId = root.field("install_id")
                record.sessionId = root.field("session_id")
                record.sessionSecret = root.field("session_secret")
                record.expiresAt = root.field("expires_at")
                record.namespace = root.field("namespace")
                record.baseUrl = root.field("base_url")
                record.appVersion = root.field("app_version")
                record.platform = root.field("platform")
            }
        }
        var changed = false
        if (record.installId.isBlank()) {
            // Mornye seeds this with 16 random bytes; the file is the only place
            // it lives, so it is written before it is used.
            record.installId = randomHex(16)
            changed = true
        }
        // A record made against a different gateway is not this session.
        if (record.namespace != config.namespace ||
            record.baseUrl != config.baseUrl.trim() ||
            record.appVersion != config.appVersion.trim() ||
            record.platform != config.platform.trim()
        ) {
            if (record.namespace.isNotEmpty() || record.baseUrl.isNotEmpty() ||
                record.appVersion.isNotEmpty() || record.platform.isNotEmpty()
            ) {
                record.clear()
            }
            record.namespace = config.namespace
            record.baseUrl = config.baseUrl.trim()
            record.appVersion = config.appVersion.trim()
            record.platform = config.platform.trim()
            changed = true
        }
        if (changed) save(record)
        return record
    }

    private fun save(record: Record) {
        val root = JSONObject()
            .put("install_id", record.installId)
            .put("session_id", record.sessionId)
            .put("session_secret", record.sessionSecret)
            .put("expires_at", record.expiresAt)
            .put("namespace", record.namespace)
            .put("base_url", record.baseUrl)
            .put("app_version", record.appVersion)
            .put("platform", record.platform)
        runCatching {
            recordFile.parentFile?.mkdirs()
            val temporary = File(recordFile.parentFile, "${recordFile.name}.tmp")
            temporary.writeText(root.toString(2))
            runCatching { temporary.setReadable(false, false); temporary.setReadable(true, true) }
            if (!temporary.renameTo(recordFile)) {
                recordFile.writeText(root.toString(2))
                temporary.delete()
            }
        }.onFailure { Log.w(TAG, "sflx[$extensionId] could not save the signed session: ${it.message}") }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun usable(record: Record): Boolean =
        record.sessionId.isNotBlank() &&
            record.sessionSecret.isNotBlank() &&
            (parseTime(record.expiresAt)?.let { now() < it } ?: true)

    private fun isBlocked(record: Record): Boolean =
        blockedGeneration.isNotEmpty() && record.generation() == blockedGeneration

    /** `baseUrl` + endpoint, or the endpoint itself when it is absolute. */
    private fun endpoint(path: String): String? {
        val value = path.trim()
        if (value.isEmpty()) return null
        if (value.startsWith("https://", ignoreCase = true)) return value
        val base = (config.baseUrl.trim().trimEnd('/') + "/").toHttpUrlOrNull() ?: return null
        if (!base.isHttps || base.host.isEmpty()) return null
        return base.resolve(value.trimStart('/'))?.toString()
    }

    private fun withQuery(url: String, values: List<Pair<String, String>>): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        val builder = parsed.newBuilder()
        values.forEach { (key, value) -> builder.setQueryParameter(key, value) }
        return builder.build().toString()
    }

    private fun responseValue(response: HttpAnswer): JSONObject {
        val contract = ErrorContract.of(response.body)
        val retry = retryAfterSeconds(response)
        val result = JSONObject()
            .put("statusCode", response.code)
            .put("status", response.code)
            .put("ok", response.code in 200..299)
            .put("url", "")
            .put("body", response.body)
            .put("headers", JSONObject(HashMap(response.headers)))
            .put("retryAfterSeconds", if (retry > 0) retry else contract.retryAfterSeconds)
        if (contract.code.isNotEmpty() || contract.origin.isNotEmpty() || contract.action.isNotEmpty()) {
            result
                .put("error", contract.error)
                .put("code", contract.code)
                .put("origin", contract.origin)
                .put("action", contract.action)
                .put("retryable", contract.retryable)
                .put("retryMode", contract.retryMode)
        }
        return result
    }

    private fun retryAfterSeconds(response: HttpAnswer): Long {
        val value = response.headers.entries.firstOrNull { it.key.equals("Retry-After", true) }
            ?.value?.trim().orEmpty()
        if (value.isEmpty()) return 0
        value.toLongOrNull()?.let { return it.coerceAtLeast(0) }
        return runCatching {
            val at = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(value)?.time ?: 0L
            ((at - now()) / 1000L).coerceAtLeast(0)
        }.getOrDefault(0)
    }

    private fun providerDelayMs(response: HttpAnswer, contract: ErrorContract): Long {
        val header = retryAfterSeconds(response)
        val seconds = if (header > 0) header
        else if (contract.retryAfterSeconds > 0) contract.retryAfterSeconds
        else 1
        return seconds.coerceIn(0, 120) * 1_000L
    }

    private fun headersOf(value: Any?): Map<String, String> {
        val object0 = value as? JSONObject ?: return emptyMap()
        return buildMap {
            for (key in object0.keys()) {
                val text = object0.opt(key)?.toString().orEmpty()
                if (key.isNotBlank() && text.isNotEmpty()) put(key, text)
            }
        }
    }

    private fun text(args: JSONArray, index: Int): String {
        if (index >= args.length()) return ""
        val value = args.opt(index) ?: return ""
        if (value == JSONObject.NULL) return ""
        return value.toString()
    }

    private fun now(): Long = System.currentTimeMillis()

    private fun timestamp(atMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(atMillis))

    private fun randomBytes(count: Int): ByteArray =
        ByteArray(count).also { SecureRandom().nextBytes(it) }

    private fun messageOf(error: Throwable): String = error.message ?: error.javaClass.simpleName

    companion object {
        private const val TAG = "BitChord"
        private const val MAX_RECORD_BYTES = 1024 * 1024
        private const val CHALLENGE_LIFETIME_MS = 180_000L
        private const val REFRESH_WINDOW_MS = 3_600_000L
        /** How long a signed call waits for the listener to finish a captcha. */
        private const val VERIFY_WAIT_MS = 180_000L
        private const val MAX_VERIFY_RETRIES = 1

        fun randomHex(count: Int): String =
            ByteArray(count).also { SecureRandom().nextBytes(it) }
                .joinToString("") { "%02x".format(it) }

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }

        fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(message)
        }

        fun base64UrlNoPad(bytes: ByteArray): String =
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        /** RFC 3339 with `Z`, `±hh:mm`, comma or dot fractions, to epoch millis. */
        fun parseTime(value: String): Long? {
            val text = value.trim()
            if (text.isEmpty()) return null
            val zulu = Regex(
                """^(\d{4})-(\d{2})-(\d{2})T(\d{1,2}):(\d{2}):(\d{2})(?:[.,](\d{1,9}))?(Z|[+-]\d{2}:\d{2})$""",
            ).find(text) ?: return null
            val groups = zulu.groupValues
            val fraction = groups[7].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
            val calendar = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(
                    groups[1].toInt(), groups[2].toInt() - 1, groups[3].toInt(),
                    groups[4].toInt(), groups[5].toInt(), groups[6].toInt(),
                )
                set(java.util.Calendar.MILLISECOND, fraction.toInt())
            }
            val zone = groups[8]
            val offset = if (zone == "Z") 0L else {
                val sign = if (zone[0] == '-') -1 else 1
                val (hours, minutes) = zone.substring(1).split(':')
                sign * (hours.toLong() * 3_600_000L + minutes.toLong() * 60_000L)
            }
            return calendar.timeInMillis - offset
        }
    }
}

/** The `signedSession` block of a manifest, with Mornye's defaults filled in. */
data class SignedSessionConfig(
    val namespace: String,
    val baseUrl: String,
    val appVersion: String,
    val platform: String,
    val callbackUrl: String,
    val schemeLabel: String,
    val headerPrefix: String,
    val timeWindowSeconds: Long,
    val bootstrap: String,
    val challenge: String,
    val exchange: String,
    val refresh: String,
) {
    /**
     * `signed_sessions/<namespace>-<sha256(scope)[..16]>.json`, where scope is
     * the namespace and the three values that make a session valid for this
     * gateway and no other.
     */
    fun fileName(): String {
        val scope = listOf(
            namespace,
            baseUrl.trim().lowercase(Locale.ROOT),
            appVersion.trim().lowercase(Locale.ROOT),
            platform.trim().lowercase(Locale.ROOT),
        ).joinToString("\n")
        return "$namespace-${SflxSignedSession.sha256Hex(scope.toByteArray(Charsets.UTF_8)).take(16)}.json"
    }

    companion object {
        /** Null when the extension declares no `signedSession`, or declares it unusably. */
        fun from(manifest: SflxManifest): SignedSessionConfig? {
            val block = manifest.raw.field("signedSession") as? JsonObject ?: return null
            val namespace = normaliseNamespace(block.text("namespace"))
            val baseUrl = block.text("baseUrl").trim()
            if (namespace.isEmpty() || baseUrl.isEmpty()) return null
            val endpoints = block.field("endpoints") as? JsonObject
            fun endpoint(name: String, fallback: String): String =
                endpoints?.text(name)?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
            return SignedSessionConfig(
                namespace = namespace,
                baseUrl = baseUrl,
                appVersion = block.text("appVersion").trim().ifEmpty { "ext-1.0" },
                platform = block.text("platform").trim().ifEmpty { "extension" },
                callbackUrl = block.text("callbackUrl").trim().ifEmpty { "mornye://session-grant" },
                schemeLabel = block.text("schemeLabel").trim().ifEmpty { "SPOTIFLAC-HMAC-V1" },
                headerPrefix = block.text("headerPrefix").trim().ifEmpty { "X-Sig-" },
                timeWindowSeconds = block.text("timeWindowSeconds").toLongOrNull()?.takeIf { it > 0 } ?: 300,
                bootstrap = endpoint("bootstrap", "/bootstrap"),
                challenge = endpoint("challenge", "/challenge"),
                exchange = endpoint("exchange", "/session/exchange"),
                refresh = endpoints?.text("refresh")?.trim().orEmpty(),
            )
        }

        private fun normaliseNamespace(value: String): String =
            value.trim().lowercase(Locale.ROOT)
                .filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
                .trim('.', '-', '_')

        private fun JsonObject.field(name: String): JsonElement? =
            entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
                ?.takeIf { it !is JsonNull }

        private fun JsonObject.text(name: String): String {
            val value = field(name)
            return if (value is JsonArray) ""
            else (value as? JsonPrimitive)?.content.orEmpty()
        }
    }
}

/** The JSON body's own description of why a signed request was refused. */
private data class ErrorContract(
    val error: String,
    val code: String,
    val origin: String,
    val action: String,
    val retryable: Boolean,
    val retryMode: String,
    val retryAfterSeconds: Long,
) {
    fun gatewayAction(status: Int): String = when {
        status == 401 && origin == "gateway" && code == "SESSION_INVALID" && action == "bootstrap_session" ->
            "bootstrap_session"
        status == 428 && origin == "gateway" && code == "VERIFY_REQUIRED" && action == "verify" -> "verify"
        else -> ""
    }

    fun providerRetry(status: Int): Boolean =
        status == 503 && origin == "provider" && code == "PROVIDER_UNAVAILABLE" &&
            retryable && retryMode == "same_operation"

    fun requestAuthInvalid(status: Int): Boolean =
        status == 403 && origin == "gateway" && code == "REQUEST_AUTH_INVALID" && action.isEmpty()

    companion object {
        private val empty = ErrorContract("", "", "", "", false, "", 0)

        fun of(body: String): ErrorContract {
            val root = runCatching { JSONObject(body) }.getOrNull() ?: return empty
            return ErrorContract(
                error = root.field("error").trim(),
                code = root.field("code").trim().uppercase(Locale.ROOT),
                origin = root.field("origin").trim().lowercase(Locale.ROOT),
                action = root.field("action").trim().lowercase(Locale.ROOT),
                retryable = root.optBoolean("retryable", false),
                retryMode = root.field("retry_mode").trim().lowercase(Locale.ROOT),
                retryAfterSeconds = root.optLong("retry_after_seconds", 0).coerceAtLeast(0),
            )
        }
    }
}

/** Case-insensitive string field, the way Go's decoder reads these objects. */
private fun JSONObject.field(name: String): String {
    for (key in keys()) {
        if (key.equals(name, ignoreCase = true)) {
            val value = opt(key)
            if (value == null || value == JSONObject.NULL) return ""
            return value.toString()
        }
    }
    return ""
}
