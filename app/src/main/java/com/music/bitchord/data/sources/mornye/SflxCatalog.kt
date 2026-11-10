package com.music.bitchord.data.sources.mornye

import android.content.Context
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Installed extensions, and the one place that installs, removes and configures
 * them.
 *
 * ## Why an extension becomes a source config
 *
 * Everything that plays a track in this app — the resolver's ranking, the
 * download queue, the tier and Atmos gating, the sources screen with its
 * switches and its order — works on a list of [SourceConfig] rows. Making an
 * extension a *second* kind of thing would mean teaching all of that about a
 * second kind of thing, and every one of those places would then have two
 * behaviours to keep in step.
 *
 * So an installed extension is registered as one row of [SourceKind.MODULE]
 * whose URL is `sflx://<id>`. Nothing downstream needs to know it is an
 * extension: it is a module source that happens to be answered by a package on
 * this device instead of an index on a server. The sources screen draws it, the
 * listener switches it and orders it, and the resolver asks it like anything
 * else.
 *
 * That also settles what an extension's *settings* are, which is otherwise two
 * kinds of question: the ones the manifest declares (a quality tier, an account
 * field) belong to the extension and are persisted with its package by
 * [SflxStore]; the questions this app asks about a source (on, and in what
 * order) belong to the row.
 */
object SflxCatalog {

    private const val TAG = "BitChord"

    private var store: SflxStore? = null
    private var hostRef: SflxHost? = null

    /**
     * This app's version, as the host was initialised with.
     *
     * Read by the store filter below as well as handed to extensions through
     * [SflxHost]: an entry asking for a newer host than this one is filtered out
     * of a store's list rather than offered and then failing after a download.
     */
    @Volatile
    private var appVersion: String = ""

    /** Every installed extension, newest install last. */
    private val _installed = MutableStateFlow<List<InstalledSflx>>(emptyList())
    val installed: StateFlow<List<InstalledSflx>> = _installed.asStateFlow()

    /** What the install flow is doing, for the screen that started it. */
    private val _install = MutableStateFlow<InstallState>(InstallState.Idle)
    val install: StateFlow<InstallState> = _install.asStateFlow()

    /**
     * Each extension's signed-session state, for the rows that show it.
     *
     * Kept here rather than read from the host inside composition: the check
     * reads a file per extension, and doing that on every recomposition of a
     * reorderable list is the kind of work that turns a scroll into a stutter.
     * It is refreshed when something that can change it happens — an install, a
     * removal, a grant — and read from a flow in between.
     */
    private val _statuses = MutableStateFlow<Map<String, SflxSignedSession.Snapshot>>(emptyMap())
    val statuses: StateFlow<Map<String, SflxSignedSession.Snapshot>> = _statuses.asStateFlow()

    /** Re-reads every extension's signed-session state. Cheap; call after a change. */
    fun refreshStatuses() {
        val host = hostRef ?: return
        _statuses.value = runCatching { host.sessionStatuses() }.getOrDefault(emptyMap())
    }

    /**
     * Starts the verification page for [id]. Blocking — the challenge comes from
     * the gateway — so callers run it off the main thread.
     */
    fun beginVerification(id: String): String? = hostRef?.beginVerification(id)

    /** Forgets [id]'s stored session. */
    fun clearSession(id: String) {
        hostRef?.clearSession(id)
    }

    /** The host, once [init] has run. Null before that, and in a preview whose app never called it. */
    val host: SflxHost? get() = hostRef

    /** The installed package with this id, when it is installed. */
    fun installedOf(id: String): InstalledSflx? = _installed.value.firstOrNull { it.id == id }

    /**
     * Points the extension host at its directory and puts what is already
     * installed back on the sources list.
     *
     * Called once from the application, next to the other stores. The registry
     * sync happens here rather than only on install, because it is what repairs
     * the two things that drift: a package deleted from disk under a row that
     * survives it, and a row that has never existed for a package installed
     * before this app knew about extensions.
     */
    fun init(context: Context, appVersion: String) {
        if (hostRef != null) return
        val directory = File(context.filesDir, DIRECTORY)
        if (!directory.isDirectory && !directory.mkdirs()) {
            Log.w(TAG, "could not create the extension directory at $directory")
        }
        val store = SflxStore(directory)
        this.store = store
        this.appVersion = appVersion
        SflxVerify.init(context)
        val sessions = File(context.filesDir, SIGNED_SESSIONS_DIRECTORY).apply { mkdirs() }
        this.hostRef = SflxHost(store, appVersion, sessions)
        Log.d(TAG, "sflx store at $directory")
        refresh()
    }

    /** Re-reads the installed packages and brings the sources list in line with them. */
    fun refresh() {
        val store = store ?: return
        val packages = runCatching { store.list() }.getOrDefault(emptyList())
        _installed.value = packages
        syncSources(packages)
        refreshStatuses()
    }

    // ── Install ──────────────────────────────────────────────────────────

    /**
     * What a link turns out to be.
     *
     * Two things are linked to in this format, and they are not the same kind
     * of thing: a `.sflx` *is* an extension, while a `registry.json` *lists*
     * several. Telling them apart before showing anything is the difference
     * between one tap and a tap on a row that was never installable.
     */
    sealed interface InstallOffer {
        data class Package(val bytes: ByteArray, val manifest: SflxManifest, val url: String) : InstallOffer
        data class Store(val entries: List<SflxRegistryEntry>, val url: String) : InstallOffer
    }

    sealed interface InstallState {
        data object Idle : InstallState
        data class Working(val message: String) : InstallState
        data class Failed(val message: String) : InstallState
        data class Done(val id: String, val displayName: String) : InstallState
    }

    /**
     * Fetches [link] and works out what it is.
     *
     * The bytes are held rather than re-fetched: a package the listener has
     * just been told about and then taps *Install* on is installed from exactly
     * those bytes, so the digest that was checked is the digest that was
     * written. Fetching again would be a second chance for the network to
     * answer differently.
     */
    suspend fun inspect(link: String): Result<InstallOffer> {
        val candidates = SflxRegistry.candidates(link)
        if (candidates.isEmpty()) return Result.failure(SflxException("Paste a link first"))
        _install.value = InstallState.Working("Fetching…")

        // Every address the link might really be, tried in turn.
        //
        // A store is published as a repository, and the address a listener has
        // to hand over is usually some shape of that repository rather than the
        // file itself — the repository root, a folder in it, the GitHub page.
        // Fetching only the exact string that was pasted is what made the
        // first version of this useless: the store's own root answered with a
        // directory listing, and the screen said "not an extension package".
        var lastError: String? = null
        for (candidate in candidates) {
            val bytes = SflxDownloads.fetch(candidate).getOrElse { error ->
                lastError = error.message
                null
            } ?: continue

            // A registry is JSON; a package is a zip and its first two bytes are
            // `PK`. Checking the shape rather than the file name is what makes a
            // link with no extension at all — a `?raw=true` URL, a paste of the
            // document itself — work the same.
            val looksLikeJson = bytes.firstOrNull()?.toInt()?.toChar() == '{'
            if (looksLikeJson) {
                val entries = runCatching {
                    SflxRegistry.parse(bytes.decodeToString())
                }.getOrNull()
                if (entries != null) {
                    _install.value = InstallState.Idle
                    if (entries.isEmpty()) {
                        val failure = SflxException("That store lists no extensions for this runtime")
                        _install.value = InstallState.Failed(failure.message.orEmpty())
                        return Result.failure(failure)
                    }
                    return Result.success(InstallOffer.Store(entries, candidate))
                }
            }
            val packageFile = runCatching { SflxPackages.read(bytes) }.getOrElse { error ->
                lastError = error.message
                null
            } ?: continue
            _install.value = InstallState.Idle
            return Result.success(InstallOffer.Package(bytes, packageFile.manifest, candidate))
        }

        val failure = SflxException(
            lastError?.let { "Could not read that link: $it" }
                ?: "That link is not an extension package, and no store was found at it",
        )
        _install.value = InstallState.Failed(failure.message.orEmpty())
        return Result.failure(failure)
    }

    /** Installs an inspected package. No digest is checked: there was nobody to state one. */
    fun install(offer: InstallOffer.Package): Result<InstalledSflx> {
        val host = hostRef ?: return Result.failure(SflxException("The extension host is not running"))
        _install.value = InstallState.Working("Installing ${offer.manifest.displayName}…")
        return host.install(offer.bytes, expectedSha256 = null, sourceUrl = offer.url)
            .onSuccess { refresh() }
            .onSuccess { _install.value = InstallState.Done(it.id, it.manifest.displayName) }
            .onFailure { _install.value = InstallState.Failed(it.message ?: "Could not install that package") }
    }

    /**
     * Installs one entry of a store, checking the digest the store published.
     *
     * This is the path the digest exists for: the listener has been shown a
     * name and a version, and what arrives over the network is only accepted if
     * it hashes to what the store said it would.
     */
    suspend fun install(entry: SflxRegistryEntry): Result<InstalledSflx> {
        val host = hostRef ?: return Result.failure(SflxException("The extension host is not running"))
        _install.value = InstallState.Working("Downloading ${entry.displayName}…")
        val bytes = SflxDownloads.fetch(entry.downloadUrl).getOrElse { error ->
            _install.value = InstallState.Failed(error.message ?: "Could not download that extension")
            return Result.failure(error)
        }
        _install.value = InstallState.Working("Installing ${entry.displayName}…")
        return host.install(bytes, expectedSha256 = entry.sha256, sourceUrl = entry.downloadUrl)
            .onSuccess { refresh() }
            .onSuccess { _install.value = InstallState.Done(it.id, it.manifest.displayName) }
            .onFailure { _install.value = InstallState.Failed(it.message ?: "Could not install that extension") }
    }

    fun clearInstallState() {
        _install.value = InstallState.Idle
    }

    /**
     * Removes an extension, its settings and its sources row.
     *
     * All three, because two of them on their own are a lie: a package left on
     * disk behind a removed row is invisible until it is reinstalled, and a row
     * left behind without a package is a source that fails every search.
     */
    fun remove(id: String) {
        hostRef?.remove(id)
        SourceRegistry.remove(configIdOf(id))
        refresh()
    }

    fun setEnabled(id: String, enabled: Boolean) {
        SourceRegistry.setEnabled(configIdOf(id), enabled)
    }

    /**
     * Saves the listener's answers for an extension's own settings block.
     *
     * The host reloads that extension's engines as part of this — see
     * [SflxHost.saveSettings] — because an extension reads its settings once
     * and holds them.
     */
    fun saveSettings(id: String, values: JsonObject) {
        hostRef?.saveSettings(id, values.toString())
    }

    /** The listener's current answers for an extension's settings block. */
    fun settingsOf(id: String): JsonObject =
        runCatching { Json.parseToJsonElement(hostRef?.settingsJson(id) ?: "{}") as JsonObject }
            .getOrDefault(JsonObject(emptyMap()))

    // ── Sources list ─────────────────────────────────────────────────────

    /** The registry id for an extension, which is also the sandbox's module id. */
    fun configIdOf(extensionId: String): String = "${SflxHost.MODULE_PREFIX}$extensionId"

    /**
     * Makes the sources list match [packages].
     *
     * Only `download_provider` packages get a row. A metadata-only extension
     * cannot serve a stream, and a row that appears in the source order but can
     * never answer is worse than no row: it takes a place in a ranking it
     * cannot compete in, and every lookup that reaches it pays for a call that
     * returns nothing. Such a package can still be installed — its metadata is
     * read by whatever asks for it — it just is not a *source*.
     */
    private fun syncSources(packages: List<InstalledSflx>) {
        val downloadable = packages.filter { it.manifest.isDownloadProvider }
        val wanted = downloadable.associateBy { configIdOf(it.id) }
        val existing = SourceRegistry.extensions().associateBy { it.id }

        wanted.forEach { (configId, installed) ->
            val current = existing[configId]
            when {
                current == null -> SourceRegistry.add(
                    SourceConfig(
                        id = configId,
                        kind = SourceKind.MODULE,
                        label = installed.manifest.displayName,
                        baseUrl = SflxHost.configUrlFor(installed.id),
                        // Not stock: it was the listener's install, so it is
                        // theirs to remove. Nothing is shipped with this app.
                        stock = false,
                    ),
                )
                current.label != installed.manifest.displayName -> SourceRegistry.update(
                    current.copy(label = installed.manifest.displayName),
                )
            }
        }
        // A row whose package is gone — an uninstall that did not finish, a
        // directory cleared by the system — is removed here rather than being
        // left as a source that fails every call.
        existing.keys.filterNot { it in wanted }.forEach(SourceRegistry::remove)
    }

    private const val DIRECTORY = "extensions"

    /**
     * Where signed-session records live, one JSON per gateway scope.
     *
     * Separate from the packages because a record is a credential, not part of
     * an extension: reinstalling the extension must not throw it away, and
     * two extensions that declare the same namespace share it exactly as
     * Mornye's own store does.
     */
    private const val SIGNED_SESSIONS_DIRECTORY = "signed_sessions"
}
