package com.music.bitchord.data.sources.mornye

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * A Mornye extension's `manifest.json`, as the host reads it.
 *
 * Mornye (formerly SpotiFLAC) `.sflx` extensions are the de-facto standard for
 * third-party music sources: Qobuz, Tidal, Amazon, Deezer, Apple Music, YouTube
 * Music and others are distributed as one of these, and another app has already
 * adopted the format. Auris reads the same format so those packages work here
 * unchanged, rather than inventing a source format of its own.
 *
 * ## Three types, two of which are sources
 *
 * An extension declares `type: ["metadata_provider", "download_provider"]`, and
 * sometimes a third: `lyrics_provider`. A `download_provider` can serve bytes —
 * that is what a source is — and a `metadata_provider` can only describe them.
 * Both are listed in the Sources tab, and only the downloaders appear in the
 * ranking that decides who serves a stream. A `lyrics_provider` is neither: it
 * serves words, and it is asked for lyrics and for nothing else.
 *
 * ## Why the parser is hand-written and case-insensitive
 *
 * Mornye's own host matches manifest keys with an ASCII-case-insensitive
 * compare and treats missing and null scalars as their zero values, because the
 * manifest is authored against a Go implementation that does the same. A parser
 * that insisted on exact casing would refuse packages Mornye accepts, and a
 * package that works in one app and not the other is worse than either.
 *
 * Unknown keys are kept in [raw] rather than dropped: the format grows, and a
 * field this version does not know about is one a later version can still read
 * back off disk.
 */
data class SflxManifest(
    /** The extension's own name, and the directory it installs under. */
    val id: String,
    val displayName: String,
    val version: String,
    val description: String,
    val homepage: String,
    /** `metadata_provider`, `download_provider`, `lyrics_provider`, or several. */
    val types: List<String>,
    /**
     * The oldest host version that can run this extension, as the extension
     * author states it. Blank means "no claim", which is treated as compatible:
     * refusing on a missing field would lock out working extensions.
     */
    val minAppVersion: String,
    val capabilities: Set<String>,
    val permissions: SflxPermissions,
    /**
     * The settings the extension wants shown on its own row — a quality picker,
     * an account field, a region. Rendered by the host and handed back to the
     * extension as the `config` its `initialize` receives.
     */
    val settings: List<SflxSetting>,
    /** Everything the manifest declared, for fields this version does not model. */
    val raw: JsonObject,
) {
    /** Whether this extension can serve audio at all. */
    val isDownloadProvider: Boolean get() = types.contains(SflxTypes.DOWNLOAD_PROVIDER)

    /** Whether this can answer metadata (search, tracks, albums). */
    val isMetadataProvider: Boolean get() = types.contains(SflxTypes.METADATA_PROVIDER)

    /**
     * Whether this can answer lyrics.
     *
     * A third kind of thing, and the only one that is not a source: it serves
     * words, not audio, so it never takes a row in the ranking that decides
     * who serves a stream. It is asked for lyrics and for nothing else.
     */
    val isLyricsProvider: Boolean get() = types.contains(SflxTypes.LYRICS_PROVIDER)

    /**
     * Whether the extension claims it supersedes the host's built-in providers
     * for the services it speaks.
     *
     * Read but never trusted on its own: it decides where the extension starts
     * in the ranking, and the listener moves it from there.
     */
    val replacesBuiltInProviders: Boolean
        get() = declares(SflxCapabilities.REPLACES_BUILT_IN_PROVIDERS)

    /**
     * Whether what this extension serves needs remuxing before it can be played
     * — Tidal and Amazon hand over segments rather than whole files.
     */
    val requiresNativeContainerConversion: Boolean
        get() = declares(SflxCapabilities.REQUIRES_NATIVE_CONTAINER_CONVERSION)

    /**
     * Whether the manifest lists [capability], whatever casing it was written
     * in — [capabilities] is kept lower-cased and the constant is not.
     */
    fun declares(capability: String): Boolean = capability.lowercase() in capabilities

    /**
     * Whether the extension can be run by an app on [appVersion].
     *
     * Both sides are dotted numbers, compared part by part rather than as text,
     * so "4.10.0" is newer than "4.9.1" and not older than it.
     */
    fun runsOn(appVersion: String): Boolean =
        minAppVersion.isBlank() || Versions.isAtLeast(appVersion, minAppVersion)
}

/** The `permissions` block: what the extension may reach. */
data class SflxPermissions(
    /**
     * Hosts the extension may call. Matched exactly, or by a leading `*.`
     * wildcard for a subdomain.
     */
    val networkHosts: List<String>,
    /** Whether it may keep its own key/value state between runs. */
    val storage: Boolean,
    /** Whether it may read and write files the app has access to. */
    val file: Boolean,
    /** Whether plain-http hosts are allowed, which the host otherwise refuses. */
    val allowHttp: Boolean,
) {
    /** Whether [host] may be reached, by this extension's own declaration. */
    fun allowsHost(host: String): Boolean {
        val target = host.lowercase().trim().substringBefore(':')
        if (target.isEmpty()) return false
        return networkHosts.any { pattern ->
            val allowed = pattern.lowercase().trim()
            when {
                allowed.isEmpty() -> false
                allowed.startsWith("*.") -> target.endsWith(allowed.substring(1))
                else -> target == allowed
            }
        }
    }
}

/** One setting an extension wants the host to render on its row. */
data class SflxSetting(
    val key: String,
    /** The field type: `string`, `bool`, `select`, `number`… as the author spells it. */
    val type: String,
    val label: String,
    val description: String,
    val required: Boolean,
    val secret: Boolean,
    val default: JsonElement?,
    val options: List<String>,
    val action: String,
)

/** The type strings an extension can declare. */
object SflxTypes {
    const val METADATA_PROVIDER = "metadata_provider"
    const val DOWNLOAD_PROVIDER = "download_provider"
    const val LYRICS_PROVIDER = "lyrics_provider"
}

/** Capability flags this host reads by name. */
object SflxCapabilities {
    const val REPLACES_BUILT_IN_PROVIDERS = "replacesBuiltInProviders"
    const val REQUIRES_NATIVE_CONTAINER_CONVERSION = "requiresNativeContainerConversion"
    const val DOWNLOAD_TRANSFER = "downloadTransfer"
    const val DOWNLOAD_FALLBACK_TIER = "downloadFallbackTier"
    const val HOME_FEED = "homeFeed"
}

/** Reads `manifest.json` into [SflxManifest], tolerantly. */
object SflxManifestParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** @throws SflxException when the text is not JSON, or says nothing useful. */
    fun parse(text: String): SflxManifest = parse(
        runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: throw SflxException("manifest.json is not a JSON object"),
    )

    fun parse(root: JsonObject): SflxManifest {
        val manifest = SflxManifest(
            id = root.text("name"),
            displayName = root.text("displayName").ifBlank { root.text("name") },
            version = root.text("version"),
            description = root.text("description"),
            homepage = root.text("homepage"),
            types = root.strings("type"),
            minAppVersion = root.text("minAppVersion"),
            // The capability keys are lower-cased on the way in: the flags are
            // read by name from several places, and the manifest is authored in
            // whatever casing its author used.
            capabilities = root.obj("capabilities")?.keysOf().orEmpty()
                .map { it.lowercase() }
                .toSet(),
            permissions = parsePermissions(root.obj("permissions")),
            settings = root.list("settings").mapNotNull { parseSetting(it) },
            raw = root,
        )
        if (manifest.id.isBlank()) throw SflxException("manifest.json has no name")
        if (manifest.types.isEmpty()) throw SflxException("manifest.json declares no type")
        return manifest
    }

    private fun parsePermissions(block: JsonObject?): SflxPermissions {
        if (block == null) return SflxPermissions(emptyList(), false, false, false)
        return SflxPermissions(
            networkHosts = block.strings("network"),
            storage = block.bool("storage"),
            file = block.bool("file"),
            allowHttp = block.bool("allowHttp"),
        )
    }

    private fun parseSetting(element: JsonElement): SflxSetting? {
        val setting = element as? JsonObject ?: return null
        val key = setting.text("key")
        if (key.isBlank()) return null
        return SflxSetting(
            key = key,
            type = setting.text("type"),
            label = setting.text("label"),
            description = setting.text("description"),
            required = setting.bool("required"),
            secret = setting.bool("secret"),
            default = setting["default"],
            options = setting.strings("options"),
            action = setting.text("action"),
        )
    }

    // -- tolerant readers ---------------------------------------------------

    private fun JsonObject.key(name: String): JsonElement? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
            ?.takeIf { it !is JsonNull }

    private fun JsonObject.text(name: String): String = (key(name) as? JsonPrimitive)?.content.orEmpty()

    private fun JsonObject.bool(name: String): Boolean =
        (key(name) as? JsonPrimitive)?.booleanOrNull ?: false

    private fun JsonObject.obj(name: String): JsonObject? = key(name) as? JsonObject

    private fun JsonObject.list(name: String): List<JsonElement> = (key(name) as? JsonArray)?.toList().orEmpty()

    private fun JsonObject.strings(name: String): List<String> =
        list(name).mapNotNull { (it as? JsonPrimitive)?.content?.trim() }.filter { it.isNotEmpty() }

    private fun JsonObject.keysOf(): Set<String> = keys
}

/**
 * Compares dotted version strings.
 *
 * Compared as numbers part by part, because text comparison gets the one case
 * that matters wrong: "4.10.0" is newer than "4.9.1" and sorts older as a
 * string. Missing parts count as zero, so "4.9" and "4.9.0" agree, and a
 * suffix after a `-` (a pre-release tag) is ignored — an extension naming a
 * minimum of `4.3.0-beta` wants 4.3.0.
 */
object Versions {
    fun isAtLeast(current: String, required: String): Boolean = compare(current, required) >= 0

    fun compare(left: String, right: String): Int {
        val a = parts(left)
        val b = parts(right)
        for (index in 0 until maxOf(a.size, b.size)) {
            val difference = (a.getOrElse(index) { 0 }) - (b.getOrElse(index) { 0 })
            if (difference != 0) return if (difference > 0) 1 else -1
        }
        return 0
    }

    private fun parts(version: String): List<Int> =
        version.substringBefore('-').substringBefore('+').trim()
            .split('.')
            .map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
}

/** Raised for a package or manifest the host will not run. */
open class SflxException(message: String) : Exception(message)

/**
 * A failure that says what to do about it.
 *
 * The extension could not answer because its signed session is not in order,
 * and the listener is the only one who can put it right — the gateway wants a
 * browser, a challenge and a human. So the message names the extension and
 * says where the button is, rather than reporting a status code at the
 * listener.
 *
 * Its own type because the wording is the whole point: a caller that shows
 * the message as it stands must not also prefix it with the source's name,
 * and a caller that logs it must not turn it back into a bare error.
 */
class SflxVerificationNeeded(message: String) : SflxException(message)
