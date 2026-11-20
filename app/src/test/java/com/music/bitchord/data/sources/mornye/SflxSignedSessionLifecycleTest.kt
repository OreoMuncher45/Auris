package com.music.bitchord.data.sources.mornye

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Verification remains live, but one grant wakes every caller and dismissal stays dismissed. */
class SflxSignedSessionLifecycleTest {

    private lateinit var server: MockWebServer
    private lateinit var directory: File

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        directory = kotlin.io.path.createTempDirectory("sflx-session").toFile()
    }

    @After
    fun tearDown() {
        server.shutdown()
        directory.deleteRecursively()
    }

    @Test
    fun `an automatic bootstrap session retries the original signed request`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/v2/bootstrap" -> json(200, sessionJson())
                "/v2/stream" -> json(200, """{"audio":"flac"}""")
                else -> json(404, """{}""")
            }
        }
        val session = session()

        val answer = session.signedFetch("GET", "/stream", "", emptyMap())

        assertTrue(answer.toString(), answer.optBoolean("ok"))
        assertEquals("flac", JSONObject(answer.optString("body")).optString("audio"))
        assertTrue(session.snapshot().authenticated)
        assertEquals("bootstrap plus the original request, not a retry by the listener", 2, server.requestCount)
    }

    @Test
    fun `one grant wakes every waiter without a later verified-session prompt`() {
        val prompts = AtomicInteger()
        val waiting = CountDownLatch(2)
        val firstStream = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            private val streams = AtomicInteger()
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/v2/bootstrap" -> json(200, """{"auth_url":"https://verify.example/challenge?state=one"}""")
                "/v2/session/exchange" -> json(200, sessionJson())
                "/v2/stream" -> {
                    if (streams.incrementAndGet() == 1) firstStream.await(2, TimeUnit.SECONDS)
                    json(200, """{"ok":true}""")
                }
                else -> json(404, """{}""")
            }
        }
        val session = session(verificationWaitMs = 700) {
            prompts.incrementAndGet()
            waiting.countDown()
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val calls = List(2) {
                executor.submit<JSONObject> { session.signedFetch("GET", "/stream", "", emptyMap()) }
            }
            assertTrue("both callers reached the same challenge", waiting.await(2, TimeUnit.SECONDS))
            assertTrue(session.completeGrant("grant"))
            firstStream.countDown()

            calls.forEach { call ->
                val answer = call.get(2, TimeUnit.SECONDS)
                assertTrue(answer.toString(), answer.optBoolean("ok"))
            }
            Thread.sleep(800)
            assertTrue(session.snapshot().authenticated)
            assertFalse(session.snapshot().verificationRequired)
            assertEquals("only the two initial waiters asked; neither timed out into another prompt", 2, prompts.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `a dismissed challenge times out without opening itself again`() {
        val prompts = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/v2/bootstrap" -> json(200, """{"auth_url":"https://verify.example/challenge?state=one"}""")
                else -> json(404, """{}""")
            }
        }
        val session = session(verificationWaitMs = 60) { prompts.incrementAndGet() }

        val answer = session.signedFetch("GET", "/stream", "", emptyMap())

        assertFalse(answer.optBoolean("ok"))
        assertTrue(answer.optBoolean("needsVerification"))
        assertEquals("https://verify.example/challenge?state=one", answer.optString("auth_url"))
        assertEquals("the initial request is the only prompt", 1, prompts.get())
        Thread.sleep(100)
        assertEquals("timeout and dismissal do not queue another page", 1, prompts.get())
    }

    @Test
    fun `a gateway revocation still blocks the verified record and prompts once`() {
        val prompts = AtomicInteger()
        writeRecord()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/v2/stream" -> json(
                    428,
                    """{"error":"verify","code":"VERIFY_REQUIRED","origin":"gateway","action":"verify"}""",
                )
                "/v2/bootstrap" -> json(200, """{"auth_url":"https://verify.example/challenge?state=new"}""")
                else -> json(404, """{}""")
            }
        }
        val session = session(verificationWaitMs = 60) { prompts.incrementAndGet() }

        val answer = session.signedFetch("GET", "/stream", "", emptyMap())

        assertTrue(answer.optBoolean("needsVerification"))
        assertTrue(session.snapshot().verificationRequired)
        assertFalse(session.snapshot().authenticated)
        assertEquals("genuine re-verification is not suppressed", 1, prompts.get())
    }

    @Test
    fun `a usable record wins over a stale pending challenge`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/v2/bootstrap" -> json(200, """{"auth_url":"https://verify.example/challenge?state=old"}""")
                else -> json(404, """{}""")
            }
        }
        val session = session()
        assertEquals("https://verify.example/challenge?state=old", session.requestVerification())
        writeRecord()

        assertNull("an already verified session must not reopen its old challenge", session.requestVerification())
        assertTrue(session.snapshot().authenticated)
        assertNull(session.verificationUrl())
        assertEquals("no second bootstrap was needed", 1, server.requestCount)
    }

    private fun session(
        verificationWaitMs: Long = 1_000,
        onVerificationRequired: (String) -> Unit = {},
    ): SflxSignedSession = SflxSignedSession(
        extensionId = "tidal-web",
        config = config(),
        directory = directory,
        onVerificationRequired = onVerificationRequired,
        httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                // Production still requires HTTPS. Only this loopback mock
                // transport maps that URL to MockWebServer's cleartext socket.
                val request = chain.request()
                chain.proceed(request.newBuilder().url(request.url.newBuilder().scheme("http").build()).build())
            }
            .build(),
        verificationWaitMs = verificationWaitMs,
    )

    private fun config() = SignedSessionConfig(
        namespace = "test",
        baseUrl = server.url("/v2/").toString().replaceFirst("http://", "https://"),
        appVersion = "tidal-web@1.2.5",
        platform = "extension",
        callbackUrl = "mornye://session-grant",
        schemeLabel = "ZARZ-HMAC-V1",
        headerPrefix = "X-Zarz-",
        timeWindowSeconds = 300,
        bootstrap = "/bootstrap",
        challenge = "/challenge",
        exchange = "/session/exchange",
        refresh = "",
    )

    private fun writeRecord() {
        val file = File(directory, config().fileName())
        val existing = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
        existing
            .put("session_id", "session")
            .put("session_secret", "secret")
            .put("expires_at", Instant.now().plusSeconds(3_600).toString())
        file.writeText(existing.toString())
    }

    private fun sessionJson(): String = JSONObject()
        .put("session_id", "session")
        .put("session_secret", "secret")
        .put("expires_at", Instant.now().plusSeconds(3_600).toString())
        .toString()

    private fun json(code: Int, body: String): MockResponse = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}
