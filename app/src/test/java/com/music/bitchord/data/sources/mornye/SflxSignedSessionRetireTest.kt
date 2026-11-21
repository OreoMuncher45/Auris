package com.music.bitchord.data.sources.mornye

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A client whose record an add-ons import replaced must never write its old
 * record back over the imported one.
 */
class SflxSignedSessionRetireTest {

    private lateinit var server: MockWebServer
    private lateinit var directory: File

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        directory = kotlin.io.path.createTempDirectory("sflx-retire").toFile()
    }

    @After
    fun tearDown() {
        server.shutdown()
        directory.deleteRecursively()
    }

    private fun config() = SignedSessionConfig(
        namespace = "test",
        baseUrl = server.url("/v2/").toString().replaceFirst("http://", "https://"),
        appVersion = "ext-1.0",
        platform = "extension",
        callbackUrl = "mornye://session-grant",
        schemeLabel = "TEST-HMAC-V1",
        headerPrefix = "X-Test-",
        timeWindowSeconds = 300,
        bootstrap = "/bootstrap",
        challenge = "/challenge",
        exchange = "/session/exchange",
        refresh = "/refresh",
    )

    private fun session() = SflxSignedSession(
        extensionId = "demo",
        config = config(),
        directory = directory,
        httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                // Loopback only: map the HTTPS URL production requires onto MockWebServer's socket.
                val request = chain.request()
                chain.proceed(request.newBuilder().url(request.url.newBuilder().scheme("http").build()).build())
            }
            .build(),
    )

    private fun record(installId: String, sessionId: String, expiresIn: Long): String {
        val config = config()
        return JSONObject()
            .put("install_id", installId)
            .put("session_id", sessionId)
            .put("session_secret", "secret-$sessionId")
            .put("expires_at", Instant.now().plusSeconds(expiresIn).toString())
            .put("namespace", config.namespace)
            .put("base_url", config.baseUrl)
            .put("app_version", config.appVersion)
            .put("platform", config.platform)
            .toString()
    }

    private fun file() = File(directory, config().fileName())

    private fun json(code: Int, body: String) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    @Test
    fun aLiveClientStillSaves() {
        val session = session()
        val imported = record("imported", "s1", 7_200)
        file().writeText(imported)
        session.clear()
        assertNotEquals(imported, file().readText())
    }

    @Test
    fun aRetiredClientNeverWritesOverTheImportedRecord() {
        val session = session()
        session.retire()
        val imported = record("imported", "s1", 7_200)
        file().writeText(imported)
        session.clear()
        assertEquals(imported, file().readText())
    }

    @Test
    fun aRefreshInFlightAcrossAnImportCannotWriteItsStaleRecord() {
        // The record is inside the refresh window, so the next signed call refreshes first.
        file().writeText(record("old", "old-session", 600))
        val refreshStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/v2/refresh" -> {
                    refreshStarted.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    json(
                        200,
                        JSONObject()
                            .put("session_id", "refreshed-stale")
                            .put("session_secret", "refreshed-secret")
                            .put("expires_at", Instant.now().plusSeconds(7_200).toString())
                            .toString(),
                    )
                }
                else -> json(200, """{"ok":true}""")
            }
        }
        val session = session()
        val call = thread { runCatching { session.signedFetch("GET", "/stream", "", emptyMap()) } }

        // The refresh has read the old record and is waiting on the network. Now the import runs.
        assertTrue(refreshStarted.await(5, TimeUnit.SECONDS))
        session.retire()
        val imported = record("imported", "imported-session", 7_200)
        file().writeText(imported)
        release.countDown()
        call.join(10_000)

        assertEquals("the imported record survives the late refresh", imported, file().readText())
    }

    @Test
    fun aRetiredClientBootstrapsOnceAndStopsInsteadOfLooping() {
        // No authenticated record on disk, and a gateway that hands out a session on bootstrap.
        val bootstraps = java.util.concurrent.atomic.AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/v2/bootstrap" -> {
                    bootstraps.incrementAndGet()
                    json(
                        200,
                        JSONObject()
                            .put("session_id", "boot")
                            .put("session_secret", "boot-secret")
                            .put("expires_at", Instant.now().plusSeconds(7_200).toString())
                            .toString(),
                    )
                }
                else -> json(200, """{"ok":true}""")
            }
        }
        val session = session()
        session.retire()

        val answer = session.signedFetch("GET", "/stream", "", emptyMap())

        assertEquals(false, answer.optBoolean("ok"))
        assertEquals("one bootstrap, not a loop", 1, bootstraps.get())
    }
}
