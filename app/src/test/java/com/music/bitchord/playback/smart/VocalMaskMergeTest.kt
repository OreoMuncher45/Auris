package com.music.bitchord.playback.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VocalMaskMergeTest {

    private val nan = Double.NaN

    @Test
    fun `a point no model window reached keeps the heuristic`() {
        val heuristic = listOf(0.9, 0.9, 0.9, 0.9)
        // The head window ran over the first two points; the tail window failed.
        val head = doubleArrayOf(0.1, 0.6, nan, nan)

        val merged = mergeVocalMasks(4, heuristic, listOf(head), neutral = 0.2)

        assertEquals(listOf(0.1, 0.6, 0.9, 0.9), merged)
    }

    @Test
    fun `a model answer equal to the neutral value still counts as measured`() {
        val merged = mergeVocalMasks(2, listOf(0.9, 0.9), listOf(doubleArrayOf(0.2, nan)), neutral = 0.2)

        assertEquals(listOf(0.2, 0.9), merged)
    }

    @Test
    fun `points past the heuristic read as neutral and no window at all is null`() {
        assertEquals(listOf(0.5, 0.2), mergeVocalMasks(2, emptyList(), listOf(doubleArrayOf(0.5, nan)), neutral = 0.2))
        assertNull(mergeVocalMasks(2, listOf(0.9, 0.9), emptyList(), neutral = 0.2))
    }
}
