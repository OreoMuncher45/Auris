package com.music.bitchord.data.sources.backup

import com.music.bitchord.data.sources.mornye.SflxStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * One addon or custom-module source as it was configured.
 *
 * [settings] holds what the user typed into the addon's own fields, which in
 * practice means API keys. [toString] leaves the values out, so a row that
 * ends up in a log or an assertion message carries the keys' names only.
 */
@Serializable
data class BackedUpSource(
    /** A [com.music.bitchord.data.sources.SourceKind] name. Kept as text so an unknown kind is skipped, not fatal. */
    val kind: String,
    val label: String = "",
    val baseUrl: String,
    val enabled: Boolean = true,
    val settings: Map<String, String> = emptyMap(),
    val allowDownloads: Boolean = true,
    val checkValidLossless: Boolean = false,
) {
    override fun toString(): String =
        "BackedUpSource(kind=$kind, label=$label, settings=${settings.keys})"
}

/** An installed extension's place on the sources list. Its files travel alongside, in the archive. */
@Serializable
data class BackedUpExtension(
    val id: String,
    val displayName: String = id,
    val version: String = "",
    /** Whether its source row was switched on. Null for a package with no row (lyrics-only). */
    val enabled: Boolean? = null,
)

/** The index of a backup: `addons.json` inside the archive. */
@Serializable
data class AddonBackupIndex(
    val schema: Int = AddonBackupArchive.SCHEMA,
    val exportedAt: Long = 0,
    val appVersion: String = "",
    /** In the order they were tried. */
    val sources: List<BackedUpSource> = emptyList(),
    /** In the order they were tried. */
    val extensions: List<BackedUpExtension> = emptyList(),
)

/**
 * Everything a backup holds, unpacked.
 *
 * [extensionFiles] maps an extension id to its files by name (manifest, script,
 * icon, meta, settings). [sessionFiles] holds signed-session records by file
 * name.
 */
class AddonBackupContents(
    val index: AddonBackupIndex,
    val extensionFiles: Map<String, Map<String, ByteArray>> = emptyMap(),
    val sessionFiles: Map<String, ByteArray> = emptyMap(),
) {
    /** Counts only, so nothing secret reaches a log through this. */
    override fun toString(): String =
        "AddonBackupContents(sources=${index.sources.size}, extensions=${extensionFiles.size}, " +
            "sessions=${sessionFiles.size})"
}

/**
 * The zip inside the encrypted envelope.
 *
 * ```
 * addons.json                  AddonBackupIndex
 * extensions/<id>/<file>       one of [EXTENSION_FILES]
 * sessions/<name>.json         a signed-session record
 * ```
 *
 * Reading is defensive because the file came from outside the app, even
 * though it decrypted: entry names are checked against these exact shapes,
 * and the entry count and total unpacked size are capped. Entries this build
 * doesn't know are skipped, so a later build can add files without breaking
 * this one.
 */
object AddonBackupArchive {

    /** Bump when an older build can no longer read the index correctly. */
    const val SCHEMA = 1

    const val INDEX_ENTRY = "addons.json"
    private const val EXTENSIONS_DIR = "extensions/"
    private const val SESSIONS_DIR = "sessions/"

    /** The only files an extension's directory contributes. Its cache never travels. */
    val EXTENSION_FILES: Set<String> = SflxStore.BACKUP_FILES.toSet()

    internal const val MAX_ENTRIES = 2_000
    internal const val MAX_UNPACKED_BYTES = 64L * 1024 * 1024

    private val SESSION_NAME = Regex("[a-z0-9][a-z0-9._-]{0,127}\\.json")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Whether [name] is a safe file name for a signed-session record. */
    fun isSessionName(name: String): Boolean = SESSION_NAME.matches(name) && !name.contains("..")

    /**
     * Packs [contents].
     *
     * Applies the same entry-count and unpacked-size caps as [read], so every
     * archive this writes can be read back.
     *
     * @throws AddonBackupException TOO_LARGE when either cap would be passed.
     */
    fun write(contents: AddonBackupContents): ByteArray {
        val entries = buildList {
            add(INDEX_ENTRY to json.encodeToString(AddonBackupIndex.serializer(), contents.index).toByteArray())
            contents.extensionFiles.forEach { (id, files) ->
                require(SflxStore.isSafeId(id)) { "unsafe extension id" }
                files.forEach { (name, bytes) ->
                    if (name in EXTENSION_FILES) add("$EXTENSIONS_DIR$id/$name" to bytes)
                }
            }
            contents.sessionFiles.forEach { (name, bytes) ->
                if (isSessionName(name)) add("$SESSIONS_DIR$name" to bytes)
            }
        }
        if (entries.size > MAX_ENTRIES || entries.sumOf { it.second.size.toLong() } > MAX_UNPACKED_BYTES) {
            throw AddonBackupException(AddonBackupFailure.TOO_LARGE)
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /**
     * Unpacks [bytes].
     *
     * @throws AddonBackupException DAMAGED for a zip or index that won't read,
     *   NEWER_VERSION for a schema this build doesn't know, TOO_LARGE past the caps.
     */
    fun read(bytes: ByteArray): AddonBackupContents {
        var index: AddonBackupIndex? = null
        val extensions = linkedMapOf<String, MutableMap<String, ByteArray>>()
        val sessions = linkedMapOf<String, ByteArray>()
        var entries = 0
        var total = 0L
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++entries > MAX_ENTRIES) throw AddonBackupException(AddonBackupFailure.TOO_LARGE)
                    // Every entry is drained through the cap, directories too: skipping one
                    // would let the next nextEntry() inflate its payload with no limit.
                    if (entry.isDirectory) {
                        total += discardCapped(zip, MAX_UNPACKED_BYTES - total)
                        continue
                    }
                    val data = readCapped(zip, MAX_UNPACKED_BYTES - total)
                    total += data.size
                    val name = entry.name
                    when {
                        name == INDEX_ENTRY -> index = decodeIndex(data)
                        name.startsWith(EXTENSIONS_DIR) -> {
                            val rest = name.removePrefix(EXTENSIONS_DIR).split('/')
                            if (rest.size == 2 && SflxStore.isSafeId(rest[0]) && rest[1] in EXTENSION_FILES) {
                                extensions.getOrPut(rest[0]) { linkedMapOf() }[rest[1]] = data
                            }
                        }
                        name.startsWith(SESSIONS_DIR) -> {
                            val file = name.removePrefix(SESSIONS_DIR)
                            if (isSessionName(file)) sessions[file] = data
                        }
                    }
                }
            }
        } catch (e: AddonBackupException) {
            throw e
        } catch (e: ZipException) {
            throw AddonBackupException(AddonBackupFailure.DAMAGED, e)
        } catch (e: java.io.IOException) {
            throw AddonBackupException(AddonBackupFailure.DAMAGED, e)
        }
        val read = index ?: throw AddonBackupException(AddonBackupFailure.DAMAGED)
        // An extension without its manifest and script can't be installed, so it isn't offered.
        val complete = extensions.filterValues {
            SflxStore.MANIFEST_FILE in it && SflxStore.SCRIPT_FILE in it
        }
        return AddonBackupContents(read, complete, sessions)
    }

    private fun decodeIndex(data: ByteArray): AddonBackupIndex {
        val decoded = try {
            json.decodeFromString(AddonBackupIndex.serializer(), data.decodeToString())
        } catch (e: Exception) {
            throw AddonBackupException(AddonBackupFailure.DAMAGED, e)
        }
        if (decoded.schema > SCHEMA) throw AddonBackupException(AddonBackupFailure.NEWER_VERSION)
        return decoded
    }

    /** Reads and drops an entry's bytes, counting them against [remaining]. Returns how many there were. */
    private fun discardCapped(zip: ZipInputStream, remaining: Long): Long {
        val buffer = ByteArray(16 * 1024)
        var read = 0L
        while (true) {
            val n = zip.read(buffer)
            if (n < 0) break
            read += n
            if (read > remaining) throw AddonBackupException(AddonBackupFailure.TOO_LARGE)
        }
        return read
    }

    private fun readCapped(zip: ZipInputStream, remaining: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var read = 0L
        while (true) {
            val n = zip.read(buffer)
            if (n < 0) break
            read += n
            if (read > remaining) throw AddonBackupException(AddonBackupFailure.TOO_LARGE)
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
