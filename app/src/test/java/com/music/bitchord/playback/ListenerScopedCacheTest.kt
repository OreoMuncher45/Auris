package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ListenerScopedCacheTest {
    @Test
    fun `rows written for one listener are not read back under another`() {
        var listener: String? = "A"
        val cache = ListenerScopedCache<String> { listener }
        cache["v1"] = "A's row"
        assertEquals("A's row", cache["v1"])
        listener = "B"
        assertNull(cache["v1"])
        assertFalse(cache.containsKey("v1"))
    }

    @Test
    fun `switching back does not bring the earlier listener's rows back`() {
        var listener: String? = "A"
        val cache = ListenerScopedCache<String> { listener }
        cache["v1"] = "A's row"
        listener = "B"
        cache["v2"] = "B's row"
        listener = "A"
        assertNull(cache["v1"])
        assertNull(cache["v2"])
    }
}
