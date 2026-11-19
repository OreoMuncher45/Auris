package com.music.bitchord.data.sources.mornye

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.sources.module.ModuleStreamSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Turns an extension's segmented answer into a streamable local HLS playlist.
 *
 * Tidal and Apple Music parse the catalogue manifest into an initialisation
 * segment followed by media parts. Writing those references as M3U lets Media3
 * fetch while playing instead of waiting for a whole hi-res file before the
 * first note. [ManifestFile] reads the same playlist when a download must be
 * saved as one FLAC/M4A; [assemble] remains the legacy eager assembler.
 *
 * HLS has no standard place for arbitrary CDN request headers, so the response
 * and per-part maps are retained by exact segment URL. Playback restores them
 * on each Media3 DataSpec and the downloader uses the same values. Segment
 * values override response-wide ones, exactly as the reference host does.
 */
internal object SflxSegments {

    private const val TAG = "BitChordSflxSegments"

    /** Four at a time, as the reference does: enough to hide latency, not a burst. */
    private const val PARALLEL = 4

    /** Nothing legitimate is this large; a runaway list is stopped rather than filled. */
    private const val MAX_BYTES = 512L * 1024 * 1024
    private const val MAX_HEADER_URLS = 4096

    /** Segment URL → the headers that URL's own extension response required. */
    private val requestHeaders = object : LinkedHashMap<String, Map<String, String>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, String>>): Boolean =
            size > MAX_HEADER_URLS
    }

    /** The exact headers a player or downloader must send for [url], if any were registered. */
    fun headersFor(url: String): Map<String, String>? = synchronized(requestHeaders) {
        requestHeaders[url]
    }

    private fun rememberHeaders(
        segments: List<ModuleStreamSegment>,
        headers: Map<String, String>,
    ) = synchronized(requestHeaders) {
        segments.forEach { segment ->
            // Same precedence as the reference host and the old assembler:
            // response-wide values first, then this part's own overrides.
            requestHeaders[segment.url] = if (segment.headers.isNullOrEmpty()) {
                headers.toMap()
            } else {
                headers + segment.headers
            }
        }
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * A segmented start as an HLS media playlist, so the song is *streamed*.
     *
     * Assembling first meant the listener waited for the whole track before a
     * note played: forty hi-res parts on a slow connection outlast the 20s a
     * substitution is allowed, so the resolve returned nothing and the track
     * never started. A playlist lets Media3 fetch the init segment and play
     * while the rest is still on the network, which is what streaming means.
     *
     * The extension's first part is the initialisation segment — Tidal builds
     * the list as `[initURL].concat(mediaURLs)` — so it becomes `EXT-X-MAP`
     * and the rest become media segments, exactly the shape [ManifestFile]
     * already reads back into one `.flac` for a download.
     *
     * [durationMs] is the track's length where the search row knew it. HLS
     * wants a duration per segment; spreading the real length across the parts
     * keeps the player's timeline and seeking honest instead of inventing a
     * track twice as long as the song.
     */
    fun playlist(
        directory: File,
        extensionId: String,
        key: String,
        segments: List<ModuleStreamSegment>,
        durationMs: Long?,
        headers: Map<String, String> = emptyMap(),
    ): File? {
        val usable = segments.filter { it.url.isNotBlank() }
        // An init segment alone is not a song.
        if (usable.size < 2) return null
        if (!directory.isDirectory && !directory.mkdirs()) return null
        val media = usable.drop(1)
        val seconds = durationMs?.takeIf { it > 0 }?.let { it / 1000.0 / media.size } ?: 10.0
        val targetDuration = kotlin.math.ceil(seconds).toInt().coerceAtLeast(1)
        val body = buildString {
            append("#EXTM3U\n#EXT-X-VERSION:7\n")
            append("#EXT-X-TARGETDURATION:").append(targetDuration).append('\n')
            append("#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n")
            append("#EXT-X-MAP:URI=\"").append(usable.first().url).append("\"\n")
            for (segment in media) {
                append(String.format(java.util.Locale.US, "#EXTINF:%.3f,\n", seconds))
                append(segment.url).append('\n')
            }
            append("#EXT-X-ENDLIST\n")
        }
        // The content, not only the track id, names the file. A quality swap
        // can resolve the same song again while Media3 still has the first
        // playlist open; replacing that pathname swapped its segment list
        // underneath the reader and could end playback at the next boundary.
        val variant = digest(body).take(12)
        val target = File(directory, "${nameOf(extensionId, key)}-$variant.m3u8")
        if (target.isFile) {
            rememberHeaders(usable, headers)
            return target
        }
        return runCatching {
            // Unique temp names also make two simultaneous resolutions of the
            // same track independent. renameTo publishes atomically on Android;
            // never delete the old target first and leave a reader with nothing.
            val tmp = File.createTempFile("${target.name}.", ".tmp", directory)
            tmp.writeText(body)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            rememberHeaders(usable, headers)
            TrackLog.d(TAG, "wrote ${target.name}: ${media.size} parts to stream")
            target
        }.getOrNull()
    }

    /**
     * The assembled file for [key], or null when a part could not be fetched.
     *
     * A file already assembled for this key is reused: the same track asked for
     * again — a re-play, a quality re-check — must not pay for the whole song a
     * second time. The key carries the extension id, so two catalogues that
     * number their tracks alike cannot collide.
     */
    suspend fun assemble(
        directory: File,
        extensionId: String,
        key: String,
        segments: List<ModuleStreamSegment>,
        headers: Map<String, String>,
    ): File? = withContext(Dispatchers.IO) {
        val usable = segments.filter { it.url.isNotBlank() }
        if (usable.isEmpty()) return@withContext null
        if (!directory.isDirectory && !directory.mkdirs()) return@withContext null

        val target = File(directory, "${nameOf(extensionId, key)}.m4a")
        if (target.isFile && target.length() > 0) return@withContext target

        // Parts are written beside the target, so a half-finished assembly is
        // never mistaken for a finished one and can be cleaned up by name.
        val prefix = "${target.name}.part"
        val parts = arrayOfNulls<File>(usable.size)
        val permits = Semaphore(PARALLEL)
        val started = System.currentTimeMillis()

        val fetched = coroutineScope {
            usable.mapIndexed { index, segment ->
                async {
                    permits.withPermit {
                        val merged = if (segment.headers.isNullOrEmpty()) {
                            headers
                        } else {
                            headers + segment.headers
                        }
                        fetch(segment.url, merged, File(directory, "$prefix$index"))
                    }
                }
            }.awaitAll()
        }

        var total = 0L
        fetched.forEachIndexed { index, file ->
            if (file == null) {
                cleanup(directory, prefix, usable.size)
                return@withContext null
            }
            parts[index] = file
            total += file.length()
            if (total > MAX_BYTES) {
                TrackLog.w(TAG, "refusing ${target.name}: parts exceed the size cap")
                cleanup(directory, prefix, usable.size)
                return@withContext null
            }
        }

        val assembled = runCatching {
            val tmp = File(directory, "${target.name}.tmp")
            java.io.FileOutputStream(tmp).use { out ->
                parts.forEach { part ->
                    part!!.inputStream().use { input -> input.copyTo(out) }
                }
                out.flush()
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            target
        }.onFailure {
            TrackLog.w(TAG, "could not assemble ${target.name}: ${it.message}")
        }.getOrNull()

        cleanup(directory, prefix, usable.size)
        if (assembled != null) {
            TrackLog.d(
                TAG,
                "assembled ${target.name} from ${usable.size} parts, " +
                    "${total / 1024} KB in ${System.currentTimeMillis() - started} ms",
            )
        }
        assembled
    }

    /** One part, or null. Redirects are followed by the client itself. */
    private fun fetch(url: String, headers: Map<String, String>, destination: File): File? =
        runCatching {
            val request = Request.Builder().url(url).get()
            headers.forEach { (name, value) ->
                if (name.isNotBlank() && value.isNotBlank()) request.header(name, value)
            }
            client.newCall(request.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    TrackLog.w(TAG, "segment answered ${response.code} — ${url.take(110)}")
                    return@runCatching null
                }
                val body = response.body ?: return@runCatching null
                destination.outputStream().use { out -> body.byteStream().copyTo(out) }
                destination.takeIf { it.length() > 0 }
            }
        }.getOrElse {
            TrackLog.w(TAG, "segment failed: ${it.message} — ${url.take(110)}")
            null
        }

    private fun cleanup(directory: File, prefix: String, count: Int) {
        for (index in 0 until count) {
            runCatching { File(directory, "$prefix$index").delete() }
        }
    }

    /**
     * A filesystem-safe, collision-free name.
     *
     * The digest rather than the id itself: a track id can be a URL, a path
     * fragment or a hundred characters of base64, and none of those belong in a
     * filename. Eight bytes of SHA-256 is enough to keep two ids apart in one
     * directory.
     */
    private fun nameOf(extensionId: String, key: String): String =
        "sflx-${digest("$extensionId::$key").take(16)}"

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
