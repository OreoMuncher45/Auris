package com.music.bitchord.data.sources.backup

import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.addon.AddonClient

/** What to do with something in the file that's already on this device. */
enum class AddonMergeMode {
    /** Leave it alone: the device's settings and sign-ins win. */
    SKIP_EXISTING,

    /** Overwrite it with the file's copy: settings, sign-ins, package and on/off state. */
    REPLACE_EXISTING,
}

/** One line of the import preview. */
data class AddonPreviewItem(
    val name: String,
    val kind: Kind,
    /** Already on this device: an addon at the same address, or an extension with the same id. */
    val installed: Boolean,
) {
    enum class Kind { ADDON, EXTENSION }
}

/**
 * What an import will change, worked out before anything is written.
 *
 * [addSources] are new rows. [replaceSources] are existing rows overwritten
 * with the file's values, keeping their id and stock flag. [extensions] are
 * the ids whose packages will be written. [extensionEnabled] is the on/off
 * state to set once the rows exist. [sessions] are the session records to
 * write. [skipped] counts addons and extensions left as they were.
 */
data class AddonImportPlan(
    val addSources: List<SourceConfig>,
    val replaceSources: List<SourceConfig>,
    val extensions: List<String>,
    val extensionEnabled: Map<String, Boolean>,
    val sessions: List<String>,
    val skipped: Int,
) {
    /** Addons plus extensions this import adds or replaces. */
    val imported: Int get() = addSources.size + replaceSources.size + extensions.size
}

/**
 * The merge rules, kept apart from Android so they can be tested directly.
 *
 * An addon is matched by kind and normalised address, so `…/manifest.json`
 * and the bare root count as the same server. An extension is matched by its
 * manifest id. Nothing else is compared, so a renamed addon still counts as
 * installed.
 */
object AddonBackupMerge {

    /** The kinds a backup carries. The built-in sources ship with the app, and extension rows are rebuilt from packages. */
    val BACKED_UP_KINDS: Set<SourceKind> = setOf(SourceKind.ADDON, SourceKind.CUSTOM_MODULE)

    fun preview(
        contents: AddonBackupContents,
        current: List<SourceConfig>,
        installedExtensions: Set<String>,
    ): List<AddonPreviewItem> {
        val sources = restorableSources(contents).map { source ->
            AddonPreviewItem(
                name = displayName(source),
                kind = AddonPreviewItem.Kind.ADDON,
                installed = matching(source, current) != null,
            )
        }
        val extensions = restorableExtensions(contents).map { extension ->
            AddonPreviewItem(
                name = extension.displayName.ifBlank { extension.id },
                kind = AddonPreviewItem.Kind.EXTENSION,
                installed = extension.id in installedExtensions,
            )
        }
        return sources + extensions
    }

    fun plan(
        contents: AddonBackupContents,
        current: List<SourceConfig>,
        installedExtensions: Set<String>,
        existingSessions: Set<String>,
        mode: AddonMergeMode,
    ): AddonImportPlan {
        val replace = mode == AddonMergeMode.REPLACE_EXISTING
        val add = mutableListOf<SourceConfig>()
        val replaced = mutableListOf<SourceConfig>()
        var skipped = 0
        // A file that somehow lists the same server twice adds it once.
        val seen = mutableSetOf<String>()
        restorableSources(contents).forEach { source ->
            val kind = SourceKind.valueOf(source.kind)
            if (!seen.add(identity(kind, source.baseUrl))) return@forEach
            val existing = matching(source, current)
            when {
                existing == null -> add += source.toConfig(kind)
                replace -> replaced += source.toConfig(kind).copy(id = existing.id, stock = existing.stock)
                else -> skipped++
            }
        }
        val extensions = mutableListOf<String>()
        val enabled = linkedMapOf<String, Boolean>()
        restorableExtensions(contents).forEach { extension ->
            val installed = extension.id in installedExtensions
            if (!installed || replace) {
                extensions += extension.id
                extension.enabled?.let { enabled[extension.id] = it }
            } else {
                skipped++
            }
        }
        val sessions = contents.sessionFiles.keys
            .filter { replace || it !in existingSessions }
        return AddonImportPlan(add, replaced, extensions, enabled, sessions, skipped)
    }

    /** The sources this build can restore: a kind it still has, with an address. */
    private fun restorableSources(contents: AddonBackupContents): List<BackedUpSource> =
        contents.index.sources.filter { source ->
            val kind = SourceKind.entries.firstOrNull { it.name == source.kind }
            kind != null && kind in BACKED_UP_KINDS && source.baseUrl.isNotBlank()
        }

    /** The extensions the file actually carries files for, listed or not. */
    private fun restorableExtensions(contents: AddonBackupContents): List<BackedUpExtension> {
        val listed = contents.index.extensions.filter { it.id in contents.extensionFiles }
        val unlisted = contents.extensionFiles.keys
            .filter { id -> listed.none { it.id == id } }
            .map { BackedUpExtension(id = it) }
        return listed + unlisted
    }

    private fun matching(source: BackedUpSource, current: List<SourceConfig>): SourceConfig? {
        val kind = SourceKind.entries.firstOrNull { it.name == source.kind } ?: return null
        val key = identity(kind, source.baseUrl)
        return current.firstOrNull { it.kind == kind && identity(it.kind, it.baseUrl) == key }
    }

    /**
     * The matching key for an address. Only the scheme and host are
     * case-insensitive (RFC 3986), so only they are lowercased. Path and
     * query keep their case, and `/Module.json` and `/module.json` stay two
     * different sources.
     */
    private fun identity(kind: SourceKind, baseUrl: String): String {
        val base = if (kind == SourceKind.ADDON) AddonClient.normalizeBase(baseUrl) else baseUrl.trim().trimEnd('/')
        return "${kind.name}|${lowercaseSchemeAndHost(base)}"
    }

    private fun lowercaseSchemeAndHost(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return url
        val authorityStart = schemeEnd + 3
        val authorityEnd = url.indexOfAny(charArrayOf('/', '?', '#'), authorityStart).let { if (it < 0) url.length else it }
        val authority = url.substring(authorityStart, authorityEnd)
        // User info, when present, is case-sensitive; only the host after '@' is folded.
        val at = authority.lastIndexOf('@')
        val folded = if (at < 0) authority.lowercase() else authority.substring(0, at + 1) + authority.substring(at + 1).lowercase()
        return url.substring(0, schemeEnd).lowercase() + "://" + folded + url.substring(authorityEnd)
    }

    private fun displayName(source: BackedUpSource): String =
        source.label.trim().ifBlank {
            runCatching { java.net.URI(source.baseUrl.trim()).host }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: source.baseUrl.trim()
        }

    private fun BackedUpSource.toConfig(kind: SourceKind) = SourceConfig(
        kind = kind,
        label = label,
        baseUrl = baseUrl,
        stock = false,
        enabled = enabled,
        settings = settings.filterValues { it.isNotBlank() },
        allowDownloads = allowDownloads,
        checkValidLossless = checkValidLossless,
    )

    /** The backup row for [config]. The inverse of [toConfig]. */
    fun backedUp(config: SourceConfig) = BackedUpSource(
        kind = config.kind.name,
        label = config.label,
        baseUrl = config.baseUrl,
        enabled = config.enabled,
        settings = config.settings,
        allowDownloads = config.allowDownloads,
        checkValidLossless = config.checkValidLossless,
    )
}
