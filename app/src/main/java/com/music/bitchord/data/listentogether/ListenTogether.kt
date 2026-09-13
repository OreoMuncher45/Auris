package com.music.bitchord.data.listentogether

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.BitChordApplication
import com.music.bitchord.data.DebugLog as Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
 * ought to be, right now, on its own clock. Binding that to Media3 is the next
 * piece of work and is deliberately not done here, because everything above
 * that line is testable and everything below it is not.
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
    class PartyException(val code: String, message: String) : Exception(message)

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
        install(WebSockets)
        // Off, so a 409 "party full" can be read out of the body and shown as
        // itself rather than arriving as a transport exception.
        expectSuccess = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clock = ServerClock()

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _serverUrl = MutableStateFlow("")
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private lateinit var prefs: SharedPreferences
    private var token: String? = null

    @Volatile
    private var session: DefaultClientWebSocketSession? = null
    private var socketJob: Job? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _serverUrl.value = prefs.getString(KEY_SERVER, DEFAULT_SERVER).orEmpty()
        // A membership survives the process. The socket does not, and neither
        // does the clock offset — both are re-established on the next connect,
        // which is cheap and is the only way to be sure they are current.
        val code = prefs.getString(KEY_CODE, null)
        val saved = prefs.getString(KEY_TOKEN, null)
        if (!code.isNullOrBlank() && !saved.isNullOrBlank()) {
            token = saved
            _state.value = State(code = code)
        }
    }

    fun setServerUrl(value: String) {
        val cleaned = value.trim()
        _serverUrl.value = cleaned
        prefs.edit().putString(KEY_SERVER, cleaned).apply()
    }

    /** Whether this device has an account it can jam as. */
    fun canJoin(): Boolean = identity() != null

    /**
     * Opens the socket for a membership that was restored from storage.
     *
     * Called by the screen rather than from [init], deliberately: a party this
     * device is a member of is worth reconnecting to when somebody is looking
     * at it or listening with it, and is not worth holding a socket open for on
     * every cold start of the app.
     */
    fun ensureConnected() {
        if (_state.value.code == null || token == null) return
        if (socketJob?.isActive == true) return
        connect()
    }

    // ------------------------------------------------------------ joining --

    suspend fun createParty(): Result<String> = enter { who ->
        post("${httpBase()}/api/parties", JoinRequest(who.userId, who.deviceId, who.name, who.avatar))
    }

    suspend fun joinParty(code: String): Result<String> = enter { who ->
        val cleaned = code.filter { it.isLetterOrDigit() }.uppercase()
        if (cleaned.length != CODE_LENGTH) {
            throw PartyException("bad_code", "A party code is six letters or digits.")
        }
        post("${httpBase()}/api/parties/$cleaned/join", JoinRequest(who.userId, who.deviceId, who.name, who.avatar))
    }

    private suspend fun enter(request: suspend (Identity) -> PartyMembership): Result<String> =
        withContext(Dispatchers.IO) {
            val who = identity()
                ?: return@withContext Result.failure(
                    PartyException("not_signed_in", "Sign in to listen together."),
                )
            if (httpBase().isBlank()) {
                return@withContext Result.failure(
                    PartyException("no_server", "Set the party server address first."),
                )
            }
            runCatching { request(who) }
                .onSuccess { membership ->
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
                    connect()
                }
                .onFailure { failure ->
                    Log.w(TAG, "could not enter a party: ${failure.message}")
                    _state.update { it.copy(error = failure.message) }
                }
                .map { it.code }
        }

    /** Give up this device's slot. The party carries on without it. */
    suspend fun leaveParty() = withContext(Dispatchers.IO) {
        val code = _state.value.code
        val held = token
        socketJob?.cancel()
        socketJob = null
        session = null
        clock.reset()
        token = null
        prefs.edit().remove(KEY_CODE).remove(KEY_TOKEN).apply()
        _state.value = State()
        if (code != null && held != null) {
            // Best effort, and after the local state is already clear: a leave
            // that fails must not strand this device in a party its own screen
            // says it has left. The server's disconnect grace collects the slot
            // either way.
            runCatching {
                http.post("${httpBase()}/api/parties/$code/leave") {
                    header("Authorization", "Bearer $held")
                }
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
                .onFailure { Log.w(TAG, "control not sent: ${it.message}") }
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
                http.webSocket("${wsBase()}/ws/parties/$code?token=$held") {
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
                Log.w(TAG, "party socket dropped: ${failure.message}")
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
                _state.update { it.copy(members = members) }
            }

            "error" -> {
                val reason = frame["error"]?.jsonPrimitive?.content
                val message = frame["message"]?.jsonPrimitive?.content
                Log.w(TAG, "party server refused a frame: $reason $message")
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
    private fun identity(): Identity? {
        val store = BitChordApplication.authStore
        if (!store.isSignedIn) return null
        val account = store.activeSession ?: return null
        val profile = account.profiles.firstOrNull { it.profileId == account.activeProfileId }
            ?: account.profiles.firstOrNull()
        val name = profile?.name?.takeIf { it.isNotBlank() }
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
        )
    }

    /** Trims a pasted address down to a scheme and a host the client can use. */
    private fun httpBase(): String {
        val raw = _serverUrl.value.trim().trimEnd('/')
        if (raw.isBlank()) return ""
        return if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
    }

    private fun wsBase(): String = httpBase()
        .replaceFirst("https://", "wss://")
        .replaceFirst("http://", "ws://")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    const val CODE_LENGTH = 6

    private const val TAG = "ListenTogether"
    private const val PREFS = "bitchord_listen_together"
    private const val KEY_SERVER = "server_url"
    private const val KEY_CODE = "party_code"
    private const val KEY_TOKEN = "party_token"
    private const val KEY_DEVICE = "device_id"
    private const val DEFAULT_SERVER = ""
    private const val PING_INTERVAL_MS = 15_000L
    private const val REPORT_INTERVAL_MS = 10_000L
}
