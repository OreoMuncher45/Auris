package com.music.bitchord.data.sources.mornye

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Issue #90: a grant pasted into the verification page, or delivered after the
 * app forgot its challenge, has to redeem, and a refusal has to say why.
 */
class SflxHostGrantRedeemTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val server = MockWebServer()
    private val exchanged = CopyOnWriteArrayList<String>()

    /** What `/session/exchange` answers. */
    @Volatile
    private var exchangeCode = 200

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when (request.requestUrl?.encodedPath) {
                    "/v2/bootstrap" -> MockResponse().setBody(
                        JSONObject().put("challenge_url", gateway() + "challenge?state=issued-state").toString(),
                    )
                    "/v2/session/exchange" -> {
                        exchanged += JSONObject(request.body.readUtf8()).getString("grant")
                        if (exchangeCode != 200) {
                            MockResponse().setResponseCode(exchangeCode)
                        } else {
                            MockResponse().setBody(
                                JSONObject()
                                    .put("session_id", "test-session")
                                    .put("session_secret", "test-secret")
                                    .put("expires_at", java.time.Instant.now().plusSeconds(86_400).toString())
                                    .toString(),
                            )
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                }
        }
        server.start()
    }

    @After
    fun stop() = server.shutdown()

    private fun gateway(): String = server.url("/v2/").toString().replaceFirst("http://", "https://")

    private fun manifest(name: String) = JSONObject()
        .put("name", name)
        .put("displayName", name)
        .put("version", "1.0.0")
        .put("type", JSONArray().put("download_provider"))
        .put("signedSession", JSONObject().put("namespace", name).put("baseUrl", gateway()))
        .toString()

    private val store by lazy { SflxStore(File(temp.root, "store")) }
    private val sessionsDir by lazy { File(temp.root, "sessions").apply { mkdirs() } }

    private fun install(name: String): InstalledSflx = store.restore(
        name,
        mapOf(SflxStore.MANIFEST_FILE to manifest(name).toByteArray(), SflxStore.SCRIPT_FILE to "//".toByteArray()),
    )

    private val host by lazy {
        SflxHost(
            store,
            "3.4",
            sessionsDir,
            sessionClient = okhttp3.OkHttpClient.Builder()
                .addInterceptor { chain ->
                    // Loopback only: the HTTPS URL production requires, onto MockWebServer's socket.
                    val request = chain.request()
                    chain.proceed(request.newBuilder().url(request.url.newBuilder().scheme("http").build()).build())
                }
                .build(),
        )
    }

    private fun authenticated(installed: InstalledSflx): Boolean =
        host.sessionFor(installed)!!.snapshot().authenticated

    @Test
    fun aPastedLinkRedeemsAfterTheAppForgotItsChallenge() {
        val demo = install("demo")
        install("other")
        // The challenge died with the process: nothing is pending, and the pasted link
        // carries the state of a challenge this run of the app never issued.
        val result = host.redeemGrant("demo", "pasted-grant", "stale-state", typed = true)

        assertTrue(result.exceptionOrNull()?.toString(), result.isSuccess)
        assertEquals(listOf("pasted-grant"), exchanged)
        assertTrue(authenticated(demo))
    }

    @Test
    fun aPastedLinkRedeemsWhenANewerChallengeReplacedItsOwn() {
        val demo = install("demo")
        host.beginVerification("demo")
        assertEquals("issued-state", host.sessionFor(demo)!!.pendingState())

        val result = host.redeemGrant("demo", "pasted-grant", "older-state", typed = true)

        assertTrue(result.isSuccess)
        assertTrue(authenticated(demo))
    }

    @Test
    fun aDeepLinkWithNoChallengeLeftRedeemsForTheOnlySignedExtension() {
        val demo = install("demo")
        assertTrue(host.completeGrantForState("linked-grant", "unknown-state"))
        assertTrue(authenticated(demo))
    }

    @Test
    fun aDeepLinkStillMatchesItsOutstandingChallengeByState() {
        val demo = install("demo")
        host.beginVerification("demo")

        assertTrue(host.completeGrantForState("linked-grant", "issued-state"))
        assertTrue(authenticated(demo))
    }

    @Test
    fun aDeepLinkForSomeoneElsesChallengeIsRefusedWithoutSpendingIt() {
        val demo = install("demo")
        host.beginVerification("demo")

        val result = host.redeemGrant(null, "foreign-grant", "foreign-state", typed = false)

        assertEquals(SflxGrantRefused.NOT_WAITING, result.exceptionOrNull()?.message)
        assertEquals(emptyList<String>(), exchanged)
        assertFalse(authenticated(demo))
    }

    @Test
    fun aDeepLinkWithNoChallengeAndSeveralExtensionsIsNotGuessed() {
        install("demo")
        install("other")

        val result = host.redeemGrant(null, "linked-grant", "unknown-state", typed = false)

        assertEquals(SflxGrantRefused.NOT_WAITING, result.exceptionOrNull()?.message)
        assertEquals(emptyList<String>(), exchanged)
    }

    @Test
    fun aPasteForARemovedExtensionIsNotHandedToTheOneLeft() {
        install("gone")
        val left = install("left")
        host.remove("gone")

        val result = host.redeemGrant("gone", "pasted-grant", null, typed = true)

        assertEquals(SflxGrantRefused.NOT_WAITING, result.exceptionOrNull()?.message)
        assertEquals(emptyList<String>(), exchanged)
        assertFalse(authenticated(left))
    }

    @Test
    fun aRefusedExchangeSaysSo() {
        val demo = install("demo")
        exchangeCode = 400

        val result = host.redeemGrant("demo", "used-grant", null, typed = true)

        assertEquals(SflxGrantRefused.REFUSED, result.exceptionOrNull()?.message)
        assertFalse(authenticated(demo))
    }

    @Test
    fun aGrantPastedDuringAnImportIsNotSpentOnAClientThatCannotSave() {
        val demo = install("demo")
        host.replaceSessionRecords {
            // Mid-import every client is born retired and can't write its record.
            val result = host.redeemGrant("demo", "pasted-grant", null, typed = true)
            assertEquals(SflxGrantRefused.BUSY, result.exceptionOrNull()?.message)
        }
        assertEquals("the single-use grant was never sent", emptyList<String>(), exchanged)

        // After the import the same grant redeems and is stored.
        assertTrue(host.redeemGrant("demo", "pasted-grant", null, typed = true).isSuccess)
        assertTrue(authenticated(demo))
    }
}
