package com.music.bitchord.data.sources.mornye

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * An extension installed on this device.
 *
 * [directory] holds the unpacked package: the manifest and script it was
 * installed from, the icon when it brought one, and the bookkeeping the host
 * needs to tell a reinstall from an update.
 */
data class InstalledSflx(
    val manifest: SflxManifest,
    val directory: File,
    /** Digest of the `.sflx` the bytes came from, as installed. */
    val sha256: String,
    /** Where it was downloaded from, so an update can be looked for later. */
    val sourceUrl: String,
    val installedAt: Long,
) {
    val id: String get() = manifest.id
    val scriptFile: File get() = File(directory, SflxStore.SCRIPT_FILE)
    val iconFile: File? get() = File(directory, SflxStore.ICON_FILE).takeIf { it.isFile }
}

/**
 * Where installed extensions live, and the only thing that writes them.
 *
 * One directory per extension, named by its manifest `name`, holding:
 *
 *  - `manifest.json`, `index.js` — the package, unpacked. The script is kept as
 *    a file rather than in a database because it is read as a script, once per
 *    run, and a blob in shared preferences is a worse way to store four
 *    hundred kilobytes of JavaScript.
 *  - `icon.png`, when the archive carried one.
 *  - `meta.json` — the digest, the URL it came from and when it was installed.
 *  - `settings.json` — whatever the listener filled into the extension's own
 *    settings block. Kept apart from the package so an update cannot take the
 *    listener's answers with it.
 *
 * ## Install is a swap, never an edit
 *
 * A package is written into a fresh directory and moved into place, so an
 * interrupted install leaves either the old version or nothing — never a
 * half-written script that the host would happily run.
 */
class SflxStore(private val root: File) {

    /** Every installed extension, by id, newest install last. */
    fun list(): List<InstalledSflx> {
        val directories = root.listFiles { file -> file.isDirectory } ?: return emptyList()
        return directories.mapNotNull { read(it) }.sortedBy { it.installedAt }
    }

    /** The installed extension with [id], or null. */
    fun find(id: String): InstalledSflx? = read(directoryOf(id))

    /**
     * Installs the bytes of a `.sflx`.
     *
     * @param expectedSha256 the digest the store published for these bytes. When
     *   given, a mismatch is fatal: the package is not written at all. An
     *   install with no digest is allowed for a package the listener supplied
     *   by hand (a file they downloaded themselves), which is why the check is
     *   conditional rather than mandatory.
     * @throws SflxException when the digest does not match, the id is not a safe
     *   directory name, or the package itself will not read.
     */
    fun install(bytes: ByteArray, expectedSha256: String? = null, sourceUrl: String = ""): InstalledSflx {
        val digest = SflxPackages.sha256(bytes)
        if (!expectedSha256.isNullOrBlank() && !digest.equals(expectedSha256.trim(), ignoreCase = true)) {
            throw SflxException(
                "package digest does not match the store's: expected ${expectedSha256.trim()}, got $digest",
            )
        }
        val packageFile = SflxPackages.read(bytes)
        val id = packageFile.manifest.id
        if (!isSafeId(id)) throw SflxException("'$id' is not a usable extension name")

        val target = directoryOf(id)
        val staging = File(root, "$id.installing")
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw SflxException("could not create $staging")

        val staged = runCatching {
            File(staging, MANIFEST_FILE).writeText(packageFile.manifest.raw.toString())
            File(staging, SCRIPT_FILE).writeText(packageFile.script)
            packageFile.icon?.let { File(staging, ICON_FILE).writeBytes(it) }
            File(staging, META_FILE).writeText(
                buildString {
                    append("{\"sha256\":\"").append(digest).append('"')
                    append(",\"sourceUrl\":\"").append(sourceUrl.replace("\"", "")).append('"')
                    append(",\"installedAt\":").append(System.currentTimeMillis())
                    append('}').append('\n')
                },
            )
            // A fresh install has no listener answers yet, so the settings of
            // whatever was there before are the only thing carried across.
            val previous = File(target, SETTINGS_FILE)
            if (previous.isFile) previous.copyTo(File(staging, SETTINGS_FILE), overwrite = true)
            true
        }.getOrElse { error ->
            staging.deleteRecursively()
            throw SflxException("could not write the package: ${error.message}")
        }
        if (!staged) throw SflxException("could not write the package")

        // The swap. Anything at the destination is removed first: a rename onto
        // a non-empty directory fails, and the old copy has already been copied
        // forward above.
        target.deleteRecursively()
        if (!staging.renameTo(target)) {
            staging.deleteRecursively()
            throw SflxException("could not move the package into place")
        }
        return read(target) ?: throw SflxException("installed package at $target will not read back")
    }

    /**
     * Removes [id] and everything of it — package, icon and settings.
     *
     * @return whether anything was there to remove.
     */
    fun remove(id: String): Boolean {
        if (!isSafeId(id)) return false
        val directory = directoryOf(id)
        // Checked before deleting: [File.deleteRecursively] answers true when
        // there is nothing there, which would report a removal for an
        // extension that was never installed.
        if (!directory.exists()) return false
        return directory.deleteRecursively()
    }

    /** The listener's own answers for the extension's settings block. */
    fun settings(id: String): JsonObject? {
        val file = File(directoryOf(id), SETTINGS_FILE)
        if (!file.isFile) return null
        return runCatching {
            Json.parseToJsonElement(file.readText()) as? JsonObject
        }.getOrNull()
    }

    /** Replaces the listener's answers for [id]. */
    fun saveSettings(id: String, values: JsonObject) {
        if (!isSafeId(id)) return
        val directory = directoryOf(id)
        if (!directory.isDirectory) return
        runCatching { File(directory, SETTINGS_FILE).writeText(values.toString()) }
    }

    private fun directoryOf(id: String): File = File(root, id)

    private fun read(directory: File): InstalledSflx? {
        val manifestFile = File(directory, MANIFEST_FILE)
        val scriptFile = File(directory, SCRIPT_FILE)
        if (!manifestFile.isFile || !scriptFile.isFile) return null
        val manifest = runCatching { SflxManifestParser.parse(manifestFile.readText()) }.getOrNull() ?: return null
        val meta = readMeta(File(directory, META_FILE))
        return InstalledSflx(
            manifest = manifest,
            directory = directory,
            sha256 = meta.first,
            sourceUrl = meta.second,
            installedAt = meta.third,
        )
    }

    /** `(sha256, sourceUrl, installedAt)`, all blank/zero when there is no meta file. */
    private fun readMeta(file: File): Triple<String, String, Long> {
        if (!file.isFile) return Triple("", "", 0L)
        val root = runCatching { Json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull()
            ?: return Triple("", "", 0L)
        fun text(name: String) = (root[name] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        return Triple(
            text("sha256"),
            text("sourceUrl"),
            text("installedAt").toLongOrNull() ?: 0L,
        )
    }

    /**
     * Whether [id] can be a directory name.
     *
     * The manifest names its own install directory, and it arrives from the
     * network — so the name is checked rather than trusted. Lower-case letters,
     * digits, dot, dash and underscore, no leading dot, and nothing that could
     * climb out of the store's own directory.
     */
    private fun isSafeId(id: String): Boolean =
        id.length in 1..64 &&
            id.first().isLetterOrDigit() &&
            id.all { it.isLetterOrDigit() && it.code < 128 || it == '.' || it == '-' || it == '_' } &&
            !id.contains("..")

    companion object {
        const val MANIFEST_FILE = SflxPackages.MANIFEST_ENTRY
        const val SCRIPT_FILE = SflxPackages.SCRIPT_ENTRY
        const val ICON_FILE = SflxPackages.ICON_ENTRY
        const val META_FILE = "meta.json"
        const val SETTINGS_FILE = "settings.json"
    }
}
