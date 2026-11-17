/*
 * Copyright (C) 2026 BitChord
 *
 * Where the player's ⋯ menu lands, as arithmetic. Like HeldMenuMorphTest next
 * door, none of it needs a renderer, so it runs on every host.
 */

package com.music.bitchord.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassActionMenuPlacementTest {

    // A 1080 × 2400 phone; the band the menu may use, margins already taken off.
    private val top = 12
    private val bottom = 2400 - 12
    private val gap = 6
    private val minRoom = 220

    /** The ⋯ beside the title, near the bottom of Now Playing. */
    private val lowTop = 2150
    private val lowBottom = 2238

    @Test
    fun aButtonNearTheBottomOpensUpwards() {
        val slot = glassMenuSlot(lowTop, lowBottom, top, bottom, gap, minRoom)
        assertFalse(slot.below)
    }

    @Test
    fun aTallMenuAboveTheButtonStopsAtTheBandAndNeverCoversTheButton() {
        val slot = glassMenuSlot(lowTop, lowBottom, top, bottom, gap, minRoom)
        // Far taller than the room: the measured height is the cap.
        val height = slot.maxHeight
        val y = glassMenuY(slot, height)
        assertEquals("starts exactly at the top of the band", top, y)
        assertEquals("ends one gap above the button", lowTop - gap, y + height)
    }

    @Test
    fun aShortMenuAboveTheButtonStandsOnIt() {
        val slot = glassMenuSlot(lowTop, lowBottom, top, bottom, gap, minRoom)
        val y = glassMenuY(slot, 400)
        assertEquals(lowTop - gap - 400, y)
    }

    @Test
    fun aButtonNearTheTopOpensDownwardsAndStaysAboveTheBandsBottom() {
        val slot = glassMenuSlot(100, 188, top, bottom, gap, minRoom)
        assertTrue(slot.below)
        val y = glassMenuY(slot, slot.maxHeight)
        assertEquals(188 + gap, y)
        assertEquals(bottom, y + slot.maxHeight)
    }

    @Test
    fun enoughRoomBelowWinsEvenWhenThereIsMoreAbove() {
        val slot = glassMenuSlot(1500, 1588, top, bottom, gap, minRoom)
        assertTrue(slot.below)
        assertEquals(bottom - 1588 - gap, slot.maxHeight)
    }

    @Test
    fun aButtonOutsideTheBandStillKeepsTheMenuInsideIt() {
        // The button sits above the band [40, 60] entirely.
        val slot = glassMenuSlot(anchorTop = 0, anchorBottom = 8, top = 40, bottom = 60, gap = gap, minRoom = minRoom)
        assertTrue(slot.maxHeight >= 0)
        val y = glassMenuY(slot, slot.maxHeight)
        assertTrue("top $y inside the band", y >= 40)
        assertTrue("bottom ${y + slot.maxHeight} inside the band", y + slot.maxHeight <= 60)
        // And below it.
        val under = glassMenuSlot(anchorTop = 90, anchorBottom = 98, top = 40, bottom = 60, gap = gap, minRoom = minRoom)
        val underY = glassMenuY(under, under.maxHeight)
        assertTrue(underY >= 40 && underY + under.maxHeight <= 60)
    }

    @Test
    fun theMenuLinesUpWithTheButtonsRightEdgeInsideTheMargins() {
        // No side insets: the band across is just the margins.
        assertEquals(1000 - 825, glassMenuX(anchorRight = 1000, left = 33, right = 1080 - 33, menuWidth = 825))
        // A button at the left edge cannot drag the menu past the margin.
        assertEquals(33, glassMenuX(anchorRight = 100, left = 33, right = 1080 - 33, menuWidth = 825))
        // Nor past the right margin.
        assertEquals(1080 - 33 - 825, glassMenuX(anchorRight = 1080, left = 33, right = 1080 - 33, menuWidth = 825))
    }

    @Test
    fun aSideCutoutPushesTheMenuInwards() {
        // A 120px cutout on the left, hosted at the window's origin.
        val left = glassMenuSafeStart(inset = 120, hostStart = 0) + 33
        val right = glassMenuSafeEnd(hostSize = 1080, windowSize = 1080, inset = 0, hostStart = 0) - 33
        assertEquals(153, glassMenuX(anchorRight = 200, left = left, right = right, menuWidth = 825))
        // And a side navigation bar on the right.
        val rightWithBar = glassMenuSafeEnd(hostSize = 1080, windowSize = 1080, inset = 126, hostStart = 0) - 33
        assertEquals(1080 - 126 - 33 - 825, glassMenuX(anchorRight = 1080, left = 33, right = rightWithBar, menuWidth = 825))
    }

    @Test
    fun theBandStartsBelowTheStatusBarAndEndsAboveTheGestureBar() {
        // A 2400px window with a 110px status bar and a 63px gesture bar,
        // hosted at the window's origin.
        assertEquals(110, glassMenuSafeStart(inset = 110, hostStart = 0))
        assertEquals(2400 - 63, glassMenuSafeEnd(hostSize = 2400, windowSize = 2400, inset = 63, hostStart = 0))
    }

    @Test
    fun aMenuOpenedUpwardsNeverReachesUnderTheStatusBar() {
        val safeTop = glassMenuSafeStart(inset = 110, hostStart = 0) + 12
        val safeBottom = glassMenuSafeEnd(2400, 2400, 63, 0) - 12
        val slot = glassMenuSlot(lowTop, lowBottom, safeTop, safeBottom, gap, minRoom)
        val y = glassMenuY(slot, slot.maxHeight)
        assertEquals(safeTop, y)
    }

    @Test
    fun aHostBelowTheWindowTopOnlyLosesWhatIsLeftOfTheBar() {
        assertEquals(30, glassMenuSafeStart(inset = 110, hostStart = 80))
        assertEquals(0, glassMenuSafeStart(inset = 110, hostStart = 200))
        // The host's own bottom stays the limit when it ends above the gesture bar.
        assertEquals(1500, glassMenuSafeEnd(hostSize = 1500, windowSize = 2400, inset = 63, hostStart = 200))
    }

    @Test
    fun anUnknownWindowHeightFallsBackToTheHost() {
        assertEquals(2400 - 63, glassMenuSafeEnd(hostSize = 2400, windowSize = 0, inset = 63, hostStart = 0))
    }
}
