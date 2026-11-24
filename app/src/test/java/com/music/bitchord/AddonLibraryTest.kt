package com.music.bitchord

import com.music.bitchord.data.sources.addon.AddonClient
import com.music.bitchord.data.sources.addon.AddonLibraryAuth
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The addon's account-library routes, over a real HTTP server: the route, the
 * pagination parameters, and the credential travelling in the header the
 * manifest declared — never in the URL.
 */
class AddonLibraryTest {

    private lateinit var server: MockWebServer

    @Before fun start() {
        server = MockWebServer().apply { start() }
    }

    @After fun stop() {
        server.shutdown()
    }

    private val pageBody = """
        {
          "items": [ {
            "id": "p.1",
            "catalogId": "pl.1",
            "streamId": "stream-1",
            "title": "My Playlist",
            "artist": "Various Artists",
            "artworkURL": "https://example.invalid/a.jpg",
            "duration": 245
          } ],
          "offset": 0,
          "limit": 50,
          "hasMore": false
        }
    """.trimIndent()

    @Test
    fun aCollectionIsReadWithTheDeclaredBearer() {
        server.enqueue(MockResponse().setBody(pageBody))
        val client = AddonClient(server.url("/").toString())
        val page = runBlocking {
            client.libraryCollection(
                collection = "playlists",
                credential = "tok-123",
                auth = AddonLibraryAuth(type = "bearer", header = "Authorization", setting = "libraryToken"),
                limit = 50,
            ).getOrThrow()
        }
        val request = server.takeRequest()
        assertEquals("/library/playlists?offset=0&limit=50", request.path)
        assertEquals("Bearer tok-123", request.getHeader("Authorization"))
        val item = page.items.single()
        assertEquals("My Playlist", item.title)
        assertEquals("pl.1", item.catalogueId)
        assertEquals(245, item.durationSec)
    }

    @Test
    fun aRawHeaderSchemeSendsTheCredentialUnwrapped() {
        server.enqueue(MockResponse().setBody(pageBody))
        val client = AddonClient(server.url("/").toString())
        runBlocking {
            client.libraryCollection("albums", "raw-token", AddonLibraryAuth(type = "header", header = "X-Library"))
                .getOrThrow()
        }
        assertEquals("raw-token", server.takeRequest().getHeader("X-Library"))
    }

    @Test
    fun aRequiredCredentialThatIsMissingFailsWithoutARequest() {
        val client = AddonClient(server.url("/").toString())
        val result = runBlocking {
            client.libraryCollection("songs", "", AddonLibraryAuth(type = "bearer", required = true))
        }
        assertTrue(result.isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun tracksAreReadUnderTheCollection() {
        server.enqueue(MockResponse().setBody(pageBody))
        val client = AddonClient(server.url("/").toString())
        runBlocking {
            client.libraryTracks("playlists", "p.1", "tok", AddonLibraryAuth(type = "none"))
                .getOrThrow()
        }
        assertEquals("/library/playlists/p.1/tracks?offset=0&limit=50", server.takeRequest().path)
    }
}
