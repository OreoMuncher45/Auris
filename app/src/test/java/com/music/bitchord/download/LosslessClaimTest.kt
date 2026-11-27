package com.music.bitchord.download

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.MusicSource
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.StreamRequest
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * A source whose codec claim is not what its stream holds.
 *
 * Found on an emulator: a SoundCloud extension labels its AAC-over-HLS
 * `LOSSLESS`, the lossless walk took it at its word, and the download died in
 * [ManifestFile] with `Stream is mp4a, not FLAC` — no fallback, one failed
 * row. The claim is now read against the init segment before it wins.
 */
class LosslessClaimTest {

    private fun u32(v: Long) = ByteArray(4) { (v shr (8 * (3 - it))).toByte() }

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().also { o -> parts.forEach(o::write) }.toByteArray()
        return u32(8L + body.size) + type.toByteArray(Charsets.ISO_8859_1) + body
    }

    /** A CMAF init segment whose single audio track has sample entry [entry]. */
    private fun init(entry: String, vararg children: ByteArray): ByteArray {
        val stsd = box("stsd", u32(0), u32(1), box(entry, ByteArray(28), *children))
        val trak = box("trak", box("mdia", box("minf", box("stbl", stsd))))
        return box("ftyp", "iso8".toByteArray()) + box("moov", trak)
    }

    private val flacInit = init("fLaC", box("dfLa", u32(0)))
    private val aacInit = init("mp4a", box("esds", u32(0)))

    private fun hls(name: String, codec: String) = SourceStream(
        url = "https://$name.example/track/index.m3u8",
        format = StreamFormat(codec, bitDepth = 16, sampleRateHz = 44_100),
    )

    // ---- Reading the init segment ---------------------------------------------

    @Test
    fun `sample entry is read from the init segment, through CENC to the original format`() {
        assertEquals("fLaC", ManifestFile.sampleEntry(flacInit))
        assertEquals("mp4a", ManifestFile.sampleEntry(aacInit))
        val encrypted = init("enca", box("sinf", box("frma", "mp4a".toByteArray())))
        assertEquals("mp4a", ManifestFile.sampleEntry(encrypted))
        assertNull(ManifestFile.sampleEntry(box("ftyp", "iso8".toByteArray())))
    }

    @Test
    fun `a lossless claim over mp4a is corrected to AAC and stops being lossless`() {
        val honest = ManifestFile.honest(hls("soundcloud", "flac"), "mp4a")
        assertEquals("aac", honest.format.codec)
        assertEquals(false, honest.format.isLossless)
        // Nothing else the source said survives a misnamed codec.
        assertNull(honest.format.bitDepth)
    }

    @Test
    fun `a genuine FLAC claim is left exactly as it was`() {
        val stream = hls("tidal", "flac")
        assertSame(stream, ManifestFile.honest(stream, "fLaC"))
    }

    @Test
    fun `an Atmos claim over an ec-3 entry stands, since JOC lives inside dec3`() {
        val stream = hls("tidal", "eac3-joc")
        assertSame(stream, ManifestFile.honest(stream, "ec-3"))
    }

    @Test
    fun `a FLAC claim over ALAC bytes is filed as ALAC, not by the FLAC writer`() {
        val honest = ManifestFile.honest(hls("module", "flac"), "alac")
        assertEquals("alac", honest.format.codec)
        assertEquals("m4a", DownloadStore.storable(honest.format.codec)?.extension)
    }

    @Test
    fun `a TrueHD claim over lossy ec-3 is corrected and stops being lossless`() {
        val honest = ManifestFile.honest(hls("module", "truehd"), "ec-3")
        assertEquals("eac3", honest.format.codec)
        assertEquals(false, honest.format.isLossless)
        val stream = hls("module", "truehd")
        assertSame(stream, ManifestFile.honest(stream, "mlpa"))
    }

    @Test
    fun `an entry this does not know leaves the claim standing`() {
        val stream = hls("module", "flac")
        assertSame(stream, ManifestFile.honest(stream, "zzzz"))
    }

    @Test
    fun `a non-manifest claim is not probed`() = runBlocking {
        val direct = SourceStream("https://cdn.example/track.flac", StreamFormat("flac"))
        assertSame(direct, ManifestFile.verified(direct))
    }

    // ---- The lossless walk ------------------------------------------------------

    /** A source that matches [TITLE] and answers with [stream]. */
    private class ClaimingSource(
        override val displayName: String,
        private val stream: SourceStream,
    ) : MusicSource {
        override val configId = displayName
        override val kind = SourceKind.MODULE
        override suspend fun health() = SourceHealth.Ok()
        override suspend fun search(
            query: String,
            limit: Int,
            waitForAll: Boolean,
            request: StreamRequest?,
            target: com.music.bitchord.data.sources.TrackMatcher.Target?,
        ) = listOf(Song(videoId = "$displayName-1", title = TITLE, artist = ARTIST, thumbnailUrl = null, durationText = "2:00"))

        override suspend fun stream(trackId: String, request: StreamRequest) = stream
    }

    private val target = TrackMatcher.Target(TITLE, ARTIST, durationSec = 120)

    /** [ManifestFile.verified] with the init segment fetch replaced by a lookup. */
    private fun verifyAgainst(inits: Map<String, ByteArray>): suspend (SourceStream) -> SourceStream = { stream ->
        inits[stream.url]?.let(ManifestFile::sampleEntry)?.let { ManifestFile.honest(stream, it) } ?: stream
    }

    @Test
    fun `a source claiming lossless that serves mp4a is refused and the walk falls through`() = runBlocking {
        val liar = hls("soundcloud", "flac")
        val genuine = hls("qobuz", "flac")
        val verify = verifyAgainst(mapOf(liar.url to aacInit, genuine.url to flacInit))

        val walk = SourceResolver.walkBitExact(
            listOf(ClaimingSource("SoundCloud", liar), ClaimingSource("Qobuz", genuine)),
            target, StreamRequest.Lossless, strictLength = true, verify = verify,
        )

        // The next source's real FLAC wins, not the liar's AAC named .flac.
        assertEquals(genuine.url, walk.winner?.url)
        assertEquals("flac", walk.winner?.format?.codec)
        // The liar is still on the record, as what it is: a lossy candidate
        // for the floor that decides between it and YouTube.
        assertNotNull(walk.bestLossy)
        val (source, lossy) = walk.bestLossy!!
        assertEquals("SoundCloud", source.displayName)
        assertEquals("aac", lossy.format.codec)
    }

    @Test
    fun `a lone liar leaves no lossless winner, so the download goes down a tier`() = runBlocking {
        val liar = hls("soundcloud", "flac")
        val walk = SourceResolver.walkBitExact(
            listOf(ClaimingSource("SoundCloud", liar)),
            target, StreamRequest.Lossless, strictLength = true,
            verify = verifyAgainst(mapOf(liar.url to aacInit)),
        )
        assertNull(walk.winner)
        // Unknown-bitrate AAC never clears the YouTube floor, so this is
        // YouTube's download — and storable() would not file `aac` anyway.
        assertTrue(!SourceResolver.beatsYouTubeAac(walk.bestLossy!!.second.format))
        assertNull(DownloadStore.storable("aac"))
    }

    @Test
    fun `a premium answer that cannot be filed does not end the walk`() = runBlocking {
        val truehd = hls("module", "flac")
        val genuine = hls("qobuz", "flac")
        val walk = SourceResolver.walkBitExact(
            listOf(ClaimingSource("Module", truehd), ClaimingSource("Qobuz", genuine)),
            target, StreamRequest.Lossless, strictLength = true,
            verify = verifyAgainst(mapOf(truehd.url to init("mlpa"), genuine.url to flacInit)),
            fileable = { DownloadStore.storable(it.codec) != null },
        )
        assertEquals(genuine.url, walk.winner?.url)
    }

    @Test
    fun `a genuine FLAC source wins the walk unchanged`() = runBlocking {
        val genuine = hls("qobuz", "flac")
        val walk = SourceResolver.walkBitExact(
            listOf(ClaimingSource("Qobuz", genuine)),
            target, StreamRequest.Lossless, strictLength = true,
            verify = verifyAgainst(mapOf(genuine.url to flacInit)),
        )
        assertEquals(genuine.url, walk.winner?.url)
        assertEquals(genuine.format, walk.winner?.format)
        assertNull(walk.bestLossy)
    }

    private companion object {
        const val TITLE = "Paniyon Sa"
        const val ARTIST = "Atif Aslam"
    }
}
