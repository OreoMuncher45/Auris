package com.music.bitchord

import com.music.bitchord.data.sources.mornye.SflxDownloads
import com.music.bitchord.data.sources.mornye.SflxException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A package download with no Content-Length is still held to the size ceiling. */
class SflxDownloadSizeTest {

    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun chunked(size: Int): MockResponse =
        MockResponse().setChunkedBody(Buffer().write(ByteArray(size) { 7 }), 256 * 1024)

    @Test
    fun anUndeclaredBodyPastTheCeilingIsRefused() = runBlocking {
        server.enqueue(chunked(9 * 1024 * 1024))

        val error = SflxDownloads.fetch(server.url("/big.sflx").toString()).exceptionOrNull()

        assertTrue("$error", error is SflxException && error.message.orEmpty().contains("too large"))
    }

    @Test
    fun anUndeclaredBodyUnderTheCeilingIsRead() = runBlocking {
        server.enqueue(chunked(300 * 1024))

        val bytes = SflxDownloads.fetch(server.url("/ok.sflx").toString()).getOrThrow()

        assertArrayEquals(ByteArray(300 * 1024) { 7 }, bytes)
    }
}
