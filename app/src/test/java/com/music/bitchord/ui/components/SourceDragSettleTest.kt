package com.music.bitchord.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The lyric-sources reorder swaps rows by each neighbour's own pitch, so a row
 * whose detail wraps onto two lines doesn't put the swap points of every row
 * below it out by the difference.
 */
class SourceDragSettleTest {

    private val pitches = mapOf("a" to 60f, "b" to 90f, "c" to 60f)
    private fun pitch(s: String) = pitches.getValue(s)

    @Test
    fun `slot tops add up the rows above`() {
        val order = listOf("a", "b", "c")
        assertEquals(0f, sourceSlotTop(order, 0, ::pitch))
        assertEquals(60f, sourceSlotTop(order, 1, ::pitch))
        assertEquals(150f, sourceSlotTop(order, 2, ::pitch))
    }

    @Test
    fun `a row swaps past a tall neighbour only once past that neighbour's threshold`() {
        val order = listOf("a", "b", "c")
        // 0.6 of b's 90 is 54: a uniform 60px pitch would have swapped at 36.
        assertEquals(order, settleSourceDrag(order, "a", 50f, ::pitch))
        assertEquals(listOf("b", "a", "c"), settleSourceDrag(order, "a", 55f, ::pitch))
    }

    @Test
    fun `a fast drag settles several rows in one step`() {
        val order = listOf("a", "b", "c")
        // a's slot after passing b starts at 90; 0.6 of c's 60 past that is 126.
        assertEquals(listOf("b", "c", "a"), settleSourceDrag(order, "a", 127f, ::pitch))
    }

    @Test
    fun `dragging up swaps by the row above`() {
        val order = listOf("a", "b", "c")
        // c sits at 150; b above is 90, so it needs 54 of travel upward.
        assertEquals(order, settleSourceDrag(order, "c", 97f, ::pitch))
        assertEquals(listOf("a", "c", "b"), settleSourceDrag(order, "c", 95f, ::pitch))
    }

    @Test
    fun `a just-swapped row does not swap straight back`() {
        val swapped = settleSourceDrag(listOf("a", "b", "c"), "a", 55f, ::pitch)
        assertEquals(swapped, settleSourceDrag(swapped, "a", 55f, ::pitch))
    }

    @Test
    fun `equal rows keep the old one-pitch rule`() {
        val even = { _: String -> 60f }
        val order = listOf("a", "b", "c")
        assertEquals(order, settleSourceDrag(order, "a", 35f, even))
        assertEquals(listOf("b", "a", "c"), settleSourceDrag(order, "a", 37f, even))
    }
}
