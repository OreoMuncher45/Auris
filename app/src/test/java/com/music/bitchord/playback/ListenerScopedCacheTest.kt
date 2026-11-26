package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListenerScopedCacheTest {
    private var listener: String? = "A"
    private val cache = ListenerScopedCache<String> { listener }
    private fun put(owner: String?, vararg values: String) = cache.putAll(owner, values.asIterable()) { it }

    @Test
    fun `rows written for one listener are not read back under another`() {
        put("A", "a1")
        assertEquals("a1", cache["a1"])
        listener = "B"
        assertNull(cache["a1"])
    }

    @Test
    fun `switching back does not bring the earlier listener's rows back`() {
        put("A", "a1")
        listener = "B"
        put("B", "b1")
        listener = "A"
        assertNull(cache["a1"])
        assertNull(cache["b1"])
    }

    @Test
    fun `a listing read for A that lands after a switch to B is not filed under B`() {
        listener = "B"
        put("A", "a1")
        assertNull(cache["a1"])
    }

    @Test
    fun `a lookup after a switch reads nothing rather than failing`() {
        put("A", "a1")
        listener = "B"
        // One step: no membership check that a switch could separate from the read.
        assertNull(cache["a1"])
    }

    @Test
    fun `rows that belong to no account are kept for whoever is listening`() {
        listener = "B"
        cache.putShared(listOf("local1")) { it }
        assertEquals("local1", cache["local1"])
    }
}
