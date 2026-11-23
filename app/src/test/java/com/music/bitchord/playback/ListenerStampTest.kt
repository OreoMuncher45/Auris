package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * An Android Auto listing is kept only when no account switch landed while it
 * was read. The listener's name alone can't say that: A to B and back to A
 * leaves the same name, and a page read in between may be B's.
 */
class ListenerStampTest {

    @Test
    fun `a read with no switch in between is still current`() {
        assertEquals(ListenerStamp("A", 4), ListenerStamp("A", 4))
    }

    @Test
    fun `a switch away and back makes the read stale`() {
        assertNotEquals(ListenerStamp("A", 4), ListenerStamp("A", 6))
    }

    @Test
    fun `a different listener makes the read stale`() {
        assertNotEquals(ListenerStamp("A", 4), ListenerStamp("B", 5))
        assertNotEquals(ListenerStamp(null, 4), ListenerStamp("A", 4))
    }
}
