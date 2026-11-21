package com.music.bitchord.download

import com.music.bitchord.data.Http
import com.music.bitchord.data.sources.DolbyAudio
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.mornye.SflxSegments
import com.music.bitchord.download.Mp4Boxes.boxes
import com.music.bitchord.download.Mp4Boxes.child
import com.music.bitchord.download.Mp4Boxes.find
import com.music.bitchord.download.Mp4Boxes.readU32
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.File
import java.io.OutputStream
import java.net.URI
import java.util.Locale

/**
 * A manifest's segments joined back into one ordinary file, so a lossless
 * download is a `.flac` whether it stays in the app or is exported.
 *
 * [OfflineHls] and [OfflineDash] keep a manifest as a package only BitChord can
 * play, which is why [Downloads.routeFor][Downloads] used to decline a manifest
 * outright whenever "export downloads" was on — and declining sent the track to
 * YouTube. With Tidal answering every lossless request with a manifest, that
 * meant an exporting user who heard a 24-bit FLAC in the player got a 131kbps
 * AAC file on disk, with nothing in the log between the two:
 *
 * ```
 *   15:03:44  download: 'Katiya Karun' from Unified (P kuxhagra) at FLAC · 24-bit
 *   15:03:44  downloading Hmell3OeY5A as .m4a (131kbps audio/mp4; codecs="mp4a.40.2", Lossless)
 * ```
 *
 * The segments are CMAF: one initialisation segment and a run of `moof`+`mdat`
 * fragments. How they come back together depends on what is being filed:
 *
 *  - **`.flac`** — the samples *are* FLAC frames, and the sample entry's `dfLa`
 *    holds the stream's own metadata blocks, so the file is `fLaC`, those
 *    blocks, then every frame in order. That is a native FLAC any player opens,
 *    and the one [FlacTagger] tags. STREAMINFO's sample count is rewritten from
 *    the fragments' durations: without it Media3's FLAC reader cannot seek.
 *  - **`.m4a`** — init then fragments, verbatim, as a fragmented MP4. Each
 *    segment's own `styp`/`sidx` is dropped, since a per-segment index read as
 *    the whole file's is wrong; tagging then adds one index for the lot
 *    ([Mp4Sidx]).
 */
internal object ManifestFile {

    /** The extensions this can produce; a manifest bound for anything else stays declined. */
    val EXTENSIONS = setOf("flac", "m4a")

    suspend fun write(
        cacheDir: File,
        url: String,
        headers: Map<String, String>,
        dash: Boolean,
        extension: String,
        sink: OutputStream,
        onProgress: (Long, Long) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        val manifest = open(url, headers, dash)
        val total = manifest.media.size + 1L

        val init = manifest.fetch(manifest.init)
        onProgress(1, total)
        // Lazy, so a hi-res track is never more than one segment in memory.
        val scope = coroutineContext
        val media = manifest.media.asSequence().mapIndexed { i, ref ->
            scope.ensureActive()
            manifest.fetch(ref).also { onProgress(i + 2L, total) }
        }

        assemble(cacheDir, extension, init, media, sink)
        sink.flush()
    }

    /**
     * [stream] with its format corrected to what its initialisation segment
     * actually holds, when that disagrees with what the source claimed.
     *
     * A source's codec is a claim, and until this existed it was first checked
     * by [writeFlac] — after the route was chosen, the pending file opened and
     * every other source passed over. A SoundCloud extension that labels its
     * AAC-over-HLS "LOSSLESS" then failed the download outright
     * (`Stream is mp4a, not FLAC`) instead of losing its place in the lossless
     * walk, which is what the resolver does with a claim it can see through.
     * Asked from [SourceResolver.forDownload][com.music.bitchord.data.sources.SourceResolver.forDownload]
     * before a premium claim is accepted, so a source caught out here is
     * refused like any other and the walk moves on.
     *
     * Only the index and the init segment are fetched — a few kilobytes, off
     * the main thread. Anything that cannot be read (not a manifest, network
     * fault, an entry this does not know) leaves the claim standing: the
     * download itself is where a broken stream reports itself.
     */
    suspend fun verified(stream: SourceStream): SourceStream {
        val claimed = stream.format
        // Only a premium claim decides anything here; a lossy one is never
        // the winner of the walk this guards.
        if (claimed.isLossless != true && !claimed.isDolby) return stream
        val dash = OfflineDash.handles(stream.url)
        val hls = stream.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
        if (!dash && !hls) return stream
        val entry = try {
            withContext(Dispatchers.IO) {
                val manifest = open(stream.url, stream.headers, dash)
                sampleEntry(manifest.fetch(manifest.init))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return stream
        return honest(stream, entry)
    }

    /**
     * The pure half of [verified]: [stream] as it stands when [entry] — the
     * init segment's sample entry type — agrees with its claim, else with the
     * codec [entry] names and nothing else the source said, since a source
     * that misnamed the codec has not described the rest of it either.
     */
    internal fun honest(stream: SourceStream, entry: String): SourceStream {
        val actual = SAMPLE_ENTRIES[entry] ?: return stream
        val claimed = stream.format
        val agrees = when {
            // TrueHD is lossless; a lossy Dolby entry (`ec-3`) does not agree
            // with it even though both are the Dolby family.
            DolbyAudio.codec(claimed.codec) == "truehd" -> DolbyAudio.codec(actual) == "truehd"
            // Dolby claims are judged on family: the JOC flag lives inside
            // `dec3`, so an `ec-3` entry is consistent with an Atmos claim.
            claimed.isDolby -> DolbyAudio.codec(actual) != null
            // A lossless claim has to be lossless *and* file as the same
            // thing: a FLAC claim over ALAC bytes is honest audio but would
            // still be written by the wrong assembler.
            else -> actual == claimed.codec?.lowercase(Locale.ROOT) ||
                (StreamFormat(actual).isLossless == true &&
                    DownloadStore.storable(actual)?.extension == DownloadStore.storable(claimed.codec)?.extension)
        }
        return if (agrees) stream else stream.copy(format = StreamFormat(codec = actual))
    }

    /** The first sample entry's type in an init segment, looking through CENC to the original format. */
    internal fun sampleEntry(init: ByteArray): String? {
        val stsd = find(init, "moov", "trak", "mdia", "minf", "stbl", "stsd") ?: return null
        val entry = boxes(init, stsd.content + 8, stsd.end).firstOrNull() ?: return null
        if (entry.type != "enca") return entry.type
        // AudioSampleEntry fields, then sinf → frma names what was encrypted.
        val sinf = boxes(init, entry.content + 28, entry.end).firstOrNull { it.type == "sinf" } ?: return null
        val frma = child(init, sinf, "frma")?.takeIf { it.end - it.content >= 4 } ?: return null
        return String(init, frma.content, 4, Charsets.ISO_8859_1)
    }

    /** MP4 audio sample entry types, as the codec names [StreamFormat] uses. */
    private val SAMPLE_ENTRIES = mapOf(
        "fLaC" to "flac",
        "alac" to "alac",
        "mp4a" to "aac",
        "Opus" to "opus",
        ".mp3" to "mp3",
        "ac-3" to "ac3",
        "ec-3" to "eac3",
        "ac-4" to "ac4",
        "mlpa" to "truehd",
    )

    /** A manifest's index read and its segment addresses resolved, ready to fetch. */
    private class Opened(val init: String, val media: List<String>, val fetch: (String) -> ByteArray)

    private fun open(url: String, headers: Map<String, String>, dash: Boolean): Opened {
        fun fetch(target: String): ByteArray {
            // A local extension playlist carries per-part requirements that
            // Media3 cannot express in M3U text. Use the same registry as
            // playback, falling back to the response-wide map for remote
            // manifests and ordinary HLS downloads.
            val required = SflxSegments.headersFor(target) ?: headers
            val request = Request.Builder().url(target).apply {
                required.forEach { (name, value) -> header(name, value) }
            }.build()
            return Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Segment failed (HTTP ${response.code})")
                response.body?.bytes() ?: error("Empty segment")
            }
        }

        // An extension's segmented answer is written to a local playlist so
        // the player can stream it; the same file is what a download reads.
        // Its segment URLs are absolute, so there is no base to resolve against.
        val local = url.startsWith("file:", ignoreCase = true)
        val base = if (local) null else url.toHttpUrlOrNull() ?: error("Invalid manifest URL")
        val index = if (local) {
            runCatching { File(URI(url)).readText() }.getOrElse { error("Unreadable playlist: ${it.message}") }
        } else {
            String(fetch(url), Charsets.UTF_8)
        }
        val (initRef, mediaRefs) = if (dash) {
            OfflineDash.parse(index).let { it.initialization to it.media }
        } else {
            hlsSegments(index)
        }
        fun resolve(ref: String) = base?.resolve(ref)?.toString()
            ?: ref.toHttpUrlOrNull()?.toString()
            ?: error("Invalid segment URL")
        return Opened(resolve(initRef), mediaRefs.map(::resolve), ::fetch)
    }

    /** Everything after the fetching: [init] and [media] in, one [extension] file out. */
    internal fun assemble(cacheDir: File, extension: String, init: ByteArray, media: Sequence<ByteArray>, sink: OutputStream) {
        when (extension) {
            "flac" -> writeFlac(cacheDir, init, media, sink)
            "m4a" -> {
                sink.write(init)
                media.forEach { segment ->
                    boxes(segment).filter { it.type != "styp" && it.type != "sidx" }.forEach {
                        sink.write(segment, it.offset, it.size)
                    }
                }
            }
            else -> error("Can't save a .$extension from a manifest")
        }
    }

    /** The init segment and media segments of an HLS media playlist, refusing what [OfflineHls] refuses. */
    internal fun hlsSegments(playlist: String): Pair<String, List<String>> {
        if (!playlist.startsWith("#EXTM3U")) error("Invalid HLS playlist")
        if (playlist.contains("#EXT-X-KEY:") && !playlist.contains("METHOD=NONE")) {
            error("Encrypted HLS cannot be saved")
        }
        val init = Regex("""#EXT-X-MAP:.*URI="([^"]+)"""").find(playlist)?.groupValues?.get(1)
            ?: error("HLS playlist has no initialisation segment")
        val media = playlist.lineSequence().map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .toList()
        if (media.isEmpty()) error("HLS playlist has no fragments")
        return init to media
    }

    /**
     * FLAC-in-MP4 to native FLAC. The frames go to a temporary file first
     * because STREAMINFO, which comes before them, carries a total that is
     * only known once the last fragment is in — and a hi-res track is too
     * large to hold in memory while waiting for it.
     */
    private fun writeFlac(cacheDir: File, init: ByteArray, media: Sequence<ByteArray>, sink: OutputStream) {
        val trak = find(init, "moov", "trak") ?: error("Not an MP4 initialisation segment")
        val trackId = readU32(init, (child(init, trak, "tkhd") ?: error("No track header")).let {
            it.content + if (init[it.content].toInt() == 1) 20 else 12
        })
        val mdhd = find(init, "moov", "trak", "mdia", "mdhd") ?: error("No media header")
        val timescale = readU32(init, mdhd.content + if (init[mdhd.content].toInt() == 1) 20 else 12)
        val stsd = find(init, "moov", "trak", "mdia", "minf", "stbl", "stsd") ?: error("No sample description")
        // stsd: FullBox header and entry count, then the entries.
        val entry = boxes(init, stsd.content + 8, stsd.end).firstOrNull()
        if (entry?.type != "fLaC") error("Stream is ${entry?.type ?: "unknown"}, not FLAC")
        // AudioSampleEntry: 28 bytes of fields before its child boxes.
        val dfla = boxes(init, entry.content + 28, entry.end).firstOrNull { it.type == "dfLa" }
            ?: error("FLAC stream has no dfLa")
        val blocks = metadataBlocks(init.copyOfRange(dfla.content + 4, dfla.end))
        val trex = find(init, "moov", "mvex", "trex")
        val trexDuration = trex?.let { readU32(init, it.content + 12) } ?: 0L
        val trexSize = trex?.let { readU32(init, it.content + 16) } ?: 0L

        val frames = File.createTempFile("manifest-", ".flac", cacheDir)
        try {
            var ticks = 0L
            frames.outputStream().buffered().use { out ->
                media.forEach { segment ->
                    boxes(segment).filter { it.type == "moof" }.forEach { moof ->
                        val samples = Mp4Boxes.samples(segment, moof, trackId, trexDuration, trexSize)
                            ?: error("Unreadable FLAC fragment")
                        samples.forEach {
                            out.write(segment, it.offset, it.size)
                            ticks += it.duration
                        }
                    }
                }
            }
            if (ticks <= 0) error("Manifest held no audio")

            val info = blocks.first().second
            val rate = ((info[10].toLong() and 0xFF) shl 12) or ((info[11].toLong() and 0xFF) shl 4) or
                ((info[12].toLong() and 0xFF) shr 4)
            val totalSamples = if (timescale > 0 && rate > 0) ticks * rate / timescale else ticks
            info[13] = ((info[13].toInt() and 0xF0) or ((totalSamples shr 32).toInt() and 0x0F)).toByte()
            for (i in 0 until 4) info[14 + i] = (totalSamples shr (8 * (3 - i))).toByte()

            sink.write("fLaC".toByteArray(Charsets.ISO_8859_1))
            blocks.forEachIndexed { i, (type, body) ->
                val last = if (i == blocks.lastIndex) 0x80 else 0
                sink.write(byteArrayOf((last or type).toByte(), (body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte()))
                sink.write(body)
            }
            frames.inputStream().use { it.copyTo(sink) }
        } finally {
            frames.delete()
        }
    }

    /** `dfLa`'s payload as (type, body) pairs, STREAMINFO first as FLAC requires. */
    private fun metadataBlocks(payload: ByteArray): List<Pair<Int, ByteArray>> {
        val out = mutableListOf<Pair<Int, ByteArray>>()
        var p = 0
        while (p + 4 <= payload.size) {
            val type = payload[p].toInt() and 0x7F
            val length = ((payload[p + 1].toInt() and 0xFF) shl 16) or
                ((payload[p + 2].toInt() and 0xFF) shl 8) or (payload[p + 3].toInt() and 0xFF)
            if (p + 4 + length > payload.size) break
            out += type to payload.copyOfRange(p + 4, p + 4 + length)
            val last = payload[p].toInt() and 0x80 != 0
            p += 4 + length
            if (last) break
        }
        if (out.firstOrNull()?.let { it.first == 0 && it.second.size == 34 } != true) error("FLAC stream has no STREAMINFO")
        return out
    }
}
