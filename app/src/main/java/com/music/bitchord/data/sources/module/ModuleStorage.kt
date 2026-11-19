package com.music.bitchord.data.sources.module

import java.io.File

/**
 * Where a module or extension keeps state between runs.
 *
 * A JS module in this app is one interpreter per module, thrown away whenever
 * the pool evicts it or the process dies — so anything a module wants to
 * remember past a single call (a session token, a device id, a cookie jar, a
 * region) has to live out here. The contract is deliberately tiny and
 * synchronous: JavaScript calls `storage.get(key)` and gets an answer, because
 * that is what the modules written for this host expect, and a promise there
 * would have to be awaited by code that was not written to await it.
 *
 * Synchronous means the bindings that call this run on the interpreter's own
 * thread — a `Dispatchers.Default` worker, never the main thread — so a file
 * read here cannot stall a frame. It can still stall that one module's call,
 * which is the price of a key/value store a script can use inline.
 *
 * Two namespaces, one store: [set] is state the extension can hold openly,
 * while secrets are kept apart by the caller (see [SflxModuleStorage]) so a
 * token is never written into the same slot as a genre preference.
 */
interface ModuleStorage {

    /** The text stored under [moduleId]/[key], or null when nothing is. */
    fun get(moduleId: String, key: String): String?

    /** Stores [value] under [moduleId]/[key]. */
    fun set(moduleId: String, key: String, value: String)

    /** Forgets [moduleId]/[key]. Forgetful is not an error. */
    fun remove(moduleId: String, key: String)

    /** Every key [moduleId] currently holds. Used for cleanup and for tests. */
    fun keys(moduleId: String): List<String>
}

/**
 * The default when no store has been installed: kept for the life of the
 * process and no longer.
 *
 * Deliberately not a no-op. A module that silently loses every write behaves
 * differently from one that never tried, and the difference shows up as "the
 * extension asks for a new token on every track" rather than as a crash — which
 * is much harder to read. Nothing is written to disk, so a test or a preview
 * run cannot leave state behind for the next one.
 */
class MemoryModuleStorage : ModuleStorage {
    private val values = mutableMapOf<String, MutableMap<String, String>>()

    override fun get(moduleId: String, key: String): String? = values[moduleId]?.get(key)

    override fun set(moduleId: String, key: String, value: String) {
        values.getOrPut(moduleId) { mutableMapOf() }[key] = value
    }

    override fun remove(moduleId: String, key: String) {
        values[moduleId]?.remove(key)
    }

    override fun keys(moduleId: String): List<String> = values[moduleId]?.keys?.toList().orEmpty()
}

/**
 * The store that survives a restart: one JSON file per module under [root].
 *
 * ## Why a file per module rather than one file for all of them
 *
 * A module's writes are the thing that goes wrong — a script that stores a
 * growing blob, or a key it never clears — and one file per module means the
 * damage is bounded to the module that did it. It also means an extension can
 * be uninstalled by deleting its directory, with nothing of it left behind in
 * a shared file that another module is still writing to.
 *
 * ## Why a write is a whole-file rewrite, done atomically
 *
 * The values are small (tokens, ids, a cookies blob) and read once at start
 * up, so a full rewrite is the cheap and obvious thing. It is written to a
 * sibling `.tmp` and renamed over the original, so a process death mid-write
 * leaves the previous values rather than half a JSON document — which would
 * read as "the extension lost its session and also corrupted its settings".
 */
class FileModuleStorage(private val root: File) : ModuleStorage {

    override fun get(moduleId: String, key: String): String? = read(moduleId)[key]

    override fun set(moduleId: String, key: String, value: String) {
        if (value.length > MAX_VALUE_CHARS) return
        write(moduleId, read(moduleId) + (key to value))
    }

    override fun remove(moduleId: String, key: String) {
        val current = read(moduleId)
        if (key !in current) return
        write(moduleId, current - key)
    }

    override fun keys(moduleId: String): List<String> = read(moduleId).keys.toList()

    /**
     * Everything [moduleId] holds, or nothing when the file is missing or
     * unreadable.
     *
     * A file that will not parse is treated as empty rather than fatal, and it
     * is *not* deleted here: a corrupt file is evidence, and the next write to
     * this module overwrites it anyway.
     */
    private fun read(moduleId: String): Map<String, String> {
        val file = fileOf(moduleId) ?: return emptyMap()
        if (!file.isFile) return emptyMap()
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyMap()
        return runCatching {
            val root = org.json.JSONObject(text)
            buildMap {
                for (key in root.keys()) {
                    root.optString(key, "").takeIf { root.has(key) }?.let { put(key, it) }
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun write(moduleId: String, values: Map<String, String>) {
        val file = fileOf(moduleId) ?: return
        val document = org.json.JSONObject().apply { values.forEach { (key, value) -> put(key, value) } }
        runCatching {
            if (!file.parentFile.isDirectory && !file.parentFile.mkdirs()) return
            val staging = File(file.parentFile, "${file.name}.tmp")
            staging.writeText(document.toString())
            if (!staging.renameTo(file)) {
                file.writeText(document.toString())
                staging.delete()
            }
        }
    }

    /**
     * The file for [moduleId], or null when the id cannot name one.
     *
     * The same check the install store makes, for the same reason: the name
     * arrives from a package's manifest, so it is not allowed to climb out of
     * [root]. Unusual here in that a rejected id is not an error the caller
     * sees — an extension with an unusable name simply cannot persist
     * anything, which is a better outcome than a path traversal.
     */
    private fun fileOf(moduleId: String): File? {
        if (moduleId.isEmpty() || moduleId.length > MAX_ID_CHARS) return null
        if (moduleId.contains("..") || moduleId.contains('/') || moduleId.contains('\\')) return null
        if (!moduleId.first().isLetterOrDigit()) return null
        return File(root, "$moduleId.json")
    }

    companion object {
        /** Names longer than this are not extension names, they are an attack. */
        private const val MAX_ID_CHARS = 64

        /**
         * One value's ceiling, ~1 MiB of UTF-16.
         *
         * A token or a cookie blob is kilobytes. A module trying to write a
         * megabyte into the key/value store is not storing state, it is using
         * this as a scratch disk — refuse quietly rather than let it grow a
         * file that every later read has to parse.
         */
        private const val MAX_VALUE_CHARS = 1 shl 19
    }
}
