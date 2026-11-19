package com.music.bitchord

import com.music.bitchord.data.sources.module.QuickJsExecutor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An extension's POST goes out with the `Content-Type` it asked for.
 *
 * Spotify Web 1.10.6 sends its client-token request as `application/json`,
 * and Spotify answers 400 to the `application/json; charset=utf-8` this host
 * used to put in its place, so every Spotify Web search came back empty.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SflxFetchContentTypeTest {

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

    private fun send(method: String, headers: JSONObject, body: String): Pair<String?, String> {
        server.enqueue(MockResponse().setBody("{}"))
        QuickJsExecutor.fetchUrlSync(server.url("/t").toString(), method, headers.toString(), body)
        val request = server.takeRequest()
        return request.getHeader("Content-Type") to request.body.readUtf8()
    }

    @Test
    fun `a post keeps the extension's own content type, with no charset added`() {
        val (type, body) = send("POST", JSONObject().put("Content-Type", "application/json"), """{"a":"é"}""")
        assertEquals("application/json", type)
        assertEquals("""{"a":"é"}""", body)
    }

    @Test
    fun `a form post stays a form post`() {
        val (type, body) = send(
            "POST",
            JSONObject().put("content-type", "application/x-www-form-urlencoded"),
            "grant_type=client_credentials",
        )
        assertEquals("application/x-www-form-urlencoded", type)
        assertEquals("grant_type=client_credentials", body)
    }

    @Test
    fun `a put keeps its content type too`() {
        val (type, _) = send("PUT", JSONObject().put("Content-Type", "text/plain"), "x")
        assertEquals("text/plain", type)
    }

    @Test
    fun `a post that names no content type is still sent as json`() {
        val (type, body) = send("POST", JSONObject(), "{}")
        assertEquals("application/json; charset=utf-8", type)
        assertEquals("{}", body)
    }
}
