package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListenerScopedCacheTest {
    private var session = ListenerStamp("A", generation = 1)
    private val cache = ListenerScopedCache<ListenerStamp, String> { session }
    private fun put(owner: ListenerStamp, vararg values: String) = cache.putAll(owner, values.asIterable()) { it }

    @Test
    fun `rows written for one listener are not read back under another`() {
        put(session, "a1")
        assertEquals("a1", cache["a1"])
        session = ListenerStamp("B", 2)
        assertNull(cache["a1"])
    }

    @Test
    fun `switching back does not bring the earlier session's rows back`() {
        put(session, "a1")
        session = ListenerStamp("B", 2)
        put(session, "b1")
        session = ListenerStamp("A", 3)
        assertNull(cache["a1"])
        assertNull(cache["b1"])
    }

    @Test
    fun `a listing read for A that lands after a switch to B is not filed under B`() {
        val a = session
        session = ListenerStamp("B", 2)
        put(a, "a1")
        assertNull(cache["a1"])
    }

    @Test
    fun `a listing read for A that lands after a switch away and back to A is not filed`() {
        val a = session
        session = ListenerStamp("B", 2)
        session = ListenerStamp("A", 3)
        put(a, "a1")
        assertNull(cache["a1"])
    }

    @Test
    fun `a search read for A that lands after a switch to B is not served to B`() {
        val searches = ListenerScopedCache<ListenerStamp, List<String>>(capacity = 20) { session }
        val a = session
        session = ListenerStamp("B", 2)
        searches.put(a, "query", listOf("A's result"))
        assertNull(searches["query"])
    }

    @Test
    fun `a lookup after a switch reads nothing rather than failing`() {
        put(session, "a1")
        session = ListenerStamp("B", 2)
        assertNull(cache["a1"])
    }

    @Test
    fun `rows that belong to no account are kept for whoever is listening`() {
        session = ListenerStamp("B", 2)
        cache.putShared(listOf("local1")) { it }
        assertEquals("local1", cache["local1"])
    }

    @Test
    fun `the oldest entry goes once the cache is full`() {
        val small = ListenerScopedCache<ListenerStamp, String>(capacity = 2) { session }
        small.put(session, "q1", "1")
        small.put(session, "q2", "2")
        small.put(session, "q3", "3")
        assertNull(small["q1"])
        assertEquals("3", small["q3"])
    }
}
