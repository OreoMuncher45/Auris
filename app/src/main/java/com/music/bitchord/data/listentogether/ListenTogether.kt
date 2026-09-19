package com.music.bitchord.data.listentogether

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.music.bitchord.BitChordApplication
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.settings.AppSettings
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Listen together: one party, shared by up to five signed-in devices.
 *
 * This object is the whole client half of the feature — membership, the socket,
 * the clock, and the controls any member may send. It does **not** touch the
 * player. What it publishes instead is [partyPositionMs]: where this device
 * ought to be, right now, on its own clock.
 * [PartySync][com.music.bitchord.playback.PartySync] is what binds that to
 * Media3, and it lives in the playback service rather than here, because
 * everything above that line is testable and everything below it is not.
 *
 * ## A party lasts as long as the app is open
 *
 * Backgrounding, the screen going off, a tunnel, a handover — none of those end
 * a party; that is most of what listening together looks like and the whole
 * reconnect loop below exists for it. Closing the app does end it. The slot is
 * given back at the next launch (see [init]) rather than kept warm, because a
 * party nobody is in is a party of five that only holds four.
 *
 * ## How it stays in time
 *
 * Nothing here acts on "play now" messages, and correctness never depends on
 * when a frame arrived. The server holds a position and the server time that
 * position was true at; [ServerClock] measures this device's offset from that
 * clock; and the playhead is arithmetic from the two. A frame delayed 300 ms
 * carries an anchor 300 ms older and still lands in exactly the right place —
 * which is what makes a party survive one member being on bad mobile data.
 *
 * Three things back that up:
 *
 *  - **Sequence numbers.** Every state the server sends carries a [seq]
 *    [PartyPlayback.seq] that only climbs. Anything not newer than what has
 *    already been applied is dropped unread, so two people pressing pause at
 *    the same moment settle instead of fighting.
 *  - **A heartbeat from the server.** Every few seconds the party is re-told
 *    where it is, unprompted. Lost frames, a phone back from doze, an offset
 *    that has wandered — none of those announce themselves, so nothing waits
 *    to be asked.
 *  - **A reconnect loop that assumes the socket will die.** On a phone it will:
 *    tunnels, handovers, screen-off. The membership outlives the socket (the
 *    server holds the slot through a grace period), so reconnecting is
 *    invisible to everyone else, and the clock is re-sampled on arrival rather
 *    than trusted across the gap.
 *
 * ## Identity
 *
 * Creating or joining requires a signed-in account — that is what puts real
 * names and faces in the member list on every device — and [identity] is where
 * that is enforced on this side. The server cannot verify the claim; what it
 * can do is refuse anyone without the token it minted, which is why the token
 * is stored and never shown.
 */
object ListenTogether {

    enum class Connection { OFFLINE, CONNECTING, LIVE }

    data class State(
        val code: String? = null,
        val you: PartyMember? = null,
        val members: List<PartyMember> = emptyList(),
        val maxMembers: Int = 5,
        val playback: PartyPlayback = PartyPlayback(),
        /**
         * Held separately from [playback] because it arrives separately: the
         * state frame carries only a sequence number for it, and this is
         * replaced when the server says the list has actually changed.
         */
        val queue: PartyQueue = PartyQueue(),
        val connection: Connection = Connection.OFFLINE,
        /** False until the first round trip; the playhead is a guess until then. */
        val clockSynced: Boolean = false,
        val roundTripMs: Long = 0,
        /** The last thing that went wrong, for the screen to show. */
        val error: String? = null,
    ) {
        val inParty: Boolean get() = code != null
        val isFull: Boolean get() = members.size >= maxMembers
    }

    /** A refusal from the server, carrying the machine-readable half. */
    class PartyException(val code: String, message: String, val statusCode: Int? = null) : Exception(message)

    sealed interface SwitchPartyResult {
        data class Success(val partyCode: String) : SwitchPartyResult
        data class TargetFailedRecovered(val partyCode: String, val targetError: String) : SwitchPartyResult
        data class TargetFailedNoParty(val targetError: String) : SwitchPartyResult
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val http = HttpClient(OkHttp) {
        engine {
            config {
                // Not the shared [Http.client]: that one is tuned for streaming
                // media, and its read timeout would take down a socket that is
                // merely quiet. A party can sit paused for ten minutes and the
                // connection is not in trouble — the server's own heartbeat and
                // the ping below are what say whether it is.
                readTimeout(0, TimeUnit.MILLISECONDS)
                connectTimeout(15, TimeUnit.SECONDS)
                pingInterval(20, TimeUnit.SECONDS)
                retryOnConnectionFailure(true)
            }
        }
        install(ContentNegotiation) { json(json) }
        // Installed with no defaults so it changes nothing on its own — the
        // socket in particular must stay open indefinitely. It exists so the
        // health check can set a bound of its own; every other request is
        // left to OkHttp's connect timeout above.
        install(HttpTimeout)
        install(WebSockets)
        // Off, so a 409 "party full" can be read out of the body and shown as
        // itself rather than arriving as a transport exception.
        expectSuccess = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clock = ServerClock()
    private val switchMutex = Mutex()

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _activity = MutableStateFlow<List<PartyActivity>>(emptyList())
    /** Last 100 actions for this app session only. */
    val activity: StateFlow<List<PartyActivity>> = _activity.asStateFlow()

    /**
     * A server the user has pointed this install at instead of the built-in one.
     *
     * Blank means "the one this build ships with", and that address is
     * deliberately never published — not through this flow, not on screen, and
     * not in a log. See [redact]. So this is the only server address the app
     * will ever show back, because it is the only one the user typed.
     */
    private val _customServer = MutableStateFlow("")
    val customServerUrl: StateFlow<String> = _customServer.asStateFlow()

    /**
     * The default party server this build ships pointed at, from `LISTEN_TOGETHER_SERVER`
     * in `local.properties` or build environment.
     */
    val defaultServer: String = normalizeServerBase(BuildConfig.LISTEN_TOGETHER_SERVER)

    /**
     * The dynamic server currently determined to be healthy and available for IDLE operations.
     * Probes custom server first if configured; falls back to [defaultServer] if custom is down.
     */
    private val _effectiveIdleServer = MutableStateFlow(defaultServer)
    fun effectiveIdleServerBase(): String = _effectiveIdleServer.value

    /**
     * The actual server hosting the active party session. Non-null while [State.inParty] is true.
     * Immutable during the party session and never mutated by idle health checks.
     */
    @Volatile
    private var activePartyServerBase: String? = null
    fun activePartyServerBase(): String? = activePartyServerBase

    /** Whether idle operations are currently falling back to the default server. */
    val isUsingDefaultFallback: Boolean
        get() {
            val configured = normalizeServerBase(_customServer.value)
            return configured.isNotBlank() &&
                effectiveIdleServerBase() == defaultServer &&
                configured != defaultServer
        }

    /** Whether a party can be reached at all — a built-in or a custom address. */
    val hasServer: Boolean get() = effectiveIdleServerBase().isNotBlank()

    enum class Health { UNKNOWN, CHECKING, ONLINE, OFFLINE }

    data class ServerStatus(
        val health: Health = Health.UNKNOWN,
        val latencyMs: Long = 0,
        val isFallback: Boolean = false,
    )

    private val _serverStatus = MutableStateFlow(ServerStatus())
    val serverStatus: StateFlow<ServerStatus> = _serverStatus.asStateFlow()

    fun isEligibleForFallback(error: Throwable): Boolean = when (error) {
        is java.net.UnknownHostException,
        is java.net.ConnectException,
        is java.net.NoRouteToHostException,
        is java.net.PortUnreachableException,
        is java.net.SocketTimeoutException,
        is io.ktor.client.plugins.HttpRequestTimeoutException,
        is io.ktor.client.network.sockets.SocketTimeoutException,
        is io.ktor.client.network.sockets.ConnectTimeoutException -> true
        is PartyException -> (error.statusCode ?: 0) in 500..599
        else -> {
            val cause = error.cause
            if (cause != null && cause !== error && isEligibleForFallback(cause)) true
            else false
        }
    }

    private var isScreenActive: Boolean = false
    private var healthMonitorJob: Job? = null
    private val healthMutex = Mutex()
    private var healthGeneration = 0L

    fun setScreenActive(active: Boolean) {
        isScreenActive = active
        if (active) {
            refreshServerHealth()
        }
    }

    private fun startHealthMonitor() {
        healthMonitorJob?.cancel()
        healthMonitorJob = scope.launch {
            while (isActive) {
                val delayMs = if (isScreenActive) 10_000L else 30_000L
                delay(delayMs)
                refreshServerHealth()
            }
        }
    }

    data class HealthResolution(
        val resolvedServer: String,
        val health: Health,
        val isFallback: Boolean,
    )

    suspend fun computeHealthResolution(
        customServer: String,
        probeCustom: suspend () -> Boolean,
        probeDefault: suspend () -> Boolean,
    ): HealthResolution {
        val custom = normalizeServerBase(customServer)
        val hasCustom = custom.isNotBlank()
        return if (hasCustom) {
            if (probeCustom()) {
                HealthResolution(resolvedServer = custom, health = Health.ONLINE, isFallback = false)
            } else if (probeDefault()) {
                HealthResolution(resolvedServer = defaultServer, health = Health.ONLINE, isFallback = true)
            } else {
                HealthResolution(resolvedServer = defaultServer, health = Health.OFFLINE, isFallback = false)
            }
        } else {
            if (probeDefault()) {
                HealthResolution(resolvedServer = defaultServer, health = Health.ONLINE, isFallback = false)
            } else {
                HealthResolution(resolvedServer = defaultServer, health = Health.OFFLINE, isFallback = false)
            }
        }
    }

    fun refreshServerHealth() {
        scope.launch {
            healthMutex.withLock {
                val generation = synchronized(this@ListenTogether) { ++healthGeneration }
                val custom = normalizeServerBase(_customServer.value)

                _serverStatus.value = ServerStatus(Health.CHECKING)
                val startedAt = ServerClock.localNowMs()
                var probeStart = startedAt

                val resolution = computeHealthResolution(
                    customServer = custom,
                    probeCustom = {
                        probeStart = ServerClock.localNowMs()
                        probeHealth(custom)
                    },
                    probeDefault = {
                        probeStart = ServerClock.localNowMs()
                        probeHealth(defaultServer)
                    },
                )

                val latency = if (resolution.health == Health.ONLINE) {
                    ServerClock.localNowMs() - probeStart
                } else 0L

                if (generation == healthGeneration) {
                    _effectiveIdleServer.value = resolution.resolvedServer
                    _serverStatus.value = ServerStatus(
                        health = resolution.health,
                        latencyMs = latency,
                        isFallback = resolution.isFallback,
                    )
                }
            }
        }
    }

    private suspend fun probeHealth(baseUrl: String): Boolean {
        val raw = resolveHttpBase(baseUrl)
        if (raw.isBlank()) return false
        return runCatching {
            http.get("$raw/healthz") {
                timeout { requestTimeoutMillis = HEALTH_TIMEOUT_MS }
            }.status.isSuccess()
        }.getOrElse {
            Log.w(TAG, "health check failed for ${redact(raw)}: ${redact(it.message)}")
            false
        }
    }

    private lateinit var prefs: SharedPreferences
    private var token: String? = null

    @Volatile
    private var session: DefaultClientWebSocketSession? = null
    private var socketJob: Job? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _customServer.value = prefs.getString(KEY_SERVER, null)?.trim().orEmpty()
        _effectiveIdleServer.value = if (_customServer.value.isNotBlank()) {
            normalizeServerBase(_customServer.value)
        } else {
            defaultServer
        }

        val code = prefs.getString(KEY_CODE, null)
        val saved = prefs.getString(KEY_TOKEN, null)
        prefs.edit().remove(KEY_CODE).remove(KEY_TOKEN).apply()
        if (!code.isNullOrBlank() && !saved.isNullOrBlank()) {
            releaseStaleSlot(code, saved)
        }

        val manager = context.getSystemService(ConnectivityManager::class.java)
        if (manager != null) {
            runCatching {
                manager.registerDefaultNetworkCallback(
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            refreshServerHealth()
                        }
                        override fun onLost(network: Network) {
                            _serverStatus.value = ServerStatus(Health.OFFLINE)
                        }
                        override fun onCapabilitiesChanged(
                            network: Network,
                            capabilities: NetworkCapabilities,
                        ) {
                            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                                refreshServerHealth()
                            }
                        }
                    }
                )
            }
        }

        startHealthMonitor()
        refreshServerHealth()
    }

    /**
     * Hands a previous process's slot back, so the others see them leave now
     * rather than when the server's disconnect grace sweeps it.
     */
    private fun releaseStaleSlotOnServer(serverBase: String, code: String, held: String) {
        if (serverBase.isBlank() || code.isBlank() || held.isBlank()) return
        scope.launch {
            runCatching {
                http.post("$serverBase/api/parties/$code/leave") {
                    header("Authorization", "Bearer $held")
                }
            }.onFailure { failure ->
                Log.i(TAG, "stale party slot left to the server's grace: ${redact(failure.message)}")
            }
        }
    }

    private fun releaseStaleSlot(code: String, held: String) {
        val server = activePartyServerBase ?: resolveHttpBase(effectiveIdleServerBase())
        releaseStaleSlotOnServer(server, code, held)
    }

    /** Points this install at another server, or back at the default one if blank. */
    fun setCustomServerUrl(value: String) {
        val cleaned = normalizeServerBase(value)
        _customServer.value = cleaned
        prefs.edit().putString(KEY_SERVER, cleaned).apply()
        refreshServerHealth()
    }

    /** Whether this device has an account it can jam as. */
    fun canJoin(): Boolean = identity() != null

    fun nickname(): String = prefs.getString(KEY_NICKNAME, null).orEmpty()

    fun setNickname(value: String) {
        prefs.edit().putString(KEY_NICKNAME, value.trim().take(80)).apply()
    }

    /**
     * Opens the socket for a membership that was restored from storage.
     */
    fun ensureConnected() {
        if (_state.value.code == null || token == null) return
        if (socketJob?.isActive == true) return
        connect()
    }

    // ------------------------------------------------------------ joining --

    suspend fun createParty(nickname: String = nickname(), maxMembers: Int = 5): Result<String> = switchMutex.withLock {
        val primary = effectiveIdleServerBase()
        val primaryNormalized = resolveHttpBase(primary)
        val attempt = runCatching {
            doCreateOnServer(primaryNormalized, nickname, maxMembers)
        }

        if (attempt.isSuccess) {
            return@withLock Result.success(attempt.getOrThrow())
        }

        val failure = attempt.exceptionOrNull() ?: return@withLock Result.failure(IllegalStateException("Unknown create failure"))

        if (normalizeServerBase(primary) != defaultServer && isEligibleForFallback(failure)) {
            Log.w(TAG, "createParty failed on custom server, falling back to default: ${redact(failure.message)}")
            val fallbackNormalized = resolveHttpBase(defaultServer)
            val fallbackAttempt = runCatching {
                doCreateOnServer(fallbackNormalized, nickname, maxMembers)
            }
            if (fallbackAttempt.isSuccess) {
                return@withLock Result.success(fallbackAttempt.getOrThrow())
            }
            val fallbackFailure = fallbackAttempt.exceptionOrNull() ?: failure
            _state.update { it.copy(error = fallbackFailure.displayMessage()) }
            return@withLock Result.failure(fallbackFailure)
        }

        _state.update { it.copy(error = failure.displayMessage()) }
        Result.failure(failure)
    }

    private suspend fun doCreateOnServer(serverBase: String, nickname: String, maxMembers: Int): String {
        val who = identity(nickname) ?: throw PartyException("not_signed_in", "Sign in to listen together.")
        if (serverBase.isBlank()) throw PartyException("no_server", "Set the party server address first.")
        val membership = post(
            "$serverBase/api/parties",
            JoinRequest(
                who.userId, who.deviceId, who.name, who.avatar, maxMembers,
                autoplayEnabled = AppSettings.autoplay.value,
            ),
        )

        withContext(NonCancellable) {
            activePartyServerBase = serverBase
            token = membership.token
            prefs.edit()
                .putString(KEY_CODE, membership.code)
                .putString(KEY_TOKEN, membership.token)
                .apply()
            clock.reset()
            _state.value = State(
                code = membership.code,
                you = membership.you,
                members = membership.party.members,
                maxMembers = membership.party.maxMembers,
                playback = membership.party.playback,
                connection = Connection.CONNECTING,
            )
        }
        connect()
        return membership.code
    }

    /**
     * Joins a party from the currently active server.
     *
     * Architectural contract:
     * - Precondition: IDLE only.
     * - Used by manual party-code entry.
     * - Performs a direct Idle -> Live transition.
     *
     * Do not merge this with [switchPartyWithRecovery]. Deep-link invites
     * require target-first transactional semantics and recovery guarantees
     * that are intentionally not part of this API.
     */
    suspend fun joinParty(code: String, nickname: String = nickname()): Result<String> = switchMutex.withLock {
        val targetServer = resolveHttpBase(effectiveIdleServerBase())
        runCatching {
            doJoinOnServer(targetServer, code, nickname)
        }.onFailure { failure ->
            Log.w(TAG, "could not enter a party: ${redact(failure.message)}")
            _state.update { it.copy(error = failure.displayMessage()) }
        }
    }

    private suspend fun doJoinOnServer(serverBase: String, code: String, nickname: String): String {
        val who = identity(nickname) ?: throw PartyException("not_signed_in", "Sign in to listen together.")
        if (serverBase.isBlank()) throw PartyException("no_server", "Set the party server address first.")
        val cleaned = code.filter { it.isLetterOrDigit() }.uppercase()
        if (cleaned.length != CODE_LENGTH) {
            throw PartyException("bad_code", "A party code is six letters or digits.")
        }
        val membership = post(
            "$serverBase/api/parties/$cleaned/join",
            JoinRequest(who.userId, who.deviceId, who.name, who.avatar),
        )

        withContext(NonCancellable) {
            activePartyServerBase = serverBase
            token = membership.token
            prefs.edit()
                .putString(KEY_CODE, membership.code)
                .putString(KEY_TOKEN, membership.token)
                .apply()
            clock.reset()
            _state.value = State(
                code = membership.code,
                you = membership.you,
                members = membership.party.members,
                maxMembers = membership.party.maxMembers,
                playback = membership.party.playback,
                connection = Connection.CONNECTING,
            )
        }
        connect()
        return membership.code
    }

    /**
     * Transactionally transitions to an invite target.
     *
     * Architectural contract:
     *
     * Before successful target `/join`, this method must not mutate the current
     * party membership, current token, or persisted server preference.
     *
     * Phase 1 — Target join (cancellable):
     * - Target /join is attempted before mutating the current party or
     *   persisted server preference.
     * - If the target fails, the current party remains untouched.
     *
     * Phase 2 — Local commit (NonCancellable):
     * - Begins only after the target join succeeds.
     * - Commits the target server/token and local CONNECTING state.
     * - Dispatches old-party cleanup asynchronously.
     * - Target WebSocket establishment occurs outside the commit boundary.
     *
     * This API is intentionally separate from [joinParty]. It is the
     * deep-link transition state machine and may begin from either IDLE
     * or LIVE. It strictly contacts [targetCustomServer] and NEVER queries
     * or triggers idle fallback.
     */
    suspend fun switchPartyWithRecovery(
        targetCustomServer: String,
        targetCode: String,
        nickname: String = nickname(),
    ): SwitchPartyResult = switchMutex.withLock {
        withContext(Dispatchers.IO) {
            val who = identity(nickname)
                ?: return@withContext if (_state.value.inParty) {
                    SwitchPartyResult.TargetFailedRecovered(_state.value.code.orEmpty(), "Sign in to listen together.")
                } else {
                    SwitchPartyResult.TargetFailedNoParty("Sign in to listen together.")
                }

            val targetBase = resolveHttpBase(targetCustomServer)
            if (targetBase.isBlank()) {
                return@withContext if (_state.value.inParty) {
                    SwitchPartyResult.TargetFailedRecovered(_state.value.code.orEmpty(), "Target server address is invalid or missing.")
                } else {
                    SwitchPartyResult.TargetFailedNoParty("Target server address is invalid or missing.")
                }
            }

            val cleanedTargetCode = targetCode.filter { it.isLetterOrDigit() }.uppercase()
            if (cleanedTargetCode.length != CODE_LENGTH) {
                return@withContext if (_state.value.inParty) {
                    SwitchPartyResult.TargetFailedRecovered(_state.value.code.orEmpty(), "A party code is six letters or digits.")
                } else {
                    SwitchPartyResult.TargetFailedNoParty("A party code is six letters or digits.")
                }
            }

            val oldServerBase = activePartyServerBase ?: resolveHttpBase(_customServer.value)
            val oldCode = _state.value.code
            val oldToken = token

            // Step 1: Join Target First while old party stays connected & playing
            val targetJoinResult = runCatching {
                post(
                    "$targetBase/api/parties/$cleanedTargetCode/join",
                    JoinRequest(who.userId, who.deviceId, who.name, who.avatar),
                )
            }

            val membership = targetJoinResult.getOrElse { failure ->
                Log.w(TAG, "failed to join target party: ${redact(failure.message)}")
                val errorMsg = failure.displayMessage()
                return@withContext if (oldCode != null) {
                    SwitchPartyResult.TargetFailedRecovered(oldCode, errorMsg)
                } else {
                    SwitchPartyResult.TargetFailedNoParty(errorMsg)
                }
            }

            // Step 2: Target join succeeded! Commit switch & leave old party inside NonCancellable
            withContext(NonCancellable) {
                // Asynchronously release old party slot on old server
                if (!oldCode.isNullOrBlank() && !oldToken.isNullOrBlank() &&
                    (oldServerBase != targetBase || !oldCode.equals(membership.code, ignoreCase = true))
                ) {
                    releaseStaleSlotOnServer(oldServerBase, oldCode, oldToken)
                }

                // Stop old socket
                socketJob?.cancel()
                socketJob = null
                session = null

                // Commit active party server authority
                activePartyServerBase = targetBase

                // Commit new server URL setting
                setCustomServerUrl(targetCustomServer)

                // Commit new party session
                token = membership.token
                prefs.edit()
                    .putString(KEY_CODE, membership.code)
                    .putString(KEY_TOKEN, membership.token)
                    .apply()
                clock.reset()
                _state.value = State(
                    code = membership.code,
                    you = membership.you,
                    members = membership.party.members,
                    maxMembers = membership.party.maxMembers,
                    playback = membership.party.playback,
                    connection = Connection.CONNECTING,
                )
            }

            // Outside NonCancellable: establish socket connection
            connect()

            SwitchPartyResult.Success(membership.code)
        }
    }

    /** Give up this device's slot. The party carries on without it. */
    suspend fun leaveParty() = switchMutex.withLock {
        withContext(Dispatchers.IO) {
            val code = _state.value.code
            val held = token
            val currentServer = activePartyServerBase ?: resolveHttpBase(effectiveIdleServerBase())
            socketJob?.cancel()
            socketJob = null
            session = null
            clock.reset()
            token = null
            activePartyServerBase = null
            prefs.edit().remove(KEY_CODE).remove(KEY_TOKEN).apply()
            _state.value = State()
            if (code != null && held != null) {
                releaseStaleSlotOnServer(currentServer, code, held)
            }
        }
    }

    // ------------------------------------------------------------ controls --
    //
    // Any member may send any of these. There is no host privilege in this
    // feature, on either side of the wire.

    fun play(positionMs: Long? = null) = control("play") { positionMs?.let { put("positionMs", it) } }

    fun pause(positionMs: Long? = null) = control("pause") { positionMs?.let { put("positionMs", it) } }

    fun seek(positionMs: Long) = control("seek") { put("positionMs", positionMs) }

    fun next() = control("next") {}

    fun previous() = control("previous") {}

    fun setTrack(track: PartyTrack, positionMs: Long = 0, isPlaying: Boolean = true) =
        control("setTrack") {
            put("track", json.encodeToJsonElement(PartyTrack.serializer(), track))
            put("positionMs", positionMs)
            put("isPlaying", isPlaying)
        }

    fun setQueue(queue: List<PartyTrack>, index: Int) = control("setQueue") {
        put("queue", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(PartyTrack.serializer()), queue))
        put("queueIndex", index)
    }

    fun queueAdd(tracks: List<PartyTrack>, playNext: Boolean = false) = control("queueAdd") {
        put("tracks", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(PartyTrack.serializer()), tracks))
        put("playNext", playNext)
    }

    fun queueRemove(videoId: String) = control("queueRemove") {
        put("videoId", videoId)
    }

    fun queueClear() = control("queueClear") {}

    fun queueMove(fromIndex: Int, toIndex: Int, videoId: String? = null) = control("queueMove") {
        put("fromIndex", fromIndex)
        put("toIndex", toIndex)
        if (videoId != null) put("videoId", videoId)
    }

    fun setMaxMembers(value: Int) = control("setMaxMembers") { put("maxMembers", value) }

    fun kick(memberId: String) = control("kick") { put("memberId", memberId) }

    fun setAutoplay(enabled: Boolean) = control("setAutoplay") { put("enabled", enabled) }

    private fun control(action: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        val frame = buildJsonObject {
            put("type", "control")
            put("action", action)
            body()
        }
        send(frame)
    }

    private fun send(frame: JsonObject) {
        val live = session ?: return
        scope.launch {
            runCatching { live.send(Frame.Text(frame.toString())) }
                .onFailure { Log.w(TAG, "control not sent: ${redact(it.message)}") }
        }
    }

    // --------------------------------------------------------- the playhead --

    /**
     * Where this device should be in the current track, right now.
     *
     * The one number the player layer will need, and the reason the rest of
     * this class exists. Null when there is no party, or nothing playing in it.
     *
     * Before the first pong lands there is no offset to convert with, and this
     * falls back to the server's own reading at the moment it sent the frame —
     * which is right to within the age of that frame, and wrong by exactly the
     * amount the clock sync exists to remove. So it is a starting point, not a
     * thing to seek to: wait for [State.clockSynced] before acting on it.
     */
    fun partyPositionMs(): Long? {
        val playback = _state.value.playback
        playback.track ?: return null
        if (!playback.isPlaying) return playback.positionMs
        val serverNow = clock.serverNowMs() ?: return playback.effectivePositionMs
        val elapsed = (serverNow - playback.anchorMs).coerceAtLeast(0)
        val position = playback.positionMs + elapsed
        val duration = playback.track.durationMs
        return if (duration != null) minOf(position, duration) else position
    }

    /**
     * How far in the future the party's next resume is scheduled, or 0 if it is
     * already under way. The player layer waits this out rather than starting
     * early and seeking.
     */
    fun msUntilStart(): Long {
        val playback = _state.value.playback
        if (!playback.isPlaying) return 0
        val serverNow = clock.serverNowMs() ?: return 0
        return (playback.anchorMs - serverNow).coerceAtLeast(0)
    }

    // ------------------------------------------------------------- socket --

    private fun connect() {
        socketJob?.cancel()
        socketJob = scope.launch { runSocketLoop() }
    }

    /**
     * Connect, pump frames until the connection goes away for any reason, back
     * off, go round again.
     *
     * The loop is the design, not error handling bolted onto one. A socket held
     * by a backgrounded music app will be dropped repeatedly over a listening
     * session — the radio sleeping, a Wi-Fi handover, a captive portal, the
     * server's free instance spinning down — and every one of those has to heal
     * without anyone reopening the app.
     */
    private suspend fun runSocketLoop() {
        var backoffMs = 1_000L
        while (currentScopeActive()) {
            val code = _state.value.code ?: return
            val held = token ?: return
            try {
                _state.update { it.copy(connection = Connection.CONNECTING) }
                http.webSocket(
                    urlString = "${wsBase()}/ws/parties/$code",
                    request = { header("Authorization", "Bearer $held") },
                ) {
                    session = this
                    backoffMs = 1_000L
                    _state.update { it.copy(connection = Connection.LIVE, error = null) }
                    launch { pingLoop() }
                    launch { reportLoop() }
                    for (frame in incoming) {
                        if (frame is Frame.Text) onFrame(frame.readText())
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "party socket dropped: ${redact(failure.message)}")
            } finally {
                session = null
            }
            if (!currentScopeActive()) return
            _state.update { it.copy(connection = Connection.CONNECTING) }
            // The clock is not carried across a gap. Whatever the offset was
            // before the socket died, the only honest thing to say afterwards is
            // that it has not been measured since.
            clock.reset()
            _state.update { it.copy(clockSynced = false) }
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(20_000L)
        }
    }

    private suspend fun currentScopeActive(): Boolean =
        kotlinx.coroutines.currentCoroutineContext().isActive

    /**
     * A burst on arrival, then a slow trickle.
     *
     * The burst is what makes the first seconds of a party accurate: the best of
     * several quick round trips is a far better offset than the first one, and
     * the first one is what the playhead would otherwise be built on. After
     * that the offset only has to keep up with clock drift, which on a phone is
     * milliseconds per minute.
     */
    private suspend fun DefaultClientWebSocketSession.pingLoop() {
        repeat(4) {
            ping()
            delay(300)
        }
        while (true) {
            delay(PING_INTERVAL_MS)
            ping()
        }
    }

    private suspend fun DefaultClientWebSocketSession.ping() {
        val sentAt = ServerClock.localNowMs()
        val frame = buildJsonObject {
            put("type", "ping")
            put("clientMs", sentAt)
        }
        runCatching { send(Frame.Text(frame.toString())) }
    }

    /**
     * Tells the server where this device actually is.
     *
     * Nothing is decided by it — the server's state is the truth, and a party
     * averaged towards its slowest member would be a party that drags. It keeps
     * the membership alive and makes real drift visible in the server log,
     * which is the only place the two halves of this feature can be compared.
     */
    private suspend fun DefaultClientWebSocketSession.reportLoop() {
        while (true) {
            delay(REPORT_INTERVAL_MS)
            val position = partyPositionMs() ?: continue
            val frame = buildJsonObject {
                put("type", "report")
                put("positionMs", position)
                put("isPlaying", _state.value.playback.isPlaying)
            }
            runCatching { send(Frame.Text(frame.toString())) }
        }
    }

    private fun onFrame(text: String) {
        val received = ServerClock.localNowMs()
        val frame = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (frame["type"]?.jsonPrimitive?.content) {
            "welcome" -> {
                val party = frame["party"]?.let {
                    runCatching { json.decodeFromJsonElement(PartySnapshot.serializer(), it) }.getOrNull()
                } ?: return
                val you = frame["you"]?.let {
                    runCatching { json.decodeFromJsonElement(PartyMember.serializer(), it) }.getOrNull()
                }
                _state.update { it.copy(
                    code = party.code,
                    you = you ?: it.you,
                    members = party.members,
                    maxMembers = party.maxMembers,
                    playback = party.playback,
                    // A snapshot is the one message that carries the queue
                    // unconditionally — a device that has just arrived has no
                    // other way to learn it.
                    queue = party.queue,
                    connection = Connection.LIVE,
                    error = null,
                ) }
            }

            "pong" -> {
                val sentAt = frame["clientMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: return
                val serverMs = frame["serverMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: return
                clock.record(sentAt, serverMs, received)
                _state.update { it.copy(
                    clockSynced = clock.synced,
                    roundTripMs = clock.roundTripMs,
                ) }
            }

            "state" -> {
                val playback = frame["playback"]?.let {
                    runCatching { json.decodeFromJsonElement(PartyPlayback.serializer(), it) }.getOrNull()
                } ?: return
                // Older than what is already applied, so it says nothing. This
                // is the guard that keeps two simultaneous controllers from
                // sending each other backwards.
                if (playback.seq < _state.value.playback.seq) return
                _state.update { it.copy(playback = playback) }
                // The queue does not ride along with the state — only its
                // sequence number does. Disagreeing with the copy held here
                // means a queue frame was missed, which the heartbeat therefore
                // heals within a few seconds rather than leaving the running
                // order wrong until somebody happens to change it.
                if (playback.queueSeq != _state.value.queue.seq) {
                    send(buildJsonObject { put("type", "syncQueue") })
                }
            }

            "queue" -> {
                val queue = frame["queue"]?.let {
                    runCatching { json.decodeFromJsonElement(PartyQueue.serializer(), it) }.getOrNull()
                } ?: return
                if (queue.seq < _state.value.queue.seq) return
                _state.update { it.copy(queue = queue) }
            }

            "members" -> {
                val members = frame["members"]?.let {
                    runCatching {
                        json.decodeFromJsonElement(
                            kotlinx.serialization.builtins.ListSerializer(PartyMember.serializer()),
                            it,
                        )
                    }.getOrNull()
                } ?: return
                val maxMembers = frame["maxMembers"]?.jsonPrimitive?.content?.toIntOrNull() ?: _state.value.maxMembers
                _state.update { it.copy(members = members, maxMembers = maxMembers) }
            }

            "activity" -> {
                val action = frame["action"]?.jsonPrimitive?.content ?: return
                val by = frame["by"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return
                val atMs = frame["atMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: received
                val detail = frame["detail"]?.jsonPrimitive?.content.orEmpty()
                recordActivity(PartyActivity(action, by, atMs, detail))
            }

            "error" -> {
                val reason = frame["error"]?.jsonPrimitive?.content
                val message = frame["message"]?.jsonPrimitive?.content
                Log.w(TAG, "party server refused a frame: $reason ${redact(message)}")
                _state.update { it.copy(error = message) }
                // These two are terminal, and the reconnect loop cannot learn
                // that on its own — it would keep dialling a party that no
                // longer knows this device for as long as the app is open. The
                // ordinary way to reach here is the server having restarted,
                // which on a free instance is every idle spin-down.
                if (reason == "bad_token" || reason == "no_such_party") {
                    scope.launch { leaveParty() }
                }
            }

            "bye" -> {
                // The server has let this membership go — the slot was swept, or
                // the party ended. Reconnecting would be answered with 4401
                // forever, so the loop is stopped rather than left spinning.
                scope.launch { leaveParty() }
            }
        }
    }

    // ------------------------------------------------------------- plumbing --

    private data class Identity(
        val userId: String,
        val deviceId: String,
        val name: String,
        val avatar: String?,
    )

    /**
     * Who this device is jamming as, or null if it cannot.
     *
     * [AuthStore.isSignedIn][com.music.bitchord.auth.AuthStore.isSignedIn]
     * rather than "is there a cookie": a jar with no signable secret in it is a
     * session the app is already treating as signed out everywhere else, and a
     * party is not the place to start disagreeing with that.
     *
     * [userId] is a hash of the account and profile ids, not either of them. It
     * only has to be stable and comparable — the server never needs to know
     * which Google account it stands for, and a party server that accumulated
     * real account identifiers would be holding something it has no use for.
     */
    private fun identity(nickname: String = nickname()): Identity? {
        val store = BitChordApplication.authStore
        if (!store.isSignedIn) return null
        val account = store.activeSession ?: return null
        val profile = account.profiles.firstOrNull { it.profileId == account.activeProfileId }
            ?: account.profiles.firstOrNull()
        val name = nickname.trim().takeIf { it.isNotBlank() }
            ?: profile?.name?.takeIf { it.isNotBlank() }
            ?: account.name.takeIf { it.isNotBlank() }
            ?: account.email.substringBefore('@').takeIf { it.isNotBlank() }
            ?: return null
        return Identity(
            userId = sha256("${account.accountId}:${profile?.profileId.orEmpty()}").take(32),
            deviceId = deviceId(),
            name = name,
            avatar = profile?.avatar?.takeIf { it.startsWith("http") },
        )
    }

    /**
     * This install's identity to the party, independent of who is signed in.
     *
     * The server counts devices, not people, and uses this to tell a rejoin
     * from a sixth device — so it has to survive a sign-out, an account switch
     * and a process death, and must not survive an uninstall.
     */
    private fun deviceId(): String {
        prefs.getString(KEY_DEVICE, null)?.let { return it }
        return UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE, it).apply()
        }
    }

    private fun recordActivity(entry: PartyActivity) {
        val next = (listOf(entry) + _activity.value).take(100)
        _activity.value = next
    }

    private suspend fun post(url: String, body: JoinRequest): PartyMembership {
        val response = http.post(url) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess()) throw response.toPartyException()
        return response.body()
    }

    private suspend fun HttpResponse.toPartyException(): PartyException {
        val body = runCatching { bodyAsText() }.getOrDefault("")
        val parsed = runCatching { json.decodeFromString(ApiError.serializer(), body) }.getOrNull()
        return PartyException(
            code = parsed?.code.orEmpty().ifBlank { "http_${status.value}" },
            message = parsed?.message?.takeIf { it.isNotBlank() }
                // 422 is the server rejecting an identity, which here can only
                // mean the account layer handed over something blank.
                ?: if (status.value == 422) "This account can't be used to jam."
                else "The party server said ${status.value}.",
            statusCode = status.value,
        )
    }

    fun normalizeServerBase(value: String): String =
        value.trim().trimEnd('/')

    /**
     * Resolves the given custom server (or default server if blank) to a fully qualified HTTP base URL.
     */
    private fun resolveHttpBase(server: String): String {
        val raw = normalizeServerBase(server).ifBlank { defaultServer }
        if (raw.isBlank()) return ""
        return if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
    }

    /**
     * A message with every server address taken out of it.
     *
     * Failures from the HTTP and WebSocket layers name the host they were
     * talking to — that is what they are for — and those messages end up in two
     * places that must not carry it: logcat, and the error line on the Listen
     * Together screen. So nothing from below this class reaches either without
     * passing through here.
     */
    private fun redact(text: String?): String {
        var out = text.orEmpty()
        if (out.isEmpty()) return out
        listOfNotNull(defaultServer, _customServer.value, activePartyServerBase)
            .filter { it.isNotBlank() }
            .flatMap { listOf(it, it.substringAfter("://")) }
            .sortedByDescending(String::length)
            .forEach { out = out.replace(it, SERVER_PLACEHOLDER, ignoreCase = true) }
        return out.replace(ABSOLUTE_URL, SERVER_PLACEHOLDER)
    }

    /**
     * What the listener is told when the transport fails.
     */
    private fun Throwable.displayMessage(): String = when (this) {
        is PartyException -> message ?: UNREACHABLE
        else -> UNREACHABLE
    }

    private fun wsBase(): String {
        val base = activePartyServerBase ?: effectiveIdleServerBase()
        return resolveHttpBase(base)
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    const val CODE_LENGTH = 6

    private const val TAG = "ListenTogether"
    private const val PREFS = "bitchord_listen_together"
    private const val KEY_SERVER = "server_url"

    /** What a redacted address reads as. Not a hostname, so it cannot be resolved back. */
    private const val SERVER_PLACEHOLDER = "<party server>"

    private const val UNREACHABLE = "Couldn’t reach the party server."

    private val ABSOLUTE_URL = Regex(""" (?:https?|wss?)://[^\s,;)\]}'\"]+""", RegexOption.IGNORE_CASE)
    private const val KEY_CODE = "party_code"
    private const val KEY_TOKEN = "party_token"
    private const val KEY_DEVICE = "device_id"
    private const val KEY_NICKNAME = "party_nickname"

    private const val PING_INTERVAL_MS = 15_000L
    private const val REPORT_INTERVAL_MS = 10_000L
    private const val HEALTH_TIMEOUT_MS = 45_000L
}
