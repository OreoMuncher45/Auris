package com.music.bitchord.ui.components.floatingtabbar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A held nav-bar pill must stay on the bar however far the finger goes (#104). */
class TabPillDragPositionTest {

    private val step = 100f
    private val last = 3

    @Test
    fun `inside the bar the pill follows the finger one to one`() {
        assertEquals(0f, tabPillDragPosition(0, 0f, step, last), 1e-4f)
        assertEquals(1.5f, tabPillDragPosition(0, 150f, step, last), 1e-4f)
        assertEquals(3f, tabPillDragPosition(1, 200f, step, last), 1e-4f)
        assertEquals(0.25f, tabPillDragPosition(2, -175f, step, last), 1e-4f)
    }

    @Test
    fun `dragging far right from any tab stops just past the last tab`() {
        for (from in 0..last) {
            val position = tabPillDragPosition(from, 50_000f, step, last)
            assertTrue("from $from: $position", position > last)
            assertTrue("from $from: $position", position < last + PILL_EDGE_OVERSHOOT)
        }
    }

    @Test
    fun `dragging far left from any tab stops just before the first tab`() {
        for (from in 0..last) {
            val position = tabPillDragPosition(from, -50_000f, step, last)
            assertTrue("from $from: $position", position < 0f)
            assertTrue("from $from: $position", position > -PILL_EDGE_OVERSHOOT)
        }
    }

    @Test
    fun `the stretch past an end grows with the drag but slows down`() {
        val a = tabPillDragPosition(last, 20f, step, last) - last
        val b = tabPillDragPosition(last, 40f, step, last) - last
        val c = tabPillDragPosition(last, 400f, step, last) - last
        assertTrue(a > 0f && b > a && c > b)
        // Each pixel further out moves the pill less than the last.
        assertTrue((b - a) / 20f > (c - b) / 360f)
        assertTrue(c < PILL_EDGE_OVERSHOOT)
    }

    @Test
    fun `no layout yet leaves the pill on its tab`() {
        assertEquals(2f, tabPillDragPosition(2, 300f, 0f, last), 0f)
    }
}
