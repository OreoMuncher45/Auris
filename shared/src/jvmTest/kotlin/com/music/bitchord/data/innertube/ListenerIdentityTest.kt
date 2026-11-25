package com.music.bitchord.data.innertube

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

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
}
