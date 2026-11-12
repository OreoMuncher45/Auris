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
 * Builds one playable file from a segmented start.
 *
 * Tidal, Amazon and Apple Music do not hand over a link to a whole song. Their
 * extensions parse the catalogue's own manifest and produce an ordered list of
 * parts — an initialisation segment describing the track's codec and timescale,
 * then the audio itself — which the reference host fetches concurrently and
 * concatenates byte for byte. That is the entire trick: concatenated fMP4 is a
 * valid fMP4, so no remux is needed for the lossy case, and ExoPlayer plays the
 * result exactly as it plays a download.
 *
 * ## Why a file and not a playlist
 *
 * The player is handed a URL. A list of forty unrelated CDN links is not a URL,
 * and teaching the pipeline a second kind of source — one that has to fetch
 * parts, honour per-part headers and survive a seek into a part it has not
 * fetched — would be a second player. The parts are small (a few hundred
 * kilobytes each), the whole song is one to two seconds of a fast connection,
 * and after the first play it is a local file. The wait is shown, not hidden:
 * the caller only gets a URL once there is something to play.
 *
 * ## What is preserved from the extension
 *
 * The headers, exactly. A part URL is frequently bound to the `Origin`,
 * `Referer` or bearer header the extension built it with, and each segment can
 * carry its own on top of the response's — the reference merges the global ones
 * first and lets the segment's win, which is the order here too.
 */
internal object SflxSegments {

    private const val TAG = "BitChordSflxSegments"

    /** Four at a time, as the reference does: enough to hide latency, not a burst. */
    private const val PARALLEL = 4

    /** Nothing legitimate is this large; a runaway list is stopped rather than filled. */
    private const val MAX_BYTES = 512L * 1024 * 1024

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
    private fun nameOf(extensionId: String, key: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$extensionId::$key".toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }
        return "sflx-$digest"
    }
}
