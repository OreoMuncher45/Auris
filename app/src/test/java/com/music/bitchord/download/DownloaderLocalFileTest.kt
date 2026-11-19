package com.music.bitchord.download

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files

/**
 * A `file://` answer is a real download source, not an error.
 *
 * Tidal answers a lossless track as an init segment plus a run of media
 * segments, and `SflxHost` assembles those into one local file. The download
 * route then handed that path to OkHttp, which refuses the scheme:
 * "Expected URL scheme 'http' or 'https' but was 'file'". The audio had
 * already been fetched and the download still failed.
 */
class DownloaderLocalFileTest {

    @Test
    fun `a file url is copied with its bytes and progress, not fetched`() = runTest {
        val file = Files.createTempFile("tidal-assembled", ".flac").toFile()
        val payload = ByteArray(100_000) { (it % 251).toByte() }
        file.writeBytes(payload)
        val sink = ByteArrayOutputStream()
        val progress = mutableListOf<Pair<Long, Long>>()

        val written = Downloader.fetchDirect(file.toURI().toString(), emptyMap(), sink) { w, total ->
            progress += w to total
        }

        assertArrayEquals(payload, sink.toByteArray())
        assertEquals(payload.size.toLong(), written)
        assertTrue("progress is reported against the file length", progress.isNotEmpty())
        assertEquals(payload.size.toLong(), progress.last().first)
        assertEquals(payload.size.toLong(), progress.last().second)
        file.delete()
    }

    @Test
    fun `a missing file fails instead of writing an empty download`() = runTest {
        val missing = Files.createTempDirectory("tidal-missing").toFile().resolve("gone.flac")
        val failure = runCatching {
            Downloader.fetchDirect(missing.toURI().toString(), emptyMap(), ByteArrayOutputStream()) { _, _ -> }
        }.exceptionOrNull()
        assertTrue("a missing local file is an error, not a silent success", failure != null)
    }
}
