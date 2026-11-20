package com.music.bitchord

import com.music.bitchord.data.sources.module.TidalCatalogAuth
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The 401 seen on device, and the catalog-OAuth request which answers it. */
class TidalCatalogAuthTest {

    private lateinit var catalog: MockWebServer
    private lateinit var auth: MockWebServer

    @Before
    fun setUp() {
        catalog = MockWebServer().apply { start() }
        auth = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        catalog.shutdown()
        auth.shutdown()
    }

    private fun client(
        clock: () -> Long = { 0L },
        eligible: (Request) -> Boolean = { true },
    ): OkHttpClient {
        val recovery = TidalCatalogAuth(
            tokenEndpoint = auth.url("/v1/oauth2/token").toString(),
            tokenClient = OkHttpClient(),
            clock = clock,
            isCatalogRequest = eligible,
        )
        return OkHttpClient.Builder().addInterceptor(recovery).build()
    }

    private fun search(client: OkHttpClient): okhttp3.Response = client.newCall(
        Request.Builder()
            .url(catalog.url("/v1/search/tracks?query=example"))
            .header("x-tidal-token", "old-public-token")
            .build(),
    ).execute()

    private fun catalogRound(userRequired: Boolean = true) {
        catalog.enqueue(
            MockResponse()
                .setResponseCode(if (userRequired) 401 else 200)
                .setBody(if (userRequired) USER_REQUIRED else TRACKS),
        )
        if (userRequired) catalog.enqueue(MockResponse().setResponseCode(200).setBody(TRACKS))
    }

    private fun tokenRound(token: String = "test-catalog-token", lifetime: Long = 14_400L) {
        auth.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"access_token":"$token","token_type":"Bearer","expires_in":$lifetime}"""),
        )
    }

    @Test
    fun `a refused public token becomes catalog OAuth on the same URL`() {
        catalogRound()
        tokenRound()

        search(client()).use { response ->
            assertEquals(200, response.code)
            assertEquals(TRACKS, response.body?.string())
        }

        val first = catalog.takeRequest()
        val retry = catalog.takeRequest()
        assertEquals("old-public-token", first.getHeader("x-tidal-token"))
        assertNull(first.getHeader("Authorization"))
        assertEquals(first.requestUrl, retry.requestUrl)
        assertNull("the closed public token does not ride beside OAuth", retry.getHeader("x-tidal-token"))
        assertEquals("Bearer test-catalog-token", retry.getHeader("Authorization"))

        val token = auth.takeRequest()
        assertEquals("POST", token.method)
        assertTrue(token.getHeader("Authorization")?.startsWith("Basic ") == true)
        val form = token.body.readUtf8()
        assertTrue("grant_type=client_credentials" in form)
        assertTrue("client_id=" in form)
        assertTrue("client_secret=" in form)
        assertEquals("no second token request for one recovery", 1, auth.requestCount)
    }

    @Test
    fun `the catalog token is cached and renewed at its own expiry`() {
        var now = 0L
        val client = client(clock = { now })
        repeat(2) {
            catalogRound()
            tokenRound(lifetime = 301)
        }

        search(client).close()
        search(client).close()
        assertEquals("one token serves repeated searches", 1, auth.requestCount)

        now += 1_001L
        catalogRound()
        search(client).close()
        assertEquals("an expired token is replaced, not reused", 2, auth.requestCount)
        assertEquals("each search needed its original and its retry", 6, catalog.requestCount)
    }

    @Test
    fun `an auth outage returns the original 401 unchanged`() {
        catalog.enqueue(MockResponse().setResponseCode(401).setBody(USER_REQUIRED))
        auth.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))

        search(client()).use { response ->
            assertEquals(401, response.code)
            assertEquals(USER_REQUIRED, response.body?.string())
        }
        assertEquals("no retry without a token", 1, catalog.requestCount)
    }

    @Test
    fun `successful and ineligible calls never buy a token`() {
        catalogRound(userRequired = false)
        catalogRound()

        search(client(eligible = { false })).close()
        search(client(eligible = { false })).close()

        assertEquals(2, catalog.requestCount)
        assertEquals(0, auth.requestCount)
    }

    @Test
    fun `only an anonymous HTTPS TIDAL v1 request is eligible`() {
        fun request(
            url: String,
            publicToken: String? = "token",
            authorization: String? = null,
        ) = Request.Builder().url(url).apply {
            publicToken?.let { header("x-tidal-token", it) }
            authorization?.let { header("Authorization", it) }
        }.build()

        assertTrue(TidalCatalogAuth.isTidalCatalogRequest(request("https://tidal.com/v1/search/tracks")))
        assertTrue(TidalCatalogAuth.isTidalCatalogRequest(request("https://api.tidal.com/v1/tracks/1")))
        assertFalse(TidalCatalogAuth.isTidalCatalogRequest(request("http://tidal.com/v1/search/tracks")))
        assertFalse(TidalCatalogAuth.isTidalCatalogRequest(request("https://example.com/v1/search/tracks")))
        assertFalse(TidalCatalogAuth.isTidalCatalogRequest(request("https://api.zarz.moe/v2/dl/tid")))
        assertFalse(TidalCatalogAuth.isTidalCatalogRequest(request("https://tidal.com/v2/search")))
        assertFalse(TidalCatalogAuth.isTidalCatalogRequest(request("https://tidal.com/v1/search/tracks", publicToken = null)))
        assertFalse(
            TidalCatalogAuth.isTidalCatalogRequest(
                request("https://tidal.com/v1/search/tracks", authorization = "Bearer listener-token"),
            ),
        )
    }

    private companion object {
        const val USER_REQUIRED =
            """{"status":401,"subStatus":6007,"userMessage":"A user is required for this endpoint"}"""
        const val TRACKS = """{"items":[{"id":341260060,"title":"Pull Me Under"}],"totalNumberOfItems":1}"""
    }
}
