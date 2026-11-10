package com.music.bitchord.data.sources.mornye

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.sources.module.ModuleSearchResponse
import com.music.bitchord.data.sources.module.ModuleSearchResult
import com.music.bitchord.data.sources.module.ModuleStreamResponse
import com.music.bitchord.data.sources.module.ModuleStreamTrack
import com.music.bitchord.data.sources.module.QuickJsExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
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
) {

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
    suspend fun streamUrl(extensionId: String, trackId: String, quality: String): Result<ModuleStreamResponse> =
        withContext(Dispatchers.IO) {
            val installed = store.find(extensionId)
                ?: return@withContext Result.failure(SflxException("'$extensionId' is not installed"))
            if (!installed.manifest.isDownloadProvider) {
                return@withContext Result.failure(
                    SflxException("'${installed.manifest.displayName}' describes tracks and cannot serve audio"),
                )
            }
            load(installed).getOrElse { return@withContext Result.failure(it) }

            val requested = SflxQuality.forTier(installed.manifest, quality)
            val prepared = rowFor(extensionId, trackId)
            QuickJsExecutor.callExport(
                moduleId = moduleIdOf(extensionId),
                functionName = "getTrackStreamUrl",
                args = listOf(quote(trackId), quote(requested), context(settingsFor(installed), prepared)),
            ).mapCatching { raw ->
                val answer = JSONObject(raw)
                val url = answer.optString("streamUrl", "").takeIf { it.isNotBlank() }
                if (url == null) {
                    val why = answer.optString("error", "").ifBlank { "the extension returned no stream URL" }
                    throw SflxException(why)
                }
                ModuleStreamResponse(
                    streamUrl = url,
                    track = ModuleStreamTrack(
                        id = trackId,
                        // The tier the extension was asked for is what it says
                        // it is serving. It is a claim, not a measurement, and
                        // is treated as one everywhere downstream.
                        audioQuality = answer.optJSONObject("track")?.optString("audioQuality").orEmpty(),
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
        return QuickJsExecutor.loadModule(
            moduleId = moduleIdOf(installed.id),
            jsCode = script,
            fetchBase = "",
            acceptRegistered = true,
            preamble = SflxShim.preamble(installed.manifest, settingsFor(installed).toString(), appVersion),
            epilogue = SflxShim.ADAPTER,
            allowedHosts = installed.manifest.permissions.networkHosts,
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
    private fun context(settings: JSONObject, preparedJson: String? = null): String {
        val root = JSONObject().apply {
            // The wrapper is part of the contract: a module reads a setting as
            // `context.settings.<key>.value`.
            val boxed = JSONObject()
            for (key in settings.keys()) {
                boxed.put(key, JSONObject().apply { put("value", settings.opt(key)) })
            }
            put("settings", boxed)
            put("appVersion", appVersion)
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

    private val LOSSLESS_HINTS = listOf("LOSSLESS", "HI_RES", "HIRES", "FLAC", "ALAC", "HI-RES", "MASTER")

    fun forTier(manifest: SflxManifest, tier: String): String {
        val options = optionsOf(manifest)
        if (options.isEmpty()) return tier

        val lossy = options.filterNot { it.isLossless }
        val lossless = options.filter { it.isLossless }

        return when (tier.uppercase(Locale.ROOT)) {
            "LOSSLESS" -> (lossless.firstOrNull() ?: options.first()).id
            // High is the best lossy copy the extension has. If it has none, its
            // worst lossless tier is the closest honest answer to "not the top
            // one".
            "HIGH" -> (lossy.firstOrNull() ?: lossless.lastOrNull() ?: options.last()).id
            "LOW" -> (lossy.lastOrNull() ?: lossless.lastOrNull() ?: options.last()).id
            else -> options.firstOrNull { it.id.equals(tier, ignoreCase = true) }?.id ?: tier
        }
    }

    private data class Quality(val id: String, val isLossless: Boolean)

    private fun optionsOf(manifest: SflxManifest): List<Quality> {
        val array = manifest.raw["qualityOptions"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val entry = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val id = (entry["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            if (id.isBlank()) return@mapNotNull null
            val kind = (entry["kind"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            Quality(
                id = id,
                isLossless = kind.contains("lossless", ignoreCase = true) ||
                    LOSSLESS_HINTS.any { it in id.uppercase(Locale.ROOT) },
            )
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
