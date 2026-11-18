package com.music.bitchord

import com.music.bitchord.data.sources.module.ModuleStreamSegment
import com.music.bitchord.data.sources.mornye.SflxSegments
import com.music.bitchord.download.ManifestFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * A segmented answer is streamed, not downloaded whole before playback.
 *
 * Tidal answers a lossless track as an init segment and a run of media
 * segments. Assembling those first meant a hi-res track on a slow connection
 * never started inside the substitution's 20s. The answer is now an HLS
 * playlist the player streams — and the same playlist the downloader's
 * existing assembler reads back into one `.flac`.
 */
class SflxSegmentsPlaylistTest {

    private fun segments() = listOf("https://cdn.example/init.mp4") +
        (0 until 4).map { "https://cdn.example/$it.mp4?token=abc" }

    @Test
    fun `the init segment becomes the map and the rest become media`() {
        val directory = Files.createTempDirectory("sflx-playlist").toFile()
        val playlist = SflxSegments.playlist(
            directory = directory,
            extensionId = "tidal-web",
            key = "tidal:1",
            segments = segments().map { ModuleStreamSegment(url = it) },
            durationMs = 200_000L,
        )
        val body = playlist!!.readText()
        assertTrue(body.startsWith("#EXTM3U"))
        assertTrue("the first part is the init segment", body.contains("#EXT-X-MAP:URI=\"https://cdn.example/init.mp4\""))
        assertTrue(body.endsWith("#EXT-X-ENDLIST\n"))

        // The downloader reads exactly this shape back.
        val (init, media) = ManifestFile.hlsSegments(body)
        assertEquals("https://cdn.example/init.mp4", init)
        assertEquals((0 until 4).map { "https://cdn.example/$it.mp4?token=abc" }, media)
    }

    @Test
    fun `the track's real length is spread across the parts`() {
        val directory = Files.createTempDirectory("sflx-playlist").toFile()
        val playlist = SflxSegments.playlist(
            directory = directory,
            extensionId = "tidal-web",
            key = "tidal:1",
            segments = segments().map { ModuleStreamSegment(url = it) },
            durationMs = 200_000L,
        )!!
        val body = playlist.readText()
        // 200s over four parts is 50s a part, so the timeline adds up to the
        // song rather than to a nominal duration invented by the host.
        assertTrue(body.contains("#EXTINF:50.000,"))
        assertTrue(body.contains("#EXT-X-TARGETDURATION:50"))
    }

    @Test
    fun `an init segment alone is not a playable answer`() {
        val directory = Files.createTempDirectory("sflx-playlist").toFile()
        val playlist = SflxSegments.playlist(
            directory = directory,
            extensionId = "tidal-web",
            key = "tidal:1",
            segments = listOf(ModuleStreamSegment(url = "https://cdn.example/init.mp4")),
            durationMs = null,
        )
        assertTrue(playlist == null)
    }
}
