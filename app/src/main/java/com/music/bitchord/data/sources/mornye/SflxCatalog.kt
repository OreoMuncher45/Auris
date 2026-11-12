package com.music.bitchord.data.sources.mornye

import android.content.Context
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
        // The lyrics bridge. A `.sflx` package that declares itself a lyrics
        // provider is asked through this host and nowhere else, so the
        // repository is given a fetcher here rather than reaching for one
        // itself — it has no idea what an extension is.
        com.music.bitchord.data.lyrics.LyricsRepository.extensionProvider = SflxLyrics
        Log.d(TAG, "sflx store at $directory")
        refresh()
        // The engines, loaded before anybody asks for one. A load is a token
        // fetch and a client handshake, and paying for it on the first search
        // is what made the first track of a session slow.
        hostRef?.warmUp()
    }

    /** Re-reads the installed packages and brings the sources list in line with them. */
    fun refresh() {
        val store = store ?: return
        val packages = runCatching { store.list() }.getOrDefault(emptyList())
        _installed.value = packages
        syncSources(packages)
        syncLyricsExtension(packages)
        refreshStatuses()
    }

    /**
     * Tells the lyrics repository which package is answering, and whether it
     * is ready to.
     *
     * The sources list called this source "Mornye extension" whatever was
     * installed — so a listener with Apple's package in it was told "Mornye
     * extension" and had no way to tell which package was meant. And when
     * that package was waiting for a token, the player said "no lyrics
     * found" for every song, because a package that cannot answer is a miss
     * and a miss says nothing. Both are said here instead: the package's own
     * name, and the setting it is missing.
     */
    private fun syncLyricsExtension(packages: List<InstalledSflx>) {
        val lyrics = packages.filter { it.manifest.isLyricsProvider }
        LyricsRepository.extensionLabel = lyrics.firstOrNull()?.manifest?.displayName
        LyricsRepository.extensionSetup = lyrics.firstNotNullOfOrNull { missingLyricsSetup(it) }
    }

    /**
     * The setting an installed lyrics extension is waiting for, when it is
     * waiting for one this host can see.
     *
     * Apple's package asks the listener for their own MusicKit token, under
     * the key `mediaUserToken`, and answers nothing at all until it has one.
     * The key is the one Mornye's store and host use for it, so it is read by
     * name rather than guessed at — and a package that does not declare it is
     * never reported as unconfigured.
     */
    private fun missingLyricsSetup(installed: InstalledSflx): LyricsRepository.ExtensionSetup? {
        val setting = installed.manifest.settings.firstOrNull {
            it.key.equals(MEDIA_USER_TOKEN_KEY, ignoreCase = true)
        } ?: return null
        val stored = runCatching { settingsOf(installed.id)[setting.key] }.getOrNull()
        val value = (stored as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
        if (value.isNotEmpty()) return null
        return LyricsRepository.ExtensionSetup(
            extensionName = installed.manifest.displayName,
            settingLabel = setting.label.ifBlank { setting.key },
        )
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
     * Every package that can *describe* tracks gets a row — the download
     * providers and the metadata providers alike. A metadata-only extension
     * used to be left out of the list on the grounds that it could never
     * answer a stream, and that was true and useless: the row was not the
     * point, the *catalogue* was.
     *
     * Apple's package in the Mornye store is the case that settles it. It
     * answers a search with Apple's own rows — the artwork, the album, the
     * release, the ISRC — and it can answer lyrics word by word. Its rows are
     * what the listener sees when they search for a track, and when one of
     * those rows is played the audio comes from whichever source holds the
     * recording, because this one holds no audio at all. Leaving it out of
     * the list meant the catalogue it carries was invisible, and the only
     * thing the package could do was the one thing the app already had.
     *
     * A source that cannot serve audio says so on its row — see
     * [ModuleSource.servesAudio] — and is left out of the playback walks,
     * where it could only cost a call.
     *
     * A newly installed *metadata* extension goes to the top of the order.
     * It cannot take a place in the playback ranking — there is no audio to
     * rank — so the order is free to mean what the listener means by it: the
     * catalogue asked first. An extension that serves audio is left where it
     * is, because its position is a statement about which copy of a track
     * should play, and installing something new is not a request to change
     * that.
     */
    private fun syncSources(packages: List<InstalledSflx>) {
        val describable = packages.filter {
            it.manifest.isDownloadProvider || it.manifest.isMetadataProvider
        }
        val wanted = describable.associateBy { configIdOf(it.id) }
        val existing = SourceRegistry.extensions().associateBy { it.id }

        // The metadata extensions added by this pass, which are the ones that
        // go to the top.
        val addedMetadata = mutableListOf<String>()

        wanted.forEach { (configId, installed) ->
            val current = existing[configId]
            when {
                current == null -> {
                    SourceRegistry.add(
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
                    if (!installed.manifest.isDownloadProvider) addedMetadata += configId
                }
                current.label != installed.manifest.displayName -> SourceRegistry.update(
                    current.copy(label = installed.manifest.displayName),
                )
            }
        }
        // A row whose package is gone — an uninstall that did not finish, a
        // directory cleared by the system — is removed here rather than being
        // left as a source that fails every call.
        existing.keys.filterNot { it in wanted }.forEach(SourceRegistry::remove)

        // The catalogue first, and only for a catalogue that was just
        // installed. Everything else keeps the order the listener gave it.
        if (addedMetadata.isNotEmpty()) {
            val order = addedMetadata + SourceRegistry.extensions().map { it.id }
                .filterNot { it in addedMetadata }
            SourceRegistry.reorderExtensions(order)
        }
    }

    private const val DIRECTORY = "extensions"

    /**
     * The setting an Apple Music lyrics package needs from the listener.
     *
     * The key Mornye's own store and host use for the subscriber's MusicKit
     * token; read by name so a package that does not declare it is not
     * reported as unconfigured.
     */
    private const val MEDIA_USER_TOKEN_KEY = "mediaUserToken"

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
