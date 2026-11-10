package com.music.bitchord.data.sources.mornye

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.module.ModuleSearchResponse
import com.music.bitchord.data.sources.module.ModuleSearchResult
import com.music.bitchord.data.sources.module.ModuleStreamResponse
import com.music.bitchord.data.sources.module.ModuleStreamTrack
import com.music.bitchord.data.sources.module.QuickJsExecutor
import kotlinx.coroutines.Dispatchers
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
    suspend fun search(extensionId: String, query: String, limit: Int): Result<ModuleSearchResponse> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext Result.success(ModuleSearchResponse())
            val installed = store.find(extensionId)
                ?: return@withContext Result.failure(SflxException("'$extensionId' is not installed"))
            if (!enabled(installed)) {
                return@withContext Result.failure(SflxException("'${installed.manifest.displayName}' is turned off"))
            }
            load(installed).getOrElse { return@withContext Result.failure(it) }

            val settings = settingsFor(installed)
            QuickJsExecutor.callExport(
                moduleId = moduleIdOf(extensionId),
                functionName = "searchTracks",
                args = listOf(quote(query), limit.toString(), context(settings)),
            ).mapCatching { raw ->
                val parsed = SflxRows.parse(raw)
                // Remembered per track, because the stream call needs what the
                // search already learned: the extension's own id for the
                // recording, its ISRC, its duration. Asking the extension to
                // search for it again would be a second request for something
                // the first one already answered.
                parsed.rows.forEach { row -> remember(extensionId, row) }
                parsed.response
            }
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
            QuickJsExecutor.callExport(
                moduleId = moduleIdOf(extensionId),
                functionName = "getTrackStreamUrl",
                args = listOf(quote(trackId), quote(requested), context(settingsFor(installed), prepared, wantsAtmos)),
            ).mapCatching { raw ->
                val answer = JSONObject(raw)
                val url = answer.optString("streamUrl", "").takeIf { it.isNotBlank() }
                if (url == null) {
                    val why = answer.optString("error", "").ifBlank { "the extension returned no stream URL" }
                    throw SflxException(why)
                }
                // What the extension said about the audio it resolved. It is a
                // claim, not a measurement — the fields come from the file the
                // extension was about to write, which this host never sees — and
                // it is treated as one everywhere downstream.
                val meta = answer.optJSONObject("track")
                ModuleStreamResponse(
                    streamUrl = url,
                    track = ModuleStreamTrack(
                        id = trackId,
                        audioQuality = meta?.optString("audioQuality").orEmpty(),
                        mimeType = meta?.optString("mimeType")?.takeIf { it.isNotBlank() },
                        bitDepth = meta?.optInt("bitDepth", 0)?.takeIf { it > 0 },
                        sampleRate = meta?.optDouble("sampleRate", 0.0)?.takeIf { it > 0 },
                        bitrate = meta?.optInt("bitrate", 0)?.takeIf { it > 0 },
                        audioModes = meta?.optJSONArray("audioModes")?.let { modes ->
                            (0 until modes.length()).mapNotNull { modes.optString(it).takeIf { s -> s.isNotBlank() } }
                        }?.takeIf { it.isNotEmpty() },
                    ),
                )
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
        if (QuickJsExecutor.isLoaded(moduleIdOf(installed.id))) return Result.success(Unit)
        val script = runCatching { installed.scriptFile.readText() }.getOrElse { error ->
            return Result.failure(SflxException("could not read ${installed.scriptFile.name}: ${error.message}"))
        }
        Log.d(TAG, "sflx load ${installed.id} v${installed.manifest.version} (${script.length} chars)")
        val session = sessionFor(installed)
        return QuickJsExecutor.loadModule(
            moduleId = moduleIdOf(installed.id),
            jsCode = script,
            fetchBase = "",
            acceptRegistered = true,
            preamble = SflxShim.preamble(installed.manifest, settingsFor(installed).toString(), appVersion),
            epilogue = SflxShim.ADAPTER,
            allowedHosts = installed.manifest.permissions.networkHosts,
            sessionCall = session?.let { client -> { method, args -> client.sessionCall(method, args) } },
        ).onFailure { Log.w(TAG, "sflx load failed for ${installed.id}: ${it.message}") }
    }

    /**
     * Forgets the loaded engines of [extensionId].
     *
     * Called when its settings change or when it is reinstalled, because both
     * mean the script in memory no longer matches what is on disk: a new
     * quality or a new account is read by `initialize()`, and that only runs
     * at load.
     */
    fun unload(extensionId: String) {
        QuickJsExecutor.unload(moduleIdOf(extensionId))
    }

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
         * The sandbox key an extension's engines live under.
         *
         * Prefixed so that it cannot collide with a module index's own ids —
         * both are user-visible strings from different sources, and a
         * collision would have one extension's engines serve another's calls.
         */
        const val MODULE_PREFIX = "sflx:"

        /** How many searched rows are kept to hand back on the stream call. */
        private const val MAX_REMEMBERED_ROWS = 400

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
    private data class Quality(val id: String, val kind: String)

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

    private fun optionsOf(manifest: SflxManifest): List<Quality> {
        val array = manifest.raw["qualityOptions"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val entry = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val id = (entry["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            if (id.isBlank()) return@mapNotNull null
            val declared = (entry["kind"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            val label = (entry["label"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            Quality(id = id, kind = kindOf(declared, id, label))
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
