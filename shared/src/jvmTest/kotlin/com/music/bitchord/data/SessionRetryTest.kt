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
    private val lookups get() = synchronized(sent) { sent.filter { it.method == "POST" && it.path.endsWith("/browse") } }

    private fun answer(
        shell: (cookie: String?) -> String = { shellFor() },
        /** Each whole-playlist lookup's status, by how many came before it. */
        lookup: (index: Int) -> Int = { 404 },
        edit: (index: Int) -> Int,
    ) {
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
                    request.method == "POST" && request.url.encodedPath.endsWith("/browse") -> {
                        val index = lookups.size - 1
                        Triple(lookup(index), "{}", "application/json")
                    }
                    else -> Triple(404, "{}", "application/json")
                }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code == 200) "OK" else if (code == 404) "Not Found" else "Unauthorized")
                    .header("Content-Type", type)
                    .body(body.toResponseBody(type.toMediaType()))
                    .build()
            },
        )
    }

    private fun shellFor(index: String = "0", pageId: String? = null, dataSyncId: String? = null): String =
        """<script>ytcfg.set({"LOGGED_IN":true,"SESSION_INDEX":"$index",""" +
            (pageId?.let { """"DELEGATED_SESSION_ID":"$it",""" } ?: "") +
            (dataSyncId?.let { """"DATASYNC_ID":"$it",""" } ?: "") +
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
    fun `a playlist add bound recoverably to A is refreshed and retried once as A`() = runBlocking {
        Innertube.cookie = "SID=A"
        answer { index -> if (index == 0) 401 else 200 }
        Innertube.ensureSessionScope()
        val a = Innertube.listenerStamp()
        val result = Innertube.asListenerRecoverably(a) { YtMusicRepository.addToPlaylist("PL1", listOf("vvvvvvvvvvv")) }
        assertTrue(result.isSuccess, "${result.exceptionOrNull()}")
        assertEquals(listOf("SID=A", "SID=A"), writes.map { it.cookie })
        assertEquals(listOf("shell", "edit", "shell", "edit"), order())
    }

    @Test
    fun `after a recovered request, the next one in the same add goes out under the refreshed session`() = runBlocking {
        Innertube.cookie = "SID=A"
        // The shell names a dataSyncId, so clearing and re-reading the scope moves the generation.
        answer(shell = { shellFor(dataSyncId = "sync-A") }) { index -> if (index == 0) 401 else 200 }
        Innertube.ensureSessionScope()
        val a = Innertube.listenerStamp()
        Innertube.asListenerRecoverably(a) {
            // The lookup's stand-in, recovered, then the write.
            assertTrue(YtMusicRepository.addToPlaylist("PL1", listOf("aaaaaaaaaaa")).isSuccess)
            assertTrue(Innertube.listenerStamp() != a, "the refresh did not move the generation")
            assertTrue(YtMusicRepository.addToPlaylist("PL1", listOf("bbbbbbbbbbb")).isSuccess)
        }
        // The rejected one, its retry, and the next: nothing accepted was sent twice.
        assertEquals(3, writes.size)
        assertEquals(listOf("SID=A", "SID=A", "SID=A"), writes.map { it.cookie })
    }

    @Test
    fun `a recovered batch is followed by the next batch, and a strict binding still refuses after a refresh`() = runBlocking {
        Innertube.cookie = "SID=A"
        answer(shell = { shellFor(dataSyncId = "sync-A") }) { index -> if (index == 1) 401 else 200 }
        Innertube.ensureSessionScope()
        val a = Innertube.listenerStamp()
        Innertube.asListenerRecoverably(a) {
            assertTrue(YtMusicRepository.addToPlaylist("PL1", listOf("aaaaaaaaaaa")).isSuccess)
            assertTrue(YtMusicRepository.addToPlaylist("PL1", listOf("bbbbbbbbbbb")).isSuccess)
            assertTrue(YtMusicRepository.addToPlaylist("PL1", listOf("ccccccccccc")).isSuccess)
        }
        assertEquals(4, writes.size)
        // The stamp taken before the refresh is stale now, and a strict binding to it sends nothing.
        val before = writes.size
        val strict = Innertube.asListener(a) { YtMusicRepository.addToPlaylist("PL1", listOf("ddddddddddd")) }
        assertIs<Innertube.ListenerChangedException>(strict.exceptionOrNull())
        assertEquals(before, writes.size)
    }

    @Test
    fun `an add whose lookup is recovered goes on to write under the refreshed session, for every playlist`() = runBlocking {
        Innertube.cookie = "SID=A"
        // The first lookup is refused for stale credentials; the shell names a
        // dataSyncId, so the refresh moves the generation.
        answer(shell = { shellFor(dataSyncId = "sync-A") }, lookup = { index -> if (index == 0) 401 else 404 }) { 200 }
        Innertube.ensureSessionScope()
        val a = Innertube.listenerStamp()
        val results = Innertube.asListenerRecoverably(a) {
            // The app's own call pattern: one binding, every playlist picked.
            listOf("PL1", "PL2").map { id -> YtMusicRepository.addMissingTracks("VL$id", id, listOf("aaaaaaaaaaa"), batchSize = 1) }
        }
        assertTrue(Innertube.listenerStamp() != a, "the refresh did not move the generation")
        assertTrue(results.none { it.failed }, "a write was refused after the recovered lookup")
        assertEquals(listOf("SID=A", "SID=A"), writes.map { it.cookie })
    }

    @Test
    fun `an add whose batch is recovered sends the next batch and never resends an accepted one`() = runBlocking {
        Innertube.cookie = "SID=A"
        // Batch 1 accepted, batch 2 refused once then accepted, batch 3 accepted.
        answer(shell = { shellFor(dataSyncId = "sync-A") }) { index -> if (index == 1) 401 else 200 }
        Innertube.ensureSessionScope()
        val a = Innertube.listenerStamp()
        val result = Innertube.asListenerRecoverably(a) {
            YtMusicRepository.addMissingTracks("VLPL1", "PL1", listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"), batchSize = 1)
        }
        assertTrue(!result.failed)
        assertEquals(setOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"), result.added.keys)
        // Three batches and one retry of the refused one.
        assertEquals(4, writes.size)
    }

    @Test
    fun `rows added in bulk carry the entry id this playlist gave them, never the source's`() {
        val fromAnotherPlaylist = com.music.bitchord.data.model.Song(
            videoId = "aaaaaaaaaaa", title = "A", artist = "", thumbnailUrl = null, setVideoId = "SOURCE-ROW",
        )
        val noIdGiven = fromAnotherPlaylist.copy(videoId = "bbbbbbbbbbb")
        val notAdded = fromAnotherPlaylist.copy(videoId = "ccccccccccc")
        val result = YtMusicRepository.AddedTracks(
            added = mapOf("aaaaaaaaaaa" to "DEST-ROW", "bbbbbbbbbbb" to ""),
            alreadyThere = false,
            failed = false,
        )
        val rows = result.rowsOf(listOf(fromAnotherPlaylist, noIdGiven, notAdded))
        assertEquals(listOf("aaaaaaaaaaa" to "DEST-ROW", "bbbbbbbbbbb" to null), rows.map { it.videoId to it.setVideoId })
    }

    @Test
    fun `a page's continuation refused once goes on under the refreshed session of the same listener`() = runBlocking {
        Innertube.cookie = "SID=A"
        // The first continuation is refused for stale credentials; the shell
        // names a dataSyncId, so the refresh moves the generation.
        answer(shell = { shellFor(dataSyncId = "sync-A") }, lookup = { index -> if (index == 0) 401 else 200 }) { 200 }
        Innertube.ensureSessionScope()
        val opening = Innertube.listenerStamp()
        val (first, second, adopted) = Innertube.asListenerRecoverably(opening) {
            val first = YtMusicRepository.moreSongs("TOKEN-2")
            val adopted = Innertube.boundStamp()
            Triple(first, YtMusicRepository.moreSongs("TOKEN-3"), adopted)
        }
        // Retried once and then the next page sent: three requests, none refused as another session.
        assertEquals(3, lookups.size)
        kotlin.test.assertFalse(first.exceptionOrNull() is Innertube.ListenerChangedException)
        kotlin.test.assertFalse(second.exceptionOrNull() is Innertube.ListenerChangedException)
        assertEquals(opening.listener, adopted?.listener)
        assertTrue(adopted != opening, "the binding did not move to the refreshed session")
        assertEquals(Innertube.listenerStamp(), adopted)
    }

    @Test
    fun `a recoverable add is never retried as another account`() = runBlocking {
        Innertube.cookie = "SID=A"
        var shells = 0
        answer(
            shell = { _ ->
                // The second shell read is the rejection's refresh; a switch lands during it.
                if (++shells == 2) Innertube.cookie = "SID=B"
                shellFor()
            },
        ) { 401 }
        Innertube.ensureSessionScope()
        val a = Innertube.listenerStamp()
        val result = Innertube.asListenerRecoverably(a) { YtMusicRepository.addToPlaylist("PL1", listOf("vvvvvvvvvvv")) }
        assertTrue(result.isFailure)
        assertEquals(listOf("SID=A"), writes.map { it.cookie })
    }

    @Test
    fun `only the rejected batch of a recoverable add is sent again`() = runBlocking {
        Innertube.cookie = "SID=A"
        // The first batch is accepted, the second rejected once.
        answer { index -> if (index == 1) 401 else 200 }
        Innertube.ensureSessionScope()
        val a = Innertube.listenerStamp()
        Innertube.asListenerRecoverably(a) {
            assertTrue(YtMusicRepository.addToPlaylist("PL1", listOf("aaaaaaaaaaa")).isSuccess)
            assertTrue(YtMusicRepository.addToPlaylist("PL1", listOf("bbbbbbbbbbb")).isSuccess)
        }
        assertEquals(3, writes.size)
    }

    @Test
    fun `reading the session scope in is not an account transition, a profile switch is`() = runBlocking {
        Innertube.cookie = "SID=A"
        answer { 200 }
        val before = Innertube.accountTransitionCount()
        Innertube.ensureSessionScope()
        assertEquals(before, Innertube.accountTransitionCount())
        Innertube.selectChannel("brand", null, "0")
        assertTrue(Innertube.accountTransitionCount() > before)
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
