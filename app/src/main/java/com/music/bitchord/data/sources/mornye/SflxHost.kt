package com.music.bitchord.data.sources.mornye

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.module.ModuleSearchResponse
import com.music.bitchord.data.sources.module.ModuleSearchResult
import com.music.bitchord.data.sources.module.ModuleStreamResponse
import com.music.bitchord.data.sources.module.QuickJsExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Runs installed `.sflx` extensions and answers in this app's own module
 * vocabulary.
 *
 * An extension is not a module — see [SflxShim] for how that gap is closed on
 * the JavaScript side — but from here it is asked the same two questions as one
 * (`searchTracks`, `getTrackStreamUrl`) and answers in the same JSON shapes. The
 * point of that is how much does *not* have to exist: the resolver's ranking,
 * the row tiers, the format and Atmos gating in [ModuleSource], the download
 * queue and the sources screen all keep working on an extension because it
 * arrives in the shape they already handle.
 *
 * ## Two things this is careful about
 *
 * **The manifest's network list is a permission, not a comment.** It is handed
 * to the sandbox, which refuses a fetch to anything else. A package that lists
 * `api.example.com` cannot read `accounts.example.com`, and an install that
 * turns out to want more than it declared fails visibly instead of quietly
 * succeeding.
 *
 * **A stream is captured, not downloaded.** The extension resolves the audio —
 * that part is its own business and it does it well — and the URL it was about
 * to write to disk is taken instead. See [SflxShim.ADAPTER].
 */
class SflxHost(
    private val store: SflxStore,
    /** This app's version, for the `minAppVersion` check and for `appVersion()`. */
    private val appVersion: String,
    /**
     * Where signed-session records live — `<filesDir>/signed_sessions`.
     *
     * Null disables signed sessions outright, which is what a test harness or a
     * preview gets: a session record is a credential, and one written by a run
     * that was never a real install is a credential nobody asked for.
     */
    private val signedSessionsDirectory: File? = null,
) {

    /** One signed-session client per extension, made on first use. */
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, SflxSignedSession>()

    /**
     * Whether this extension's source row is switched on.
     *
     * Checked here, at the only places an extension is ever run, because the
     * surfaces that ask — playback, the upgrade walk, a download — each build
     * their own candidate list, and one of them asking a switched-off source is
     * a switch that does not work. A switched-off extension is not loaded, not
     * searched, and cannot raise a verification prompt.
     */
    private fun enabled(installed: InstalledSflx): Boolean {
        val configId = SflxCatalog.configIdOf(installed.id) ?: return true
        return SourceRegistry.config(configId)?.enabled ?: true
    }

    /**
     * The signed-session client for [installed], or null when its manifest does
     * not declare one.
     */
    fun sessionFor(installed: InstalledSflx): SflxSignedSession? {
        val directory = signedSessionsDirectory ?: return null
        val config = SignedSessionConfig.from(installed.manifest) ?: return null
        return sessions.computeIfAbsent(installed.id) {
            SflxSignedSession(
                extensionId = installed.id,
                config = config,
                directory = directory,
                onVerificationRequired = { url ->
                    // A switched-off extension must not put a page on screen.
                    if (enabled(installed)) {
                        SflxVerify.open(installed.id, installed.manifest.displayName, url)
                    }
                },
            )
        }
    }

    /** The signed-session state of [id], or null when it has no signed session. */
    fun sessionStatus(id: String): SflxSignedSession.Snapshot? {
        val installed = store.find(id) ?: return null
        return sessionFor(installed)?.snapshot()
    }

    /**
     * What to tell the listener when an extension could not answer because its
     * signed session is not in order, or null when there is nothing to say.
     *
     * The wording is the whole point. "the extension returned no stream URL" is
     * a fact about a process the listener cannot see; "Tidal needs
     * verification — Sources → Tidal → Verify" is a fact about a thing on their
     * screen, and it says where to go. Everything that has to explain a
     * failure to a listener carries this one instead of the raw error.
     *
     * Null when the extension has no session at all, and null when its session
     * is in order — the message is only worth saying when it names the thing
     * the listener can actually do about it.
     */
    fun verificationAdvice(extensionId: String): String? {
        val installed = store.find(extensionId) ?: return null
        val snapshot = sessionFor(installed)?.snapshot() ?: return null
        if (snapshot.authenticated && !snapshot.verificationRequired) return null
        val name = installed.manifest.displayName.ifBlank { extensionId }
        return verificationMessage(name)
    }

    /**
     * Every installed extension's signed-session state, keyed by id.
     *
     * Only extensions that declare a `signedSession` appear: a row with no
     * status at all is a row that has nothing to verify, and an empty badge on
     * every extension that never asked for a session would be noise.
     */
    fun sessionStatuses(): Map<String, SflxSignedSession.Snapshot> =
        store.list().mapNotNull { installed ->
            sessionFor(installed)?.let { installed.id to it.snapshot() }
        }.toMap()

    /**
     * Starts a verification for [id] and returns the URL the page was opened on.
     *
     * The bootstrap that produces the challenge is a network call, so this is
     * meant to be called off the main thread — see the Sources screen, which
     * runs it in its own scope.
     */
    fun beginVerification(id: String): String? {
        val installed = store.find(id) ?: return null
        if (!enabled(installed)) return null
        val session = sessionFor(installed) ?: return null
        val url = runCatching { session.requestVerification() }.getOrNull() ?: return null
        if (url.isNotBlank()) {
            SflxVerify.open(installed.id, installed.manifest.displayName, url)
        }
        SflxCatalog.refreshStatuses()
        return url
    }

    /** Forgets [id]'s stored session; the next request bootstraps again. */
    fun clearSession(id: String) {
        val installed = store.find(id) ?: return
        runCatching { sessionFor(installed)?.clear() }
        SflxCatalog.refreshStatuses()
    }

    /**
     * Redeems a grant that arrived as a deep link, without knowing which
     * extension it belongs to.
     *
     * That is the shape a callback really arrives in: the verification page
     * redirects to `mornye://session-grant?grant=…&state=…`, the system hands
     * the URL to this app, and the URL names the challenge — not the package.
     * The `state` is what identifies the extension, so it is matched against
     * the challenge this host issued. When there is no state to match on and
     * exactly one extension is waiting, that one is it, because a grant that
     * arrives while one challenge is outstanding cannot belong to another.
     */
    fun completeGrantForState(grant: String, state: String?): Boolean {
        if (grant.isBlank()) return false
        val waiting = store.list().mapNotNull { installed ->
            sessionFor(installed)?.takeIf { it.pendingState() != null }?.let { installed to it }
        }
        val matched = waiting.filter { (_, session) ->
            state != null && session.pendingState() == state
        }.ifEmpty {
            if (state == null && waiting.size == 1) waiting else emptyList()
        }
        for ((_, session) in matched) {
            val ok = runCatching { session.completeGrant(grant, state ?: session.pendingState()) }
                .getOrDefault(false)
            if (ok) {
                Log.d(TAG, "a verification grant arrived by deep link and was redeemed")
                SflxCatalog.refreshStatuses()
                return true
            }
        }
        Log.w(TAG, "a verification grant arrived but matched no outstanding challenge")
        return false
    }

    /** A URL the listener must visit to verify [id], when one is pending. */
    fun verificationUrl(id: String): String? {
        val installed = store.find(id) ?: return null
        return sessionFor(installed)?.verificationUrl()
    }

    /**
     * Redeems the grant the verification page handed back.
     *
     * @param state the `state` the page echoed; checked against the challenge
     *   this host issued, so a callback for someone else's challenge is ignored
     *   rather than stored as this listener's session.
     */
    fun completeGrant(id: String, grant: String, state: String? = null): Boolean {
        val installed = store.find(id) ?: return false
        val session = sessionFor(installed) ?: return false
        val ok = runCatching { session.completeGrant(grant, state) }.getOrDefault(false)
        Log.d(TAG, "sflx[$id] grant ${if (ok) "accepted" else "refused"}")
        SflxCatalog.refreshStatuses()
        return ok
    }

    /** Every installed extension, in install order. */
    fun installed(): List<InstalledSflx> = store.list()

    /** The extension with [id], or null when nothing of that name is installed. */
    fun installed(id: String): InstalledSflx? = store.find(id)

    /**
     * The rows of one extension's search, mapped into this app's shape.
     *
     * @param limit how many the extension is asked for. The extension is told
     *   the real number rather than over-asked and trimmed here, because the
     *   limit is what makes it choose between its own result pages.
     */
    suspend fun search(extensionId: String, query: String, limit: Int): Result<ModuleSearchResponse> {
        if (query.isBlank()) return Result.success(ModuleSearchResponse())
        val installed = store.find(extensionId)
            ?: return Result.failure(SflxException("'$extensionId' is not installed"))
        if (!enabled(installed)) {
            return Result.failure(SflxException("'${installed.manifest.displayName}' is turned off"))
        }
        val settings = settingsFor(installed)
        val key = searchKey(extensionId, query, limit, settings)

        // The same question, asked twice.
        //
        // A search is not free: the extension runs its own catalogue walk in
        // JavaScript and answers with kilobytes of JSON, and the app asks the
        // same question from several places — the search screen, the row
        // warming, the playback resolve, the upgrade, the next-track
        // preparation. Measured on a device log, one track's worth of looking
        // up ran the extension's search eight times in twelve seconds, and
        // every one of them was the same question with the same answer.
        //
        // So the answer is kept for a moment, and a question asked while
        // another is still running waits for that one rather than starting a
        // second.
        val outcome = searches.once(key) { ask(installed, query, limit, settings) }
        // The rows are remembered on every answer, cached or fresh: the stream
        // call needs the extension's own object for the track, and the bounded
        // map that holds them may have dropped it since the search.
        outcome.onSuccess { it.rows.forEach { row -> remember(extensionId, row) } }
        // A failure caused by a session that is not in order carries the
        // advice instead of the raw error — see [verificationAdvice].
        outcome.exceptionOrNull()?.let { error ->
            val advice = verificationAdvice(installed.id) ?: return@let
            Log.w(TAG, "sflx[$extensionId] $advice (${error.message})")
            return Result.failure(SflxVerificationNeeded(advice))
        }
        return outcome.map { it.response }
    }

    /**
     * One answered search, and the rows it produced.
     */
    private class SearchOutcome(
        val response: ModuleSearchResponse,
        val rows: List<SflxRows.Row>,
    )

    /** The answers this host has already given, and the questions in flight. */
    private val searches = SflxSearchMemo<SearchOutcome>(ttlMs = SEARCH_CACHE_TTL_MS)

    /**
     * The scope the host's own background work runs in.
     *
     * Its own, and not the caller's: a warm-up started for one caller must
     * not be cancelled when that caller goes away, and neither the engine it
     * loads nor the answer it waits for belongs to any one caller.
     */
    private val hostScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The extension's own answer, asked for once. */
    private suspend fun ask(
        installed: InstalledSflx,
        query: String,
        limit: Int,
        settings: JSONObject,
    ): Result<SearchOutcome> {
        load(installed).getOrElse { return Result.failure(it) }
        return QuickJsExecutor.callExport(
            moduleId = moduleIdOf(installed.id),
            functionName = "searchTracks",
            args = listOf(quote(query), limit.toString(), context(settings)),
        ).mapCatching { raw ->
            val parsed = SflxRows.parse(raw)
            SearchOutcome(response = parsed.response, rows = parsed.rows)
        }
    }

    /** What makes two searches the same question. */
    private fun searchKey(extensionId: String, query: String, limit: Int, settings: JSONObject): String {
        // The settings are part of the question: an extension asked for a
        // different quality, or with a different account, answers differently.
        // The keys are sorted because the settings object has no order of its
        // own, and an unordered key would make every search a new one.
        val fingerprint = settings.keys().asSequence().sorted()
            .joinToString(",") { key -> "$key=${settings.opt(key)}" }
        return "$extensionId|${query.trim().lowercase(Locale.ROOT)}|$limit|$fingerprint"
    }

    /**
     * A playable URL for one track, as the extension itself resolves it.
     *
     * The extension is asked to download — with the row the search returned
     * handed back to it as prepared context, so it does not re-derive what it
     * already knew — and the URL its own resolution produces is taken before
     * any bytes move.
     */
    suspend fun streamUrl(
        extensionId: String,
        trackId: String,
        quality: String,
        wantsAtmos: Boolean = false,
    ): Result<ModuleStreamResponse> =
        withContext(Dispatchers.IO) {
            val installed = store.find(extensionId)
                ?: return@withContext Result.failure(SflxException("'$extensionId' is not installed"))
            if (!installed.manifest.isDownloadProvider) {
                return@withContext Result.failure(
                    SflxException("'${installed.manifest.displayName}' describes tracks and cannot serve audio"),
                )
            }
            if (!enabled(installed)) {
                return@withContext Result.failure(SflxException("'${installed.manifest.displayName}' is turned off"))
            }
            load(installed).getOrElse { return@withContext Result.failure(it) }

            val requested = SflxQuality.forTier(installed.manifest, quality, wantsAtmos)
            val prepared = rowFor(extensionId, trackId)
            // The extension is asked for the wanted tier, and then for the ones
            // below it, and the first answer that carries a URL wins.
            //
            // The extension does have a fallback ladder of its own, and it only
            // walks it when the gateway says *why* it refused — a code like
            // QUALITY_UNAVAILABLE. A refusal it cannot read (this gateway
            // answers the Atmos request with a bare 400 and an empty body) is
            // treated as a fault worth retrying, six times, and then reported —
            // so a track with no Atmos mix failed to play at all rather than
            // playing its lossless master, which is the wrong way round.
            val tiers = SflxQuality.fallbacks(installed.manifest, requested)
            var attempt: Result<String> = Result.failure(SflxException("the extension returned no stream URL"))
            var chosenTier = requested
            for (tier in tiers) {
                val answer = QuickJsExecutor.callExport(
                    moduleId = moduleIdOf(extensionId),
                    functionName = "getTrackStreamUrl",
                    args = listOf(quote(trackId), quote(tier), context(settingsFor(installed), prepared, wantsAtmos && tier == requested)),
                )
                // An answer counts as resolved when it carries a URL *or* the
                // parts of one. Tidal answers a segmented start with
                // `streamUrl: null` and the segments beside it, and a reader
                // that only looked for the URL called that a failure — so the
                // ladder kept walking down past a perfectly good Atmos mix,
                // and the parts that were in the same object were never read.
                if (answer.isSuccess && SflxAnswer.carriesStream(answer.getOrThrow())) {
                    attempt = answer
                    chosenTier = tier
                    break
                }
                attempt = answer
                val why = answer.mapCatching { SflxAnswer.error(it) }
                    .getOrNull()
                    .orEmpty()
                    .ifBlank { answer.exceptionOrNull()?.message.orEmpty() }
                    .ifBlank { "the extension returned no stream URL" }
                Log.w(TAG, "sflx[$extensionId] $tier did not resolve '$trackId': $why")
            }
            // The extension could not answer at all. If the reason is that its
            // signed session is not in order, then the raw error is the wrong
            // thing to hand back: it describes a status code the listener
            // cannot act on, when there is a button on their screen that
            // fixes it. The advice replaces it, and every surface that shows
            // the failure shows the advice.
            val answered = attempt.mapCatching { raw ->
                val url = SflxAnswer.streamUrl(raw)
                val headers = SflxAnswer.headers(raw)
                val segments = SflxAnswer.segments(raw)
                if (url == null && segments.isEmpty()) {
                    val why = SflxAnswer.error(raw) ?: "the extension returned no stream URL"
                    throw SflxException(why)
                }
                if (chosenTier != requested) {
                    Log.d(TAG, "sflx[$extensionId] answered $requested with $chosenTier")
                }
                // What the extension said about the audio it resolved. It is a
                // claim, not a measurement — the fields come from the file the
                // extension was about to write, which this host never sees — and
                // it is treated as one everywhere downstream.
                val playable = if (url != null) {
                    url
                } else {
                    // A segmented start: parts, init first. Assembled here into
                    // one file, because the player is handed a URL and a list of
                    // forty CDN links is not one.
                    val assembled = SflxSegments.assemble(
                        directory = cacheDirectory(installed),
                        extensionId = extensionId,
                        key = trackId,
                        segments = segments,
                        headers = headers,
                    ) ?: throw SflxException("the segments could not be fetched")
                    "file://${assembled.absolutePath}"
                }
                ModuleStreamResponse(
                    streamUrl = playable,
                    headers = headers.ifEmpty { null },
                    segments = segments.ifEmpty { null },
                    track = SflxAnswer.track(raw, trackId),
                )
            }
            // The advice, when the failure was a session that is not in order.
            // Asked of the *answer* rather than of the attempt, because the
            // extension reports its own refusal as a successful call carrying
            // an error — the host never sees an exception for it.
            answered.exceptionOrNull()?.let { error ->
                val advice = verificationAdvice(installed.id) ?: return@let
                Log.w(TAG, "sflx[$extensionId] $advice (${error.message})")
                return@withContext Result.failure(SflxVerificationNeeded(advice))
            }
            answered
        }

    /**
     * The lyrics one extension answers with, as the JSON it returned.
     *
     * The extension is asked in the shape the contract established —
     * `fetchLyrics(track, artist, album, durationSeconds)` — and whatever it
     * answers is handed back whole. Parsing it is not this host's business:
     * the words, the timings and the answering vocal are the extension's, and
     * the parser that reads them lives with the rest of the lyrics code.
     *
     * An extension that cannot answer says so with nothing at all, which is
     * a miss rather than a failure: the lyrics lookup carries on with the
     * sources behind this one.
     */
    suspend fun fetchLyrics(
        extensionId: String,
        title: String,
        artist: String,
        album: String,
        durationSeconds: Double,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            val installed = store.find(extensionId)
                ?: return@withContext Result.failure(SflxException("'$extensionId' is not installed"))
            if (!installed.manifest.isLyricsProvider) {
                return@withContext Result.failure(
                    SflxException("'${installed.manifest.displayName}' cannot answer lyrics"),
                )
            }
            if (!enabled(installed)) {
                return@withContext Result.failure(SflxException("'${installed.manifest.displayName}' is turned off"))
            }
            load(installed).getOrElse { return@withContext Result.failure(it) }

            val answer = QuickJsExecutor.callExport(
                moduleId = moduleIdOf(extensionId),
                functionName = "fetchLyrics",
                args = listOf(
                    quote(title),
                    quote(artist),
                    quote(album),
                    durationSeconds.toString(),
                ),
            )
            // The sandbox hands back whatever the export returned, JSON-encoded.
            // "null" is the extension saying it has nothing for this track.
            answer.mapCatching { raw ->
                val text = raw.trim()
                if (text.isEmpty() || text == "null") throw SflxException("no lyrics")
                // An extension that cannot answer says so with an `error` of
                // its own. Read the way every other string here is read: a JSON
                // null in that field is not the word "null", it is the absence
                // of a reason, and treating it as the word threw a failure
                // whose message was four letters long.
                SflxAnswer.error(text)?.let { why ->
                    throw SflxException(why)
                }
                text
            }
        }

    /**
     * Loads [installed] into the sandbox, if it is not already there.
     *
     * The extension's own script, the host globals it expects, and the adapter
     * that turns its registration into an export — evaluated once per engine
     * and then reused for every call, because a load is a token fetch and a
     * client id handshake, and paying for that per search is the difference
     * between a source that feels instant and one that does not.
     *
     * The settings are read at load time rather than passed per call: an
     * extension reads its own config once in `initialize()` and holds it, and a
     * settings change is applied by unloading the extension — see [reload].
     */
    private suspend fun load(installed: InstalledSflx): Result<Unit> {
        val moduleId = moduleIdOf(installed.id)
        if (QuickJsExecutor.isLoaded(moduleId)) return Result.success(Unit)
        // One load per extension, however many callers want one.
        //
        // A load is a token fetch and the extension's own `initialize()`, and
        // both are expensive: two of them at once is two token fetches, two
        // handshakes and two engines, and the second engine replaces the first
        // in the pool — so the first caller is left holding a script that is no
        // longer the one running.
        //
        // It happened because the warm-up and a search could both arrive
        // before either had finished: the warm-up loads every installed
        // extension at start-up, and the first search arrives whenever the
        // listener gets there. The lock is per extension, so one extension's
        // slow load never holds up another's.
        val lock = loadLocks.getOrPut(moduleId) { Mutex() }
        return lock.withLock {
            // Asked again inside the lock: the caller that was waiting has just
            // been handed the load the first one did.
            if (QuickJsExecutor.isLoaded(moduleId)) return@withLock Result.success(Unit)
            val script = runCatching { installed.scriptFile.readText() }.getOrElse { error ->
                return@withLock Result.failure(
                    SflxException("could not read ${installed.scriptFile.name}: ${error.message}"),
                )
            }
            Log.d(TAG, "sflx load ${installed.id} v${installed.manifest.version} (${script.length} chars)")
            val session = sessionFor(installed)
            QuickJsExecutor.loadModule(
                moduleId = moduleId,
                jsCode = script,
                fetchBase = "",
                acceptRegistered = true,
                preamble = SflxShim.preamble(
                    installed.manifest,
                    settingsFor(installed).toString(),
                    appVersion,
                    cacheDirectory(installed).absolutePath,
                ),
                epilogue = SflxShim.ADAPTER,
                // The manifest's declared hosts, when it declared any. An empty
                // list means "this package said nothing", not "this package may
                // reach nothing": the reference host enforces no per-extension list
                // at all, and reading an absent block as deny-all made every
                // extension that omitted it fail its first API call with "not
                // permitted by this extension's manifest" — which looks exactly
                // like a catalogue refusing, and is why some of them searched but
                // never streamed.
                allowedHosts = installed.manifest.permissions.networkHosts.takeIf { it.isNotEmpty() },
                sessionCall = session?.let { client -> { method, args -> client.sessionCall(method, args) } },
            ).onFailure { Log.w(TAG, "sflx load failed for ${installed.id}: ${it.message}") }
        }
    }

    /** One lock per extension, so two callers cannot load the same package twice. */
    private val loadLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    /**
     * Forgets the loaded engines of [extensionId].
     *
     * Called when its settings change or when it is reinstalled, because both
     * mean the script in memory no longer matches what is on disk: a new
     * quality or a new account is read by `initialize()`, and that only runs
     * at load.
     *
     * The searches it answered go with it. They were the old script's
     * answers, and a reinstall or a changed setting can change what the
     * extension returns for the same question.
     */
    fun unload(extensionId: String) {
        QuickJsExecutor.unload(moduleIdOf(extensionId))
        forgetSearches(extensionId)
    }

    /** Drops every answer this extension gave. */
    private fun forgetSearches(extensionId: String) {
        searches.forgetMatching("$extensionId|")
    }

    /**
     * Loads every installed extension's engine, in the background.
     *
     * A load is a token fetch and a client handshake — the extension's own
     * `initialize()` — and paying for it on the first search is what makes the
     * first track of a session slow. Doing it here takes that cost off the
     * critical path: by the time a track is played, the engine is already
     * there and the search is the only thing left to wait for.
     *
     * Only the extensions that serve audio. A metadata-only package has no
     * stream to warm, and loading it costs a developer-token scrape for
     * nothing.
     *
     * Never throws: a package that cannot load is reported by the search that
     * needs it, and a start-up that failed on one extension's handshake would
     * take every other source down with it.
     */
    fun warmUp() {
        hostScope.launch {
            store.list().forEach { installed ->
                if (!enabled(installed)) return@forEach
                if (!installed.manifest.isDownloadProvider) return@forEach
                load(installed)
            }
        }
    }

    /**
     * Where this extension may be told to write, and where a segmented start is
     * assembled.
     *
     * Inside the extension's own directory, so removing the package removes
     * everything it caused to exist — a preview, a half-fetched song, the file
     * a segmented track was assembled into. It is never read by the extension:
     * the download call is intercepted, and the path is only there because a
     * downloader asks for one.
     */
    private fun cacheDirectory(installed: InstalledSflx): File =
        File(installed.directory, "cache").apply { if (!isDirectory) mkdirs() }

    /** Reinstalls from a package held in memory, digest-checked when one is given. */
    fun install(bytes: ByteArray, expectedSha256: String?, sourceUrl: String): Result<InstalledSflx> =
        runCatching {
            val installed = store.install(bytes, expectedSha256, sourceUrl)
            // A reinstall of a running extension has to be a different object
            // in the sandbox, or the old script keeps answering.
            unload(installed.id)
            installed
        }.onFailure { Log.w(TAG, "sflx install failed: ${it.message}") }

    fun remove(id: String): Boolean {
        val removed = store.remove(id)
        if (removed) unload(id)
        return removed
    }

    /** The listener's own answers for [id]'s settings block, as JSON text. */
    fun settingsJson(id: String): String = store.settings(id)?.toString() ?: "{}"

    /**
     * Replaces the listener's answers for [id], and reloads the extension.
     *
     * The reload is the point. An extension reads its settings once, in
     * `initialize()`, and holds them — so a new quality tier saved without
     * unloading the engines would keep being served at the old one until the
     * process happened to restart, which reads as a setting that does not work.
     */
    fun saveSettings(id: String, json: String) {
        val values = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject
        }.getOrNull() ?: return
        store.saveSettings(id, values)
        unload(id)
    }

    /**
     * The extension's settings as it will receive them: the manifest's own
     * defaults, with the listener's answers laid over the top.
     *
     * Defaults first because an extension's `initialize()` reads its config as
     * fact — a missing key is a missing quality tier, not "ask again" — and the
     * listener's answers last because those are the ones that were chosen
     * deliberately.
     */
    private fun settingsFor(installed: InstalledSflx): JSONObject {
        val merged = JSONObject()
        installed.manifest.settings.forEach { setting ->
            val text = (setting.default as? kotlinx.serialization.json.JsonPrimitive)?.content
            if (!text.isNullOrBlank()) merged.put(setting.key, text)
        }
        val chosen = runCatching { JSONObject(settingsJson(installed.id)) }.getOrDefault(JSONObject())
        for (key in chosen.keys()) merged.put(key, chosen.opt(key))
        return merged
    }

    /** The `context` argument the sandbox's exports take, with the row when there is one. */
    private fun context(settings: JSONObject, preparedJson: String? = null, atmos: Boolean = false): String {
        val root = JSONObject().apply {
            // The wrapper is part of the contract: a module reads a setting as
            // `context.settings.<key>.value`.
            val boxed = JSONObject()
            for (key in settings.keys()) {
                boxed.put(key, JSONObject().apply { put("value", settings.opt(key)) })
            }
            put("settings", boxed)
            put("appVersion", appVersion)
            // The module protocol's immersive flag, read by the reference
            // modules and by the extensions that serve a spatial mix.
            put("dolbyAtmos", atmos)
            // The extension's own object for this track, handed back so the
            // download call does not re-derive what its search already knew.
            if (preparedJson != null) put("track", runCatching { JSONObject(preparedJson) }.getOrDefault(JSONObject()))
        }
        return root.toString()
    }

    /** A JavaScript string literal. The id comes from a package, so it is quoted rather than trusted. */
    private fun quote(value: String): String = JSONObject.quote(value)

    private fun moduleIdOf(extensionId: String): String = "$MODULE_PREFIX$extensionId"

    // ── Remembered rows ──────────────────────────────────────────────────

    /**
     * The row a search returned, per extension per track, for the stream call
     * to hand back.
     *
     * Bounded, because this is a cache and not a library: an extension that
     * answers a thousand-row search should not hold a thousand rows for the
     * life of the process. Dropping one costs exactly one extra lookup inside
     * the extension — it re-derives what it needs from the id it was given —
     * and the alternative, growing without limit, is a leak whose size is
     * decided by somebody else's search page size.
     */
    private val rows = LinkedHashMap<String, String>()

    private fun remember(extensionId: String, row: SflxRows.Row) {
        val raw = row.raw?.toString() ?: return
        synchronized(rows) {
            rows["$extensionId|${row.result.id}"] = raw
            while (rows.size > MAX_REMEMBERED_ROWS) {
                val oldest = rows.keys.firstOrNull() ?: break
                rows.remove(oldest)
            }
        }
    }

    private fun rowFor(extensionId: String, trackId: String): String? =
        synchronized(rows) { rows["$extensionId|$trackId"] }

    companion object {
        private const val TAG = "BitChord"

        /**
         * The sentence a listener is given when an extension needs verifying.
         *
         * It names the extension and says where the button is, because that is
         * the two things a message about a broken source has to do: say *which*
         * one is broken, and say what to do about it. "Sources → Tidal →
         * Verify" is the path, because the Sources screen is where extension
         * rows live and where the verification opens.
         *
         * Its own function so the wording can be read, and tested, on its own.
         */
        internal fun verificationMessage(name: String): String =
            "$name needs verification — Sources → $name → Verify"

        /**
         * The sandbox key an extension's engines live under.
         *
         * Prefixed so that it cannot collide with a module index's own ids —
         * both are user-visible strings from different sources, and a
         * collision would have one extension's engines serve another's calls.
         */
        const val MODULE_PREFIX = "sflx:"

        /** How many searched rows are kept to hand back on the stream call. */
        private const val MAX_REMEMBERED_ROWS = 400

        /**
         * How long an answered search is kept.
         *
         * Long enough to cover one track's whole life — the search, the
         * warming, the playback resolve, the upgrade and the next-track
         * preparation all happen inside a couple of minutes of each other —
         * and short enough that a catalogue which changed while the app was
         * open is not answered from the past for the rest of the session.
         */
        private const val SEARCH_CACHE_TTL_MS = 120_000L

        /** The prefix an extension's source config carries, so it is routed here. */
        const val CONFIG_SCHEME = "sflx://"

        /** The source-config URL for an installed extension. */
        fun configUrlFor(extensionId: String): String = "$CONFIG_SCHEME$extensionId"

        /** The extension id in a `sflx://…` config URL, or null for anything else. */
        fun extensionIdOf(configUrl: String): String? =
            configUrl.trim().takeIf { it.startsWith(CONFIG_SCHEME, ignoreCase = true) }
                ?.removePrefix(CONFIG_SCHEME)
                ?.removePrefix("sflx:/")
                ?.substringBefore('/')
                ?.takeIf { it.isNotBlank() }
    }
}

/**
 * Reading an extension's answer into this app's rows.
 *
 * Kept apart from [SflxHost] and free of Android so the mapping can be tested
 * on its own: it is the one place where two vocabularies meet, and every field
 * that goes wrong here goes wrong silently — a missing duration shows as `0:00`,
 * a missing id as a row that cannot be played.
 */
internal object SflxRows {

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * One mapped row, with the extension's original object alongside it.
     *
     * [raw] is not decoration. It is what the extension is handed back on the
     * download call, and it carries everything this app does not model — the
     * extension's own album id, its ISRC, its duration in milliseconds, in the
     * field names the extension itself uses.
     */
    data class Row(val result: ModuleSearchResult, val raw: kotlinx.serialization.json.JsonObject?)

    data class Parsed(val response: ModuleSearchResponse, val rows: List<Row>)

    fun parse(text: String): Parsed {
        val root = runCatching {
            json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject
        }.getOrNull() ?: return Parsed(ModuleSearchResponse(), emptyList())
        val tracks = root["tracks"] as? kotlinx.serialization.json.JsonArray ?: return Parsed(ModuleSearchResponse(), emptyList())

        val mapped = tracks.mapNotNull { element ->
            val track = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val result = runCatching {
                json.decodeFromJsonElement(ModuleSearchResult.serializer(), track)
            }.getOrNull() ?: return@mapNotNull null
            // A row without an id cannot be played, and one that arrives without
            // a name cannot be found again in a list. Neither is dropped
            // silently: they are filled in, because a search that returned
            // something is worth showing even when the extension was sloppy
            // about it.
            if (result.id.isBlank()) return@mapNotNull null
            Row(
                result = result.copy(
                    title = result.title.trim().ifBlank { "Unknown track" },
                    artist = result.artist.trim().ifBlank { "Unknown artist" },
                ),
                raw = track["raw"] as? kotlinx.serialization.json.JsonObject,
            )
        }
        return Parsed(
            response = ModuleSearchResponse(tracks = mapped.map { it.result }, total = mapped.size),
            rows = mapped,
        )
    }
}

/**
 * Translating this app's quality tier into an extension's own.
 *
 * The ids are the extension's vocabulary, not this app's — `HI_RES_LOSSLESS`,
 * `aac_160`, `MP3_320` — and its manifest lists them best-first. So the tier is
 * mapped onto the list the extension published about itself, rather than a
 * fixed table here: an extension that only serves AAC should still be asked for
 * its best AAC when a lossless tier is requested, and told apart by the tier it
 * names on the way back (which [ModuleSource] reads through its own format
 * handling, and may well decide not to play).
 */
internal object SflxQuality {

    /**
     * One of the extension's quality ids, with the kind Mornye infers for it.
     *
     * `kind` is optional in a manifest and several extensions in the wild were
     * published before it existed, so the same id/label inference Mornye uses
     * is applied when it is missing. Descriptions are deliberately left out:
     * they routinely mention fallback formats ("FLAC fallback", "AAC 320"), and
     * inferring from them marks the wrong option as the lossless one.
     */
    private data class Quality(val id: String, val kind: String, val label: String)

    fun forTier(manifest: SflxManifest, tier: String, wantsAtmos: Boolean = false): String {
        val options = optionsOf(manifest)
        if (options.isEmpty()) return tier

        // The kind being asked for, in the extension's own three-way vocabulary.
        val target = when {
            wantsAtmos -> "spatial"
            tier.uppercase(Locale.ROOT) == "LOSSLESS" -> "lossless"
            tier.uppercase(Locale.ROOT) == "HIGH" || tier.uppercase(Locale.ROOT) == "LOW" -> "lossy"
            // Not one of this app's tiers: the caller named an extension id.
            else -> return options.firstOrNull { it.id.equals(tier, ignoreCase = true) }?.id ?: tier
        }

        // The fallback ladder, as Mornye defines it: a spatial request may take
        // a lossless copy, a lossy request may take one, a lossless request may
        // not be answered with lossy audio.
        val allowed = when (target) {
            "spatial", "lossy" -> listOf(target, "lossless")
            else -> listOf("lossless")
        }
        for (kind in allowed) {
            val candidates = options.filter { it.kind == kind }
            val pick = when {
                candidates.isEmpty() -> null
                // A spatial request is answered with the extension's *Atmos*
                // option, and not with the first spatial one it happens to
                // list. The two are different files and only one of them is
                // the immersive mix: Amazon publishes `eac3` ("Dolby Digital
                // Plus") before `ac4` ("Dolby Atmos (AC-4)"), so taking the
                // first spatial option answered an Atmos request with a
                // surround stream the device may not even decode, and the
                // AC-4 master was never asked for at all.
                //
                // The rule is Mornye's own — see `is_atmos_option` in
                // `mornye/extensions/quality_options.py`: the canonical id
                // `DOLBY_ATMOS`, or a spatial option whose label says
                // "atmos". A spatial option that says neither is surround,
                // not Atmos, and an extension with no Atmos option at all
                // falls through to the lossless rung below — which is what
                // Mornye does with the same manifest.
                kind == "spatial" -> candidates.firstOrNull { isAtmos(it) }
                // Within a tier: best first, except a LOW request (the smallest
                // copy the extension has), and except the lossless rung used as
                // a fallback for a lossy request — "HIGH" means "not the top
                // one", so the honest answer is the smaller lossless copy.
                kind == "lossy" && tier.uppercase(Locale.ROOT) == "LOW" -> candidates.last()
                kind == "lossless" && target == "lossy" -> candidates.last()
                else -> candidates.first()
            }
            if (pick != null) return pick.id
        }
        return options.first().id
    }

    /**
     * Whether an option is a Dolby Atmos mix, and not merely surround.
     *
     * Mornye's rule, verbatim: the canonical id `DOLBY_ATMOS`, or a spatial
     * option whose label names Atmos. An extension that publishes surround
     * under a spatial kind — `eac3`, "Dolby Digital Plus" — is not offering
     * the immersive mix, and asking it for one is asking for a file that is
     * not the thing the listener chose.
     *
     * The id is also read for the word itself, because extensions name their
     * own tiers: Tidal publishes `DOLBY_ATMOS` and others publish `ATMOS`,
     * and a manifest that omits the label entirely would otherwise be read as
     * having no Atmos option at all.
     */
    private fun isAtmos(option: Quality): Boolean =
        option.id.equals("DOLBY_ATMOS", ignoreCase = true) ||
            option.id.contains("atmos", ignoreCase = true) ||
            (option.kind == "spatial" && option.label.contains("atmos", ignoreCase = true))

    /**
     * [requested] first, then the tiers below it, so a refused Atmos or Hi-Res
     * request can still be answered with the lossless master.
     *
     * Spatial falls to lossless, lossless falls to the next lossless, and a
     * lossy tier is the last resort — the same ladder the manifest's own order
     * describes, stopping at four attempts so a source that holds nothing for
     * this track is not asked eight times.
     */
    fun fallbacks(manifest: SflxManifest, requested: String): List<String> {
        val options = optionsOf(manifest)
        if (options.isEmpty()) return listOf(requested)
        val requestedKind = options.firstOrNull { it.id.equals(requested, ignoreCase = true) }?.kind.orEmpty()
        val allowed = when (requestedKind) {
            "spatial" -> listOf("lossless", "lossy")
            "lossless" -> listOf("lossless", "lossy")
            else -> listOf("lossy", "lossless")
        }
        val ordered = ArrayList<String>()
        ordered += requested
        for (kind in allowed) {
            options.filter { it.kind == kind && !it.id.equals(requested, ignoreCase = true) }
                .forEach { ordered += it.id }
        }
        return ordered.distinct().take(MAX_TIER_ATTEMPTS)
    }

    /** How many tiers one stream request may try. */
    private const val MAX_TIER_ATTEMPTS = 4

    private fun optionsOf(manifest: SflxManifest): List<Quality> {
        val array = manifest.raw["qualityOptions"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val entry = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val id = (entry["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            if (id.isBlank()) return@mapNotNull null
            val declared = (entry["kind"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            val label = (entry["label"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            Quality(id = id, kind = kindOf(declared, id, label), label = label)
        }
    }

    /** The manifest's own `kind`, or the same inference Mornye applies when it is absent. */
    private fun kindOf(declared: String, id: String, label: String): String {
        val kind = declared.trim().lowercase(Locale.ROOT)
        if (kind == "lossless" || kind == "lossy" || kind == "spatial") return kind
        val token = id.trim().lowercase(Locale.ROOT)
        val text = "$token ${label.lowercase(Locale.ROOT)}"
        return when {
            text.contains("atmos") || text.contains("dolby") || text.contains("surround") ||
                token == "ac4" || token == "ac-4" || token == "eac3" || token == "e-ac-3" || token == "ec-3" ->
                "spatial"
            text.contains("lossless") || text.contains("flac") || text.contains("alac") ||
                text.contains("24-bit") || text.contains("16-bit") || token == "hi_res" ->
                "lossless"
            token == "high" || token == "low" || text.contains("mp3") || text.contains("aac") ->
                "lossy"
            else -> ""
        }
    }
}

/** Downloads a `.sflx` so it can be installed. */
object SflxDownloads {

    /**
     * Fetches a package or a registry.
     *
     * Deliberately a plain GET with a size ceiling rather than a streaming
     * download: a package is a few hundred kilobytes of JavaScript and a
     * registry is smaller still, and the install path wants the whole thing
     * before it can check a digest or read a manifest.
     *
     * The ceiling is what stops a URL that answers with something enormous from
     * being held in memory at all — the digest check cannot be done in pieces,
     * so a bounded read is the only protection available before it.
     */
    suspend fun fetch(url: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).header("Accept", "application/octet-stream, */*").build()
            com.music.bitchord.data.Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw SflxException("the server answered HTTP ${response.code}")
                val declared = response.body?.contentLength() ?: -1
                if (declared > MAX_PACKAGE_BYTES) {
                    throw SflxException("that file is ${declared / 1024 / 1024} MB, which is too large to be an extension")
                }
                val bytes = response.body?.bytes() ?: throw SflxException("the server sent nothing")
                if (bytes.size > MAX_PACKAGE_BYTES) {
                    throw SflxException("that file is too large to be an extension")
                }
                bytes
            }
        }.onFailure { Log.w(TAG, "sflx fetch failed for $url: ${it.message}") }
    }

    private const val TAG = "BitChord"

    /** 8 MB: an extension is a manifest and a script, and nothing legitimate is close to this. */
    private const val MAX_PACKAGE_BYTES = 8 * 1024 * 1024
}

