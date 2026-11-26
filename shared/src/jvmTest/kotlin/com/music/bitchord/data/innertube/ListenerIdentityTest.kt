package com.music.bitchord.data.innertube

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** [Innertube.listener]: what an account-bound cache has to match before it is reused. */
class ListenerIdentityTest {

    @AfterTest
    fun signOut() {
        Innertube.cookie = null
    }

    @Test
    fun `signed out is no listener`() {
        Innertube.cookie = null
        assertNull(Innertube.listener)
    }

    @Test
    fun `a profile switch under one cookie is a different listener`() {
        Innertube.cookie = "SID=one"
        val personal = Innertube.listener
        Innertube.selectChannel(pageId = "brand", dataSyncId = "brand||owner")
        val brand = Innertube.listener
        assertNotEquals(personal, brand)
        // Choosing the same channel again is the same listener.
        Innertube.selectChannel(pageId = "brand", dataSyncId = "brand||owner")
        assertEquals(brand, Innertube.listener)
        Innertube.selectChannel(pageId = null, dataSyncId = null)
        assertEquals(personal, Innertube.listener)
    }

    @Test
    fun `another account is a different listener`() {
        Innertube.cookie = "SID=one"
        val first = Innertube.listener
        Innertube.cookie = "SID=two"
        assertNotEquals(first, Innertube.listener)
    }

    @Test
    fun `a chosen channel without its own account index follows the scope's`() {
        Innertube.cookie = "SID=one"
        Innertube.selectChannel(pageId = "brand", dataSyncId = "brand||owner")
        Innertube.adoptSessionScope("brand", "brand||owner", "0", null, null, loggedIn = true)
        val first = Innertube.listener
        Innertube.adoptSessionScope("brand", "brand||owner", "1", null, null, loggedIn = true)
        assertNotEquals(first, Innertube.listener)
    }

    @Test
    fun `switching away and back is a new generation though the listener is the same`() {
        Innertube.cookie = "SID=one"
        val listener = Innertube.listener
        val generation = Innertube.listenerGeneration
        Innertube.cookie = "SID=two"
        Innertube.cookie = "SID=one"
        assertEquals(listener, Innertube.listener)
        assertNotEquals(generation, Innertube.listenerGeneration)
        // Nothing changed: same generation.
        val now = Innertube.listenerGeneration
        Innertube.selectChannel(pageId = null, dataSyncId = null)
        assertEquals(now, Innertube.listenerGeneration)
    }

    @Test
    fun `a page scope for another account under one cookie is a different listener`() {
        Innertube.cookie = "SID=one"
        val shell = Innertube.listener
        Innertube.adoptSessionScope("pageA", "syncA", "0", null, null, loggedIn = true)
        val first = Innertube.listener
        assertNotEquals(shell, first)
        Innertube.adoptSessionScope("pageB", "syncB", "1", null, null, loggedIn = true)
        assertNotEquals(first, Innertube.listener)
        // The same scope again is the same listener.
        val second = Innertube.listener
        Innertube.adoptSessionScope("pageB", "syncB", "1", null, null, loggedIn = true)
        assertEquals(second, Innertube.listener)
    }

    private val candidateB = Innertube.CandidateSession(
        cookie = "SID=B", pageId = "brandB", dataSyncId = "brandB||owner", authUser = "1",
        visitorData = null, clientVersion = null,
    )

    @Test
    fun `a candidate's requests sign as the candidate and never touch the shared session`() = runBlocking {
        Innertube.cookie = "SID=A"
        val before = Innertube.listenerStamp()
        val shared = Innertube.requestIdentityForTest()
        val asCandidate = Innertube.asCandidate(candidateB) { Innertube.requestIdentityForTest() }
        assertTrue(asCandidate.startsWith("SID=B|1|brandB|brandB||owner|"), asCandidate)
        // Nothing of B reached the shared session: same listener, same generation, same credentials.
        assertEquals(before, Innertube.listenerStamp())
        assertEquals(shared, Innertube.requestIdentityForTest())
    }

    @Test
    fun `an accepted candidate is installed whole, in one step`() = runBlocking {
        Innertube.cookie = "SID=A"
        val before = Innertube.listenerStamp()
        assertTrue(Innertube.installSession(candidateB))
        assertNotEquals(before, Innertube.listenerStamp())
        assertTrue(Innertube.requestIdentityForTest().startsWith("SID=B|1|brandB|brandB||owner|"))
    }

    @Test
    fun `a superseded acceptance installs nothing`() {
        Innertube.cookie = "SID=A"
        val before = Innertube.listenerStamp()
        // A sign-out or profile switch landed while B was being validated.
        assertFalse(Innertube.installSession(candidateB) { true })
        assertEquals(before, Innertube.listenerStamp())
    }

    @Test
    fun `a request bound to A is refused after a switch to B and back to A`() {
        Innertube.cookie = "SID=A"
        val stampA = Innertube.listenerStamp()
        Innertube.cookie = "SID=B"
        Innertube.cookie = "SID=A"
        // Same listener as before, but not the same session: the request is not sent.
        assertEquals(stampA.listener, Innertube.listener)
        assertFailsWith<Innertube.ListenerChangedException> {
            runBlocking { Innertube.asListener(stampA) { Innertube.requestIdentityForTest() } }
        }
        // Bound to the current session, it goes out.
        runBlocking { Innertube.asListener(Innertube.listenerStamp()) { Innertube.requestIdentityForTest() } }
    }

    @Test
    fun `a rejection from an earlier session does not clear the current one's adopted scope`() = runBlocking {
        Innertube.cookie = "SID=one"
        val earlier = Innertube.listenerStamp()
        // The listener adopts a profile under the same cookie after the request went out.
        Innertube.adoptSessionScope("brand", "brand||owner", "1", null, null, loggedIn = true)
        val adopted = Innertube.listenerStamp()
        assertFalse(Innertube.refreshSessionScope(earlier))
        assertEquals(adopted, Innertube.listenerStamp())
    }

    @Test
    fun `a page's later rows are refused after its session's scope changed before scrolling`() {
        Innertube.cookie = "SID=one"
        // The page opens: its session is recorded.
        val opened = Innertube.listenerStamp()
        // While it stays up, the scope changes (a refresh, an adopted profile).
        Innertube.adoptSessionScope("brand", "brand||owner", "1", null, null, loggedIn = true)
        // Scrolling now: the continuation is bound to the opening's session and is not sent.
        assertFailsWith<Innertube.ListenerChangedException> {
            runBlocking { Innertube.asListener(opened) { Innertube.requestIdentityForTest() } }
        }
    }

    @Test
    fun `a rejection is owned by the session that signed it, after scope setup moved the generation`() = runBlocking {
        Innertube.cookie = "SID=one"
        val beforeCall = Innertube.listenerStamp()
        val signed = Innertube.recordingSignedStamp { holder ->
            // Scope setup inside the call advances the generation under the same cookie.
            Innertube.adoptSessionScope("brand", "brand||owner", "1", null, null, loggedIn = true)
            Innertube.requestIdentityForTest()
            holder.stamp
        }
        // The pre-call stamp no longer owns the session; the signing one does, so recovery may run.
        assertFalse(Innertube.isCurrentSession(beforeCall))
        assertTrue(Innertube.isCurrentSession(assertNotNull(signed)))
    }

    @Test
    fun `a rejection signed before a switch to B and back to A owns nothing`() = runBlocking {
        Innertube.cookie = "SID=A"
        val signed = Innertube.recordingSignedStamp { holder -> Innertube.requestIdentityForTest(); holder.stamp }
        Innertube.cookie = "SID=B"
        Innertube.cookie = "SID=A"
        assertFalse(Innertube.isCurrentSession(assertNotNull(signed)))
    }
}
