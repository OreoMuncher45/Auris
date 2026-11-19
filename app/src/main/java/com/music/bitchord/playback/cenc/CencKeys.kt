package com.music.bitchord.playback.cenc

import java.io.File
import java.util.Base64
import java.util.Locale

/**
 * The content keys extensions have handed over, by key id.
 *
 * Amazon Music's downloader answers with a per-track key, which the reference
 * host feeds to FFmpeg (`-decryption_key`) once the file is on disk. This app
 * plays the file as it arrives instead, so the key is kept here, under the key
 * id the file itself names in its `tenc` box, and [CencTrackOutput] looks it up
 * per sample. Routing by key id rather than by URL is what lets the same bytes
 * play from the stream, from the audio cache after a restart, and from a
 * download, without each of those paths having to carry the key along.
 *
 * Kept on disk, in the app's no-backup files, because the audio cache keeps the
 * encrypted bytes: a track cached yesterday needs yesterday's key. A download
 * is decrypted when it lands and needs none.
 *
 * Bounded, least recently *used* first out: playing a cached track looks its
 * key up and so keeps it, and a key is only dropped after thousands of other
 * encrypted answers have been resolved or played since — by which point the
 * audio cache, which is bounded too, has long dropped that track's bytes.
 * Sixteen bytes a key, so the cap costs little.
 */
object CencKeys {

    private const val MAX_KEYS = 4096

    /** Access-ordered: the eldest entry is the one least recently used. */
    private val keys = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    @Volatile private var file: File? = null
    /** Whether memory holds something the file does not, so the next write is owed. */
    private var dirty = false

    /** Where the keys are kept. Called once at start-up; until then they live in memory only. */
    fun init(directory: File) {
        val store = File(directory, "cenc-keys.txt")
        synchronized(keys) {
            if (file == store) return
            file = store
            dirty = keys.isNotEmpty()
            runCatching {
                if (store.exists()) store.readLines().forEach { line ->
                    val parts = line.trim().split(' ')
                    if (parts.size == 2) {
                        val key = fromHex(parts[1])
                        if (key != null && key.size == 16 && fromHex(parts[0])?.size == 16) keys.putIfAbsent(parts[0], key)
                    }
                }
            }
            if (dirty) persist()
        }
    }

    /** The key for [kid], or null. */
    fun keyFor(kid: ByteArray): ByteArray? = synchronized(keys) { keys[hex(kid)]?.copyOf() }

    /** Keeps [key] for [kid], both 16 bytes. */
    fun register(kid: ByteArray, key: ByteArray) {
        require(kid.size == 16 && key.size == 16) { "a key id and a key are 16 bytes each" }
        synchronized(keys) {
            val id = hex(kid)
            if (keys[id]?.contentEquals(key) == true && !dirty) return
            keys[id] = key.copyOf()
            while (keys.size > MAX_KEYS) keys.remove(keys.keys.first())
            dirty = true
            persist()
        }
    }

    /**
     * Writes the keys, replacing the file in one step so the last good copy
     * survives a failed write; a failure leaves [dirty] set and the next
     * registration tries again. Called with the lock held.
     */
    private fun persist() {
        val store = file ?: return
        val written = runCatching {
            store.parentFile?.mkdirs()
            val temp = File(store.path + ".tmp")
            temp.writeText(keys.entries.joinToString("") { (k, v) -> "$k ${hex(v)}\n" })
            java.nio.file.Files.move(
                temp.toPath(), store.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        }
        dirty = written.isFailure
    }

    /** For tests: forget everything held in memory, and where it was kept. */
    internal fun clear() = synchronized(keys) {
        keys.clear()
        file = null
        dirty = false
    }

    /**
     * A key as an extension spells it, as 16 bytes: hex (with or without `0x`
     * and separators) or base64, the two spellings the reference host accepts.
     */
    fun parseKey(raw: String?): ByteArray? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val noPrefix = if (trimmed.startsWith("0x", ignoreCase = true)) trimmed.substring(2) else trimmed
        val compact = noPrefix.filter { it.isLetterOrDigit() }
        if (compact.length == 32) fromHex(compact)?.let { return it }
        // Base64 is read as given: `0x` is a hex prefix, but it is also two
        // ordinary Base64 characters.
        val base64 = trimmed.filterNot { it.isWhitespace() }
        return runCatching { Base64.getDecoder().decode(base64) }
            .recoverCatching { Base64.getUrlDecoder().decode(base64) }
            .getOrNull()
            ?.takeIf { it.size == 16 }
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xFF) }

    private fun fromHex(text: String): ByteArray? {
        if (text.length % 2 != 0) return null
        return runCatching { ByteArray(text.length / 2) { i -> text.substring(i * 2, i * 2 + 2).toInt(16).toByte() } }.getOrNull()
    }
}
