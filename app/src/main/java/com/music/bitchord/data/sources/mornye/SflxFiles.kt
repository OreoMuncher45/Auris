package com.music.bitchord.data.sources.mornye

import com.music.bitchord.data.sources.module.QuickJsExecutor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * One extension's private folder, and the few file calls it may make in it.
 *
 * Deezer's extension downloads an encrypted file, then asks the host to
 * decrypt it — `file.transformPatternedBlocks`, the reference host's
 * primitive — and then deletes the encrypted copy. Those three calls are
 * the whole surface: [download], [transform] and [delete]. Nothing else of
 * the reference host's file API is offered, because no extension needs it
 * here, and every call is one more way out of the folder.
 *
 * ## The folder
 *
 * [root] is the extension's own directory under the app cache. A path is
 * either relative to it, or absolute and inside it (the host hands the
 * extension an absolute output path, and the extension derives its other
 * paths from that one). Everything else is refused:
 *
 *  - a `..` segment anywhere in the text, before any cleaning that would
 *    hide it;
 *  - an absolute path outside the folder;
 *  - a path that passes through a symbolic link, or names one, or names
 *    anything that is not a plain file;
 *  - a NUL byte.
 *
 * Writes go to a fresh staging file beside the target and are renamed over
 * it, so a symbolic link planted at the target is replaced rather than
 * followed, and a reader never sees half a file.
 *
 * ## The cap
 *
 * The folder is held to [capBytes]. Before a write, the oldest files that
 * have not been touched for [IDLE_MS] are removed until the new file fits;
 * a write that still does not fit is refused, and one that grows past the
 * room it was given is stopped. The decrypted tracks this folder fills with
 * are a cache — the stream answer that names one is kept for five minutes,
 * and an answer whose file has gone is asked for again — so evicting the
 * oldest is safe.
 *
 * ## Speed
 *
 * The decryption is native and runs over the file once, 1 MiB at a time,
 * with one block cipher initialised once. CBC decryption is done as ECB
 * plus an XOR with the previous ciphertext block, which is what CBC
 * decryption is, and it avoids a key schedule per segment: Blowfish's costs
 * 521 block encryptions, and a track has thousands of segments.
 */
internal class SflxFiles(
    root: File,
    /** The hosts the extension's manifest declares, or null for no restriction. */
    private val allowedHosts: List<String>?,
    private val client: OkHttpClient = defaultClient,
    private val capBytes: Long = DEFAULT_CAP_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val root: File = root.apply { mkdirs() }.canonicalFile
    private val rootPath: Path = this.root.toPath()

    /**
     * The bridge `__spine.fileCall(method, argsJson)` answers through.
     *
     * Every answer is JSON in the reference host's shapes: `{success: true,
     * path, …}` or `{success: false, error}`. Nothing is thrown into the
     * extension, because the reference answers a refusal the same way and an
     * extension's own error path reads `success`.
     */
    fun call(method: String, argsJson: String): String {
        val args = runCatching { JSONArray(argsJson) }.getOrElse { JSONArray() }
        val answer = runCatching {
            when (method) {
                "download" -> download(
                    url = args.optString(0),
                    path = args.optString(1),
                    headers = args.optJSONObject(2)?.let { headers ->
                        headers.keys().asSequence().associateWith { headers.optString(it) }
                    }.orEmpty(),
                )
                "transformPatternedBlocks" -> transform(
                    input = args.optString(0),
                    output = args.optString(1),
                    options = args.optJSONObject(2) ?: JSONObject(),
                )
                "delete" -> delete(args.optString(0))
                else -> throw SflxFileException("unknown file operation")
            }
        }.getOrElse { error ->
            JSONObject().put("success", false).put("error", error.message ?: "file operation failed")
        }
        return answer.toString()
    }

    // ── Paths ────────────────────────────────────────────────────────────

    /**
     * [path] inside the folder, or a refusal.
     *
     * The text is checked before it is cleaned: cleaning `a/../../b` gives a
     * path that looks harmless, and `..` is refused wherever it appears.
     */
    fun resolve(path: String): File {
        if (path.isBlank()) throw SflxFileException("path is required")
        if (path.indexOf('\u0000') >= 0) throw SflxFileException("file access denied: invalid path")
        if (path.split('/', '\\').any { it == ".." }) {
            throw SflxFileException("file access denied: path '$path' is outside sandbox")
        }
        val given = File(path)
        val target = (if (given.isAbsolute) given else File(root, path)).toPath().normalize()
        if (target == rootPath || !target.startsWith(rootPath)) {
            throw SflxFileException(
                if (given.isAbsolute) {
                    "file access denied: absolute paths are not allowed. Use relative paths within extension sandbox"
                } else {
                    "file access denied: path '$path' is outside sandbox"
                },
            )
        }
        // No step of the way may be a link: a link inside the folder is a
        // door out of it.
        var step = target
        while (step != rootPath) {
            if (Files.isSymbolicLink(step)) {
                throw SflxFileException("file access denied: path must not contain symlinks or special files")
            }
            step = step.parent ?: break
        }
        return target.toFile()
    }

    /** [path] as an existing plain file in the folder, or a refusal. */
    private fun existingFile(path: String): File {
        val file = resolve(path)
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw SflxFileException("file not found: ${relative(file)}")
        }
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw SflxFileException("file access denied: path must not contain symlinks or special files")
        }
        return file
    }

    private fun relative(file: File): String = rootPath.relativize(file.toPath()).toString()

    // ── download ─────────────────────────────────────────────────────────

    /**
     * Fetches [url] into [path]: `file.download(url, path, {headers})`.
     *
     * The URL is held to the manifest's hosts, and so is every redirect: a
     * client that followed redirects on its own would let an allowed host
     * hand the download to any other.
     */
    fun download(url: String, path: String, headers: Map<String, String>): JSONObject {
        if (url.isBlank()) throw SflxFileException("URL and output path are required")
        val target = resolve(path)
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            if (!QuickJsExecutor.mayReach(current, allowedHosts)) {
                throw SflxFileException("Blocked: ${hostOf(current)} is not permitted by this extension's manifest")
            }
            val request = Request.Builder().url(current)
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isRedirect) {
                    val next = response.header("Location")
                        ?.let { response.request.url.resolve(it)?.toString() }
                        ?: throw SflxFileException("redirect without a location")
                    current = next
                    return@repeat
                }
                if (response.code !in 200..299) throw SflxFileException("HTTP ${response.code}")
                val body = response.body ?: throw SflxFileException("empty response")
                val stated = body.contentLength().takeIf { it >= 0 }
                val written = body.byteStream().use { source -> publish(target, stated ?: 0L) { sink -> copyCapped(source, sink) } }
                if (stated != null && written != stated) {
                    throw SflxFileException("download stopped at $written of $stated bytes")
                }
                return JSONObject()
                    .put("success", true)
                    .put("path", target.absolutePath)
                    .put("size", written)
                    .put("status", response.code)
            }
        }
        throw SflxFileException("too many redirects")
    }

    private fun hostOf(url: String): String = runCatching { java.net.URI(url).host }.getOrNull() ?: url

    // ── delete ───────────────────────────────────────────────────────────

    /** `file.delete(path)`: the file, gone. A directory is not a file. */
    fun delete(path: String): JSONObject {
        val file = existingFile(path)
        Files.delete(file.toPath())
        return JSONObject().put("success", true)
    }

    // ── transformPatternedBlocks ─────────────────────────────────────────

    /**
     * `file.transformPatternedBlocks(input, output, options)`, as the
     * reference host defines it.
     *
     * The file is cut into `segmentSize` pieces, and every `transformEvery`th
     * one, starting at `transformOffset`, is encrypted or decrypted on its
     * own, each restarting from the same IV; the rest pass through unchanged.
     * A short last piece is left alone unless `transformPartial` is set.
     * Ciphers: AES (16, 24 or 32-byte keys) and Blowfish (1–56 bytes), in CBC
     * or CTR, with no padding.
     */
    fun transform(input: String, output: String, options: JSONObject): JSONObject {
        val spec = TransformSpec.from(options)
        val source = existingFile(input)
        val target = resolve(output)
        val total = source.length()
        val engine = BlockTransform(spec)
        val buffer = ByteArray(spec.bufferSize)
        var segments = 0L
        var transformed = 0L
        val processed = Files.newByteChannel(source.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            Channels.newInputStream(channel).use { stream ->
                publish(target, total) { sink ->
                    var done = 0L
                    while (true) {
                        val count = readFully(stream, buffer)
                        if (count == 0) break
                        var offset = 0
                        while (offset < count) {
                            val length = minOf(spec.segmentSize, count - offset)
                            if (segments % spec.every == spec.offset &&
                                (length == spec.segmentSize || spec.partial)
                            ) {
                                engine.apply(buffer, offset, length, segments)
                                transformed++
                            }
                            segments++
                            offset += length
                        }
                        sink.write(buffer, 0, count)
                        done += count
                    }
                    done
                }
            }
        }
        return JSONObject()
            .put("success", true)
            .put("path", target.absolutePath)
            .put("bytes_processed", processed)
            .put("segments_processed", segments)
            .put("segments_transformed", transformed)
    }

    /** Fills [buffer] as far as the stream allows; 0 only at the end. */
    private fun readFully(stream: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val count = stream.read(buffer, total, buffer.size - total)
            if (count < 0) break
            total += count
        }
        return total
    }

    // ── Writing ──────────────────────────────────────────────────────────

    /** Room left for the file being written, set by [publish] for [copyCapped]. */
    private val room = ThreadLocal<Long>()

    /**
     * Writes a file the safe way: into a fresh staging file beside [target],
     * then renamed over it. [expected] is how much room to make first.
     */
    private fun publish(target: File, expected: Long, write: (java.io.OutputStream) -> Long): Long {
        val parent = target.parentFile ?: throw SflxFileException("invalid path")
        if (!parent.isDirectory && !parent.mkdirs()) throw SflxFileException("failed to create directory")
        // The parent may have been created just now: check the way again, since
        // a link there would take the staging file outside.
        resolve(target.path)
        val budget = makeRoom(expected, keep = target)
        val staged = File.createTempFile(".${target.name}.", ".partial", parent)
        try {
            room.set(budget)
            val written = staged.outputStream().buffered(BUFFER_BYTES).use { sink -> write(sink).also { sink.flush() } }
            if (written > budget) throw SflxFileException(capMessage())
            Files.move(staged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return written
        } catch (error: Throwable) {
            staged.delete()
            throw error
        } finally {
            room.remove()
        }
    }

    /** A copy that stops when the folder's cap is reached. */
    private fun copyCapped(source: InputStream, sink: java.io.OutputStream): Long {
        val budget = room.get() ?: Long.MAX_VALUE
        val buffer = ByteArray(BUFFER_BYTES)
        var written = 0L
        while (true) {
            val count = source.read(buffer)
            if (count < 0) break
            written += count
            if (written > budget) throw SflxFileException(capMessage())
            sink.write(buffer, 0, count)
        }
        return written
    }

    /**
     * Frees space for [bytes] more, oldest idle file first, and answers how
     * much the folder can now take. [keep] is never removed: it is the file
     * about to be replaced.
     */
    @Synchronized
    private fun makeRoom(bytes: Long, keep: File): Long {
        val files = root.walkTopDown().filter { it.isFile }.toList()
        var used = files.sumOf { it.length() }
        if (used + bytes > capBytes) {
            val now = clock()
            files.filter { it != keep && now - it.lastModified() >= IDLE_MS }
                .sortedBy { it.lastModified() }
                .forEach { old ->
                    if (used + bytes <= capBytes) return@forEach
                    val size = old.length()
                    if (old.delete()) used -= size
                }
        }
        // The file being replaced gives its own space back when it is.
        val reclaimed = if (keep.isFile) keep.length() else 0L
        val free = capBytes - used + reclaimed
        if (bytes > free) throw SflxFileException(capMessage())
        return free
    }

    private fun capMessage(): String = "extension storage is full (limit ${capBytes / (1024 * 1024)} MiB)"

    // ── The cipher ───────────────────────────────────────────────────────

    /** The options of one transform, checked as the reference host checks them. */
    internal class TransformSpec(
        val decrypt: Boolean,
        val algorithm: String,
        val ctr: Boolean,
        val key: ByteArray,
        val iv: ByteArray,
        val segmentSize: Int,
        val every: Long,
        val offset: Long,
        val bufferSize: Int,
        val partial: Boolean,
    ) {
        companion object {
            fun from(options: JSONObject): TransformSpec {
                val algorithm = options.optString("algorithm").lowercase()
                if (algorithm.isEmpty()) throw SflxFileException("algorithm is required")
                if (algorithm != "aes" && algorithm != "blowfish") {
                    throw SflxFileException("unsupported block cipher algorithm: $algorithm")
                }
                val mode = options.optString("mode", "cbc").lowercase()
                if (mode != "cbc" && mode != "ctr") throw SflxFileException("unsupported block cipher mode: $mode")
                if (options.optString("padding", "none").lowercase() != "none") {
                    throw SflxFileException("patterned file transforms only support padding: none")
                }
                val operation = options.optString("operation", "decrypt").lowercase()
                if (operation != "decrypt" && operation != "encrypt") {
                    throw SflxFileException("operation must be decrypt or encrypt")
                }
                val key = decode(options, "key", "keyEncoding", "invalid key")
                if (key.isEmpty()) throw SflxFileException("key is required")
                when (algorithm) {
                    "aes" -> if (key.size != 16 && key.size != 24 && key.size != 32) {
                        throw SflxFileException("crypto/aes: invalid key size ${key.size}")
                    }
                    else -> if (key.size > 56) throw SflxFileException("crypto/blowfish: invalid key size ${key.size}")
                }
                val iv = decode(options, "iv", "ivEncoding", "invalid iv")
                val blockSize = if (algorithm == "aes") 16 else 8
                val segmentSize = options.optLong("segmentSize", 0)
                if (segmentSize !in 1..MAX_SEGMENT.toLong()) {
                    throw SflxFileException("segmentSize must be between 1 and $MAX_SEGMENT bytes")
                }
                val every = options.optLong("transformEvery", 1)
                if (every <= 0) throw SflxFileException("transformEvery must be greater than zero")
                val offset = options.optLong("transformOffset", 0)
                if (offset < 0 || offset >= every) {
                    throw SflxFileException("transformOffset must be between 0 and transformEvery - 1")
                }
                if (iv.size != blockSize) throw SflxFileException("iv must be $blockSize bytes for $algorithm")
                if (mode == "cbc" && segmentSize % blockSize != 0L) {
                    throw SflxFileException("segmentSize must be a multiple of $blockSize bytes for CBC")
                }
                val requested = options.optLong("bufferSize", 1L shl 20).coerceIn(segmentSize, MAX_SEGMENT.toLong())
                return TransformSpec(
                    decrypt = operation == "decrypt",
                    algorithm = algorithm,
                    ctr = mode == "ctr",
                    // Go accepts a Blowfish key of one to three bytes and the
                    // JCE does not; repeating it is the same key schedule.
                    key = if (algorithm == "blowfish" && key.size < 4) {
                        val times = (4 + key.size - 1) / key.size
                        ByteArray(times * key.size) { key[it % key.size] }
                    } else {
                        key
                    },
                    iv = iv,
                    segmentSize = segmentSize.toInt(),
                    every = every,
                    offset = offset,
                    bufferSize = (requested - requested % segmentSize).toInt(),
                    partial = options.optBoolean("transformPartial", false),
                )
            }

            private fun decode(options: JSONObject, field: String, encodingField: String, what: String): ByteArray {
                val value = options.optString(field, "")
                return runCatching {
                    when (options.optString(encodingField, "utf8").lowercase()) {
                        "utf8", "utf-8", "text" -> value.toByteArray(Charsets.UTF_8)
                        "hex" -> {
                            require(value.length % 2 == 0)
                            ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                        }
                        "base64" -> Base64.getDecoder().decode(value)
                        else -> throw IllegalArgumentException("unsupported encoding")
                    }
                }.getOrElse { throw SflxFileException("$what: ${it.message ?: "could not be decoded"}") }
            }
        }
    }

    /**
     * One selected segment, transformed in place.
     *
     * CBC decryption and CTR both go through one ECB cipher, set up once.
     * CBC encryption cannot be done that way — each block needs the one
     * before it — so it uses a CBC cipher re-initialised per segment; it is
     * not on the playback path.
     */
    private class BlockTransform(private val spec: TransformSpec) {
        private val name = if (spec.algorithm == "aes") "AES" else "Blowfish"
        private val keySpec = SecretKeySpec(spec.key, name)
        private val blockSize = spec.iv.size
        private val ecb: Cipher = Cipher.getInstance("$name/ECB/NoPadding").apply {
            init(if (spec.decrypt && !spec.ctr) Cipher.DECRYPT_MODE else Cipher.ENCRYPT_MODE, keySpec)
        }
        private val cbc: Cipher? = if (!spec.decrypt && !spec.ctr) Cipher.getInstance("$name/CBC/NoPadding") else null
        private val scratch = ByteArray(spec.segmentSize + blockSize)

        fun apply(data: ByteArray, offset: Int, length: Int, index: Long) {
            when {
                spec.ctr -> ctr(data, offset, length)
                spec.decrypt -> cbcDecrypt(data, offset, length, index)
                else -> cbcEncrypt(data, offset, length, index)
            }
        }

        private fun cbcDecrypt(data: ByteArray, offset: Int, length: Int, index: Long) {
            if (length % blockSize != 0) {
                throw SflxFileException("selected segment $index is not a multiple of $blockSize bytes")
            }
            ecb.doFinal(data, offset, length, scratch, 0)
            for (i in 0 until length) {
                val previous = if (i < blockSize) spec.iv[i] else data[offset + i - blockSize]
                scratch[i] = (scratch[i].toInt() xor previous.toInt()).toByte()
            }
            System.arraycopy(scratch, 0, data, offset, length)
        }

        private fun cbcEncrypt(data: ByteArray, offset: Int, length: Int, index: Long) {
            if (length % blockSize != 0) {
                throw SflxFileException("selected segment $index is not a multiple of $blockSize bytes")
            }
            val cipher = cbc!!
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(spec.iv))
            cipher.doFinal(data, offset, length, scratch, 0)
            System.arraycopy(scratch, 0, data, offset, length)
        }

        /** CTR, as Go counts: the whole IV is one big-endian counter. */
        private fun ctr(data: ByteArray, offset: Int, length: Int) {
            val blocks = (length + blockSize - 1) / blockSize
            val counter = spec.iv.copyOf()
            for (block in 0 until blocks) {
                System.arraycopy(counter, 0, scratch, block * blockSize, blockSize)
                for (i in blockSize - 1 downTo 0) {
                    counter[i] = (counter[i] + 1).toByte()
                    if (counter[i].toInt() != 0) break
                }
            }
            ecb.doFinal(scratch, 0, blocks * blockSize, scratch, 0)
            for (i in 0 until length) {
                data[offset + i] = (data[offset + i].toInt() xor scratch[i].toInt()).toByte()
            }
        }
    }

    companion object {
        /** The most one extension may keep in its folder. */
        const val DEFAULT_CAP_BYTES: Long = 1L shl 30

        /** A file younger than this is in use and is not evicted. */
        const val IDLE_MS: Long = 2 * 60 * 1000L

        /** The largest segment and read buffer, as in the reference host. */
        const val MAX_SEGMENT: Int = 16 shl 20

        private const val BUFFER_BYTES = 256 * 1024
        private const val MAX_REDIRECTS = 5

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(120, TimeUnit.SECONDS)
                .build()
        }

        /** A client like [defaultClient], for a test that brings its own. */
        fun client(base: OkHttpClient): OkHttpClient =
            base.newBuilder().followRedirects(false).followSslRedirects(false).build()
    }
}

internal class SflxFileException(message: String) : IOException(message)
