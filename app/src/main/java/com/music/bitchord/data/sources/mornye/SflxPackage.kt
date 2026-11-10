package com.music.bitchord.data.sources.mornye

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * A `.sflx` file, read into memory.
 *
 * The format is a zip holding exactly two things that matter: `manifest.json`
 * and `index.js`. Everything else — an icon, a licence, a changelog — is
 * optional, and entries the host does not know are ignored rather than
 * refused, because the format is shared with other hosts.
 *
 * ## Read in memory, and bounded
 *
 * A `.sflx` is a script the listener installs from a URL, so the sizes are
 * capped before anything is decompressed: an archive claiming a two-gigabyte
 * `index.js` must fail on the claim, not on the out-of-memory kill that
 * following it would earn. The cap on the number of entries does the same job
 * for an archive that is nothing but a million empty ones.
 */
data class SflxPackage(
    val manifest: SflxManifest,
    /** `index.js`, the extension's script, exactly as the archive stores it. */
    val script: String,
    /** The icon bytes, when the archive carries one; the registry's URL otherwise. */
    val icon: ByteArray?,
) {
    // ByteArray in a data class compares by reference, which would make two
    // reads of one archive unequal. Only [icon] is affected, and the generated
    // equals is used by tests and by nothing on the playback path, so it is
    // overridden here rather than left as a trap.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** Reads `.sflx` archives. */
object SflxPackages {
    const val MANIFEST_ENTRY = "manifest.json"
    const val SCRIPT_ENTRY = "index.js"
    const val ICON_ENTRY = "icon.png"

    /**
     * The icon's own names, in the order they are preferred.
     *
     * The format's own icon is `icon.png`, but the packages in the wild carry
     * `icon.jpg` too — the YouTube Music one in Mornye's store does — and an
     * icon the host cannot find is an extension that installs with no picture
     * of itself. So the name is matched by prefix rather than exactly: any
     * `icon.*` is the icon, and the first one found wins.
     */
    private val ICON_NAMES = listOf("icon.png", "icon.jpg", "icon.jpeg", "icon.webp")

    /** A manifest is a page of JSON; anything past this is not one. */
    private const val MAX_MANIFEST_BYTES = 512L * 1024

    /**
     * The largest script that will be read.
     *
     * Mornye's own bundled providers are the largest extensions in the wild and
     * run to a few hundred kilobytes; four megabytes is generous for one and
     * small enough that a malformed archive cannot exhaust the heap.
     */
    private const val MAX_SCRIPT_BYTES = 4L * 1024 * 1024
    private const val MAX_ICON_BYTES = 2L * 1024 * 1024

    /** An extension is a script and a manifest; more entries than this is not one. */
    private const val MAX_ENTRIES = 256

    /**
     * Reads [bytes] as a `.sflx`.
     *
     * @throws SflxException when the archive is not a zip, lacks either required
     *   entry, carries one past its cap, or its manifest will not parse.
     */
    fun read(bytes: ByteArray): SflxPackage {
        var manifestText: String? = null
        var scriptText: String? = null
        var icon: ByteArray? = null
        var entries = 0

        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                if (++entries > MAX_ENTRIES) throw SflxException("archive holds more than $MAX_ENTRIES entries")
                when {
                    entry.name.substringAfterLast('/') == MANIFEST_ENTRY ->
                        manifestText = readEntry(zip, entry, MAX_MANIFEST_BYTES).decodeToString()
                    entry.name.substringAfterLast('/') == SCRIPT_ENTRY ->
                        scriptText = readEntry(zip, entry, MAX_SCRIPT_BYTES).decodeToString()
                    // The first icon wins; a second one is ignored rather
                    // than read twice.
                    icon == null && ICON_NAMES.contains(entry.name.substringAfterLast('/').lowercase()) ->
                        icon = readEntry(zip, entry, MAX_ICON_BYTES)
                    else -> Unit
                }
            }
        }

        val manifest = SflxManifestParser.parse(
            manifestText ?: throw SflxException("archive has no $MANIFEST_ENTRY"),
        )
        val script = scriptText ?: throw SflxException("archive has no $SCRIPT_ENTRY")
        if (script.isBlank()) throw SflxException("archive's $SCRIPT_ENTRY is empty")
        return SflxPackage(manifest = manifest, script = script, icon = icon)
    }

    /**
     * SHA-256 of [bytes], lower-case hex — the digest a registry publishes and
     * an install is checked against.
     */
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Reads one entry, refusing it as soon as it is past [limit].
     *
     * The declared size is checked first, and the real one is enforced while
     * reading: a zip entry's own header is written by whoever built the
     * archive, so a small header over a large body is exactly the case a
     * declared-size check alone does not catch.
     */
    private fun readEntry(zip: ZipInputStream, entry: ZipEntry, limit: Long): ByteArray {
        if (entry.size > limit) throw SflxException("${entry.name} is larger than ${limit / 1024}kB")
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = zip.read(buffer)
            if (read <= 0) break
            total += read
            if (total > limit) throw SflxException("${entry.name} is larger than ${limit / 1024}kB")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
