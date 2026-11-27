package com.music.bitchord.data

import com.music.bitchord.data.innertube.Innertube
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * An account-scoped write, end to end through [YtMusicRepository]: what signs
 * the request, what a rejection refreshes, and whether a retry is sent, with
 * the network answered in-process so every request's credentials can be read.
 */
class SessionRetryTest {

    /** One request as sent: who it was signed for. */
    private data class Sent(val method: String, val path: String, val cookie: String?, val authUser: String?, val pageId: String?)

    private val sent = mutableListOf<Sent>()
    private val writes get() = synchronized(sent) { sent.filter { it.method == "POST" && it.path.endsWith("browse/edit_playlist") } }

    /**
     * Answers the shell with [shell] (its HTML for the cookie asking) and each
     * playlist edit with [edit]'s status (by how many edits came before it).
     */
    private fun answer(shell: (cookie: String?) -> String = { shellFor() }, edit: (index: Int) -> Int) {
        Innertube.answerRequestsForTest(
            Interceptor { chain ->
                val request = chain.request()
                val record = Sent(
                    request.method,
                    request.url.encodedPath,
                    request.header("Cookie"),
                    request.header("X-Goog-AuthUser"),
                    request.header("X-Goog-PageId"),
                )
                synchronized(sent) { sent += record }
                val (code, body, type) = when {
                    request.method == "GET" && request.url.encodedPath == "/" ->
                        Triple(200, shell(record.cookie), "text/html")
                    request.url.encodedPath.endsWith("browse/edit_playlist") -> {
                        val index = writes.size - 1
                        Triple(edit(index), "{}", "application/json")
                    }
                    else -> Triple(404, "{}", "application/json")
                }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code == 200) "OK" else "Unauthorized")
                    .header("Content-Type", type)
                    .body(body.toResponseBody(type.toMediaType()))
                    .build()
            },
        )
    }

    private fun shellFor(index: String = "0", pageId: String? = null): String =
        """<script>ytcfg.set({"LOGGED_IN":true,"SESSION_INDEX":"$index",""" +
            (pageId?.let { """"DELEGATED_SESSION_ID":"$it",""" } ?: "") +
            """"INNERTUBE_CLIENT_VERSION":"1.20261126.00.00"});</script>"""

    @AfterTest
    fun reset() {
        Innertube.answerRequestsForTest(null)
        Innertube.cookie = null
    }

    @Test
    fun `a rejected write is refreshed once and retried once, signed as the same account`() = runBlocking {
        Innertube.cookie = "SID=A"
        answer { index -> if (index == 0) 401 else 200 }
        val result = YtMusicRepository.renamePlaylist("PL1", "new name")
        assertTrue(result.isSuccess, "${result.exceptionOrNull()}")
        assertEquals(listOf("SID=A", "SID=A"), writes.map { it.cookie })
        assertEquals(listOf("0", "0"), writes.map { it.authUser })
        // The scope read, the rejected edit, one refresh of the shell, then the retry.
        assertEquals(listOf("shell", "edit", "shell", "edit"), order())
    }

    /** What went out, in order: a shell read or a playlist edit. */
    private fun order() = synchronized(sent) {
        sent.mapNotNull {
            when {
                it.method == "GET" && it.path == "/" -> "shell"
                it.method == "POST" && it.path.endsWith("browse/edit_playlist") -> "edit"
                else -> null
            }
        }
    }

    @Test
    fun `a personal account picked by its index keeps it through a refresh that reads the shell's default`() = runBlocking {
        Innertube.installSession(
            Innertube.CandidateSession("SID=A", pageId = null, dataSyncId = null, authUser = "1", visitorData = null, clientVersion = null),
        )
        // The shell names the jar's default account, index 0.
        answer(shell = { shellFor(index = "0") }) { index -> if (index == 0) 401 else 200 }
        assertTrue(YtMusicRepository.renamePlaylist("PL1", "new name").isSuccess)
        assertTrue(YtMusicRepository.renamePlaylist("PL1", "newer name").isSuccess)
        assertEquals(listOf("1", "1", "1"), writes.map { it.authUser })
    }

    @Test
    fun `the first account picked explicitly keeps index 0 through a refresh whose shell defaults to another`() = runBlocking {
        Innertube.installSession(
            Innertube.CandidateSession("SID=A", pageId = null, dataSyncId = null, authUser = "0", visitorData = null, clientVersion = null),
        )
        answer(shell = { shellFor(index = "2") }) { index -> if (index == 0) 401 else 200 }
        assertTrue(YtMusicRepository.renamePlaylist("PL1", "new name").isSuccess)
        assertTrue(YtMusicRepository.renamePlaylist("PL1", "newer name").isSuccess)
        assertEquals(listOf("0", "0", "0"), writes.map { it.authUser })
    }

    @Test
    fun `a sign-in that names no index takes the shell's`() = runBlocking {
        Innertube.installSession(
            Innertube.CandidateSession("SID=A", pageId = null, dataSyncId = null, authUser = null, visitorData = null, clientVersion = null),
        )
        answer(shell = { shellFor(index = "2") }) { 200 }
        // The installed scope names index 0 until a refresh: drop it so the shell is read.
        assertTrue(Innertube.refreshSessionScope(Innertube.listenerStamp()) != null)
        assertTrue(YtMusicRepository.renamePlaylist("PL1", "new name").isSuccess)
        assertEquals(listOf("2"), writes.map { it.authUser })
    }

    @Test
    fun `a rejected request bound to its session refreshes nothing, so the caller's stamp stays current`() = runBlocking {
        Innertube.cookie = "SID=A"
        answer { 401 }
        Innertube.ensureSessionScope()
        val a = Innertube.listenerStamp()
        val shellsBefore = order().size
        val result = Innertube.asListener(a) { YtMusicRepository.renamePlaylist("PL1", "new name") }
        assertTrue(result.isFailure)
        assertEquals(listOf("edit"), order().drop(shellsBefore))
        assertEquals(a, Innertube.listenerStamp())
    }

    @Test
    fun `a switch to B before the write's credentials are taken signs it as B, never as A`() = runBlocking {
        Innertube.cookie = "SID=A"
        // The switch lands while the write's own scope setup reads the shell.
        answer(shell = { asking -> if (asking == "SID=A") Innertube.cookie = "SID=B"; shellFor() }) { 200 }
        assertTrue(YtMusicRepository.renamePlaylist("PL1", "new name").isSuccess)
        assertEquals(listOf("SID=B"), writes.map { it.cookie })
    }

    @Test
    fun `a write bound to A is not sent at all after a switch to B before its credentials are taken`() = runBlocking {
        Innertube.cookie = "SID=A"
        val a = Innertube.listenerStamp()
        answer(shell = { asking -> if (asking == "SID=A") Innertube.cookie = "SID=B"; shellFor() }) { 200 }
        val result = Innertube.asListener(a) { YtMusicRepository.renamePlaylist("PL1", "new name") }
        assertIs<Innertube.ListenerChangedException>(result.exceptionOrNull())
        assertEquals(emptyList(), writes)
    }

    @Test
    fun `a switch to B and back to A during the refresh sends no retry`() = runBlocking {
        Innertube.cookie = "SID=A"
        var shells = 0
        answer(
            shell = { _ ->
                // The second shell read is the rejection's refresh.
                if (++shells == 2) {
                    Innertube.cookie = "SID=B"
                    Innertube.cookie = "SID=A"
                }
                shellFor()
            },
        ) { 401 }
        assertTrue(YtMusicRepository.renamePlaylist("PL1", "new name").isFailure)
        assertEquals(listOf("SID=A"), writes.map { it.cookie })
    }

    @Test
    fun `a rejected write under an adopted personal profile is retried, the shell's default being the same listener`() = runBlocking {
        Innertube.cookie = "SID=A"
        Innertube.adoptSessionScope(null, null, null, null, null, loggedIn = true)
        answer { index -> if (index == 0) 401 else 200 }
        assertTrue(YtMusicRepository.renamePlaylist("PL1", "new name").isSuccess)
        assertEquals(listOf("0", "0"), writes.map { it.authUser })
        assertEquals(listOf(null, null), writes.map { it.pageId })
    }

    @Test
    fun `a rejected write for a brand channel is not retried under the shell's personal default`() = runBlocking {
        Innertube.cookie = "SID=A"
        Innertube.adoptSessionScope("brand", "brand||owner", "0", null, null, loggedIn = true)
        answer(shell = { shellFor(index = "0", pageId = null) }) { 401 }
        assertTrue(YtMusicRepository.renamePlaylist("PL1", "new name").isFailure)
        assertEquals(listOf("brand"), writes.map { it.pageId })
    }
}
