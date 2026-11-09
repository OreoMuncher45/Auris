package com.music.bitchord.data.sources.mornye

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * One entry of a Mornye extension store's `registry.json`.
 *
 * The store is a plain JSON file a host fetches over https: a list of packages,
 * each with the URL to download and the SHA-256 of the bytes at that URL. The
 * digest is not decoration — it is the only thing standing between a listener
 * tapping *Install* and running whatever the network handed back, so an install
 * refuses a package whose bytes do not hash to it.
 */
data class SflxRegistryEntry(
    val id: String,
    val displayName: String,
    val version: String,
    val description: String,
    val downloadUrl: String,
    val sha256: String,
    val category: String,
    val tags: List<String>,
    /** The oldest host version the publisher says can run it. */
    val minAppVersion: String,
    val iconUrl: String,
) {
    /** Whether this entry is a source the host can install at all. */
    val isInstallable: Boolean get() = id.isNotBlank() && downloadUrl.isNotBlank() && sha256.isNotBlank()
}

/** Reads a store's `registry.json`. */
object SflxRegistry {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Every entry in [text], in the order the store lists them.
     *
     * A malformed entry is skipped rather than failing the whole registry: a
     * store with one bad row still has usable extensions in it, and a listener
     * who cannot open the store at all cannot tell a typo from an outage.
     *
     * @throws SflxException when the document itself is not a registry.
     */
    fun parse(text: String): List<SflxRegistryEntry> {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: throw SflxException("registry.json is not a JSON object")
        val extensions = root["extensions"]?.jsonArray
            ?: throw SflxException("registry.json has no extensions list")
        return extensions.mapNotNull { element ->
            val entry = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val manifestId = entry.string("name").ifBlank { entry.string("id") }
            SflxRegistryEntry(
                id = manifestId,
                displayName = entry.string("display_name").ifBlank { entry.string("displayName") }
                    .ifBlank { manifestId },
                version = entry.string("version"),
                description = entry.string("description"),
                downloadUrl = entry.string("download_url").ifBlank { entry.string("downloadUrl") },
                sha256 = entry.string("sha256").lowercase().trim(),
                category = entry.string("category"),
                tags = (entry["tags"]?.jsonArray ?: emptyList()).mapNotNull {
                    (it as? JsonPrimitive)?.content
                },
                minAppVersion = entry.string("min_app_version").ifBlank { entry.string("minAppVersion") },
                iconUrl = entry.string("icon_url").ifBlank { entry.string("iconUrl") },
            ).takeIf { it.isInstallable }
        }
    }

    /**
     * The entries worth *showing* to an app on [appVersion].
     *
     * A publisher states the oldest host version that can run its package, so an
     * entry asking for a newer host than this one is filtered out rather than
     * offered and then failing after the download. Entries that state nothing
     * are kept: the format treats a missing minimum as no claim, and a store
     * that has not caught up with the field would otherwise be empty.
     */
    fun compatible(entries: List<SflxRegistryEntry>, appVersion: String): List<SflxRegistryEntry> =
        entries.filter { it.minAppVersion.isBlank() || Versions.isAtLeast(appVersion, it.minAppVersion) }

    /**
     * Whether [entry] is a newer version of an extension already installed.
     *
     * Equal versions are not updates, so a store still carrying the version on
     * disk does not offer a reinstall as news.
     */
    fun isUpdate(entry: SflxRegistryEntry, installed: InstalledSflx?): Boolean {
        val current = installed ?: return true
        if (entry.id != current.manifest.id) return false
        return Versions.compare(entry.version, current.manifest.version) > 0
    }

    private fun JsonObject.string(name: String): String =
        (this[name] as? JsonPrimitive)?.content.orEmpty().trim()
}
