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
    /**
     * The oldest *Mornye* version the publisher says can run it.
     *
     * Kept, and shown, but never compared against this app's own version — see
     * [SflxRegistry.hostNote]. The number is a statement about Mornye's host,
     * and this app is a different host on a different version line: 3.0.25 here
     * is not "older than Mornye 4.3.0", it is a different number entirely.
     */
    val minAppVersion: String,
    val iconUrl: String,
    /**
     * The runtime the package needs: `javascript`, or `python` for the packages
     * Mornye-Python loads.
     *
     * This app runs the JavaScript one. A Python package is not a slower
     * extension, it is a different program in a different runtime, and offering
     * it would be offering something that cannot run here at all.
     */
    val runtime: String,
) {
    /** Whether this entry is a source the host can install at all. */
    val isInstallable: Boolean get() = id.isNotBlank() && downloadUrl.isNotBlank() && sha256.isNotBlank()
}

/** Reads a store's `registry.json`. */
object SflxRegistry {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * The store Auris knows by name.
     *
     * Mornye publishes its catalog at this address, and the packages it lists
     * sit beside it under `extensions/`. It is written down here rather than
     * asked for, because a store nobody can name is a store nobody opens: the
     * alternative was a listener being told to paste a URL they had to be told
     * the shape of first, which is how the first version of this ended up
     * telling people to paste a *directory*.
     *
     * Named, not installed: the install sheet offers this store as one button
     * to tap, and every package in it still has to be chosen by name and
     * installed on its own. Nothing is fetched until the listener asks, and
     * nothing runs until they say so.
     */
    const val MORNYE_STORE =
        "https://raw.githubusercontent.com/mornyeapp/Mornye-Extension/main/registry.json"

    /**
     * The addresses [link] might really be, best first.
     *
     * A store is published as a repository, and the things a listener has to
     * hand over are all shapes of that repository: the repository root, a
     * folder inside it, the GitHub page for it, the raw file itself. Each of
     * those is a different string and only one of them is the file. Rather than
     * refusing the other three — which is what a host that only accepts an
     * exact address does — they are each turned into the addresses that could
     * hold the registry, and the caller tries them in turn.
     *
     * The pasted link itself is always first: a link that is already the exact
     * file must be fetched exactly once and never somewhere else.
     */
    fun candidates(link: String): List<String> {
        val raw = link.trim()
        if (raw.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        out += raw

        // raw.githubusercontent.com/<owner>/<repo>[/<ref>[/<path…>]]
        val rawRepo = Regex(
            """^(https?)://raw\.githubusercontent\.com/([^/]+)/([^/]+)(?:/([^/]+)(?:/(.*))?)?/?$""",
            RegexOption.IGNORE_CASE,
        ).matchEntire(raw)
        if (rawRepo != null) {
            val (scheme, owner, repo, ref, path) = rawRepo.destructured
            val base = "$scheme://raw.githubusercontent.com/$owner/$repo"
            if (path.isBlank()) {
                // The repository root, or the root of a branch: the registry
                // lives one level in, under whichever branch is published.
                val branches = (listOf(ref) + listOf("main", "master")).filter { it.isNotBlank() }.distinct()
                branches.forEach { branch ->
                    out += "$base/$branch/registry.json"
                    out += "$base/$branch/registry-unified.json"
                }
            } else {
                val branch = ref.ifBlank { "main" }
                out += "$base/$branch/${path.trimEnd('/')}/registry.json"
            }
            return out.toList()
        }

        // github.com/<owner>/<repo>[/tree|blob/<ref>[/<path…>]]
        val github = Regex(
            """^(https?)://(?:www\.)?github\.com/([^/]+)/([^/]+)(?:/(tree|blob)/([^/]+)(?:/(.*))?)?/?$""",
            RegexOption.IGNORE_CASE,
        ).matchEntire(raw)
        if (github != null) {
            val (scheme, owner, repo, _, ref, path) = github.destructured
            val base = "$scheme://raw.githubusercontent.com/$owner/${repo.removeSuffix(".git")}"
            if (path.isBlank()) {
                val branches = (listOf(ref) + listOf("main", "master")).filter { it.isNotBlank() }.distinct()
                branches.forEach { branch ->
                    out += "$base/$branch/registry.json"
                    out += "$base/$branch/registry-unified.json"
                }
            } else {
                val branch = ref.ifBlank { "main" }
                out += "$base/$branch/${path.trimEnd('/')}/registry.json"
            }
            return out.toList()
        }

        // A raw file that is not a repository: leave it alone. It may be a
        // package, or a registry published somewhere else entirely.
        return out.toList()
    }

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
            val runtime = entry.string("runtime").ifBlank { "javascript" }
            // A package for another runtime is not an extension this app can
            // run, so it is not offered at all: an install button that installs
            // something unrunnable is worse than no button.
            if (!runtime.equals("javascript", ignoreCase = true)) return@mapNotNull null
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
                runtime = runtime,
            ).takeIf { it.isInstallable }
        }
    }

    /**
     * The note to show beside [entry] about the host it was built for.
     *
     * The publisher states the oldest Mornye that can run its package. This app
     * is not Mornye, so the number says nothing about whether the package will
     * work here — and treating it as though it did is what emptied the store
     * before: every entry in Mornye's own catalog asks for a newer *Mornye*
     * than this app's version number happens to be, and thirteen of fourteen
     * were filtered out on that comparison alone.
     *
     * So the number is shown as what it is — a fact about the publisher's
     * target — and whether the package actually runs here is decided by
     * running it.
     */
    fun hostNote(entry: SflxRegistryEntry): String? =
        entry.minAppVersion.takeIf { it.isNotBlank() }?.let { "Built for Mornye $it or newer" }

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
