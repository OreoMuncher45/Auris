package com.music.bitchord

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayRequestGuardTest {
    private var generation = 0
    private val begin = { ++generation }
    private val current = { generation }

    @Test
    fun `runs when nothing else started playing`() {
        val played = mutableListOf<String>()
        val guarded = latestPlayRequestOnly<String>(begin, current) { played += it }
        guarded("a")
        assertEquals(listOf("a"), played)
    }

    @Test
    fun `a slow fetch is dropped after another play request`() {
        val played = mutableListOf<String>()
        val slow = latestPlayRequestOnly<String>(begin, current) { played += it }
        // Another play (a row tap, or a second card) bumps the generation.
        ++generation
        slow("stale")
        assertEquals(emptyList<String>(), played)
    }

    @Test
    fun `the newer of two card taps wins whatever order the fetches land in`() {
        val played = mutableListOf<String>()
        val first = latestPlayRequestOnly<String>(begin, current) { played += it }
        val second = latestPlayRequestOnly<String>(begin, current) { played += it }
        second("second")
        first("first")
        assertEquals(listOf("second"), played)
    }

    @Test
    fun `the token is claimed at the tap, not when the songs arrive`() {
        latestPlayRequestOnly<String>(begin, current) {}
        assertEquals(1, generation)
    }
}
