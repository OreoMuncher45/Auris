/*
 * Copyright (C) 2026 BitChord
 *
 * Where an anchored menu lands, as arithmetic. Like HeldMenuMorphTest, none
 * of it needs a renderer, so it runs on every host.
 */

package com.music.bitchord.ui.components.menu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuPlacementTest {

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
        val slot = menuSlot(lowTop, lowBottom, top, bottom, gap, minRoom)
        assertFalse(slot.below)
    }

    @Test
    fun aTallMenuAboveTheButtonStopsAtTheBandAndNeverCoversTheButton() {
        val slot = menuSlot(lowTop, lowBottom, top, bottom, gap, minRoom)
        // Far taller than the room: the measured height is the cap.
        val height = slot.maxHeight
        val y = menuY(slot, height)
        assertEquals("starts exactly at the top of the band", top, y)
        assertEquals("ends one gap above the button", lowTop - gap, y + height)
    }

    @Test
    fun aShortMenuAboveTheButtonStandsOnIt() {
        val slot = menuSlot(lowTop, lowBottom, top, bottom, gap, minRoom)
        val y = menuY(slot, 400)
        assertEquals(lowTop - gap - 400, y)
    }

    @Test
    fun aButtonNearTheTopOpensDownwardsAndStaysAboveTheBandsBottom() {
        val slot = menuSlot(100, 188, top, bottom, gap, minRoom)
        assertTrue(slot.below)
        val y = menuY(slot, slot.maxHeight)
        assertEquals(188 + gap, y)
        assertEquals(bottom, y + slot.maxHeight)
    }

    @Test
    fun enoughRoomBelowWinsEvenWhenThereIsMoreAbove() {
        val slot = menuSlot(1500, 1588, top, bottom, gap, minRoom)
        assertTrue(slot.below)
        assertEquals(bottom - 1588 - gap, slot.maxHeight)
    }

    @Test
    fun aButtonOutsideTheBandStillKeepsTheMenuInsideIt() {
        // The button sits above the band [40, 60] entirely.
        val slot = menuSlot(anchorTop = 0, anchorBottom = 8, top = 40, bottom = 60, gap = gap, minRoom = minRoom)
        assertTrue(slot.maxHeight >= 0)
        val y = menuY(slot, slot.maxHeight)
        assertTrue("top $y inside the band", y >= 40)
        assertTrue("bottom ${y + slot.maxHeight} inside the band", y + slot.maxHeight <= 60)
        // And below it.
        val under = menuSlot(anchorTop = 90, anchorBottom = 98, top = 40, bottom = 60, gap = gap, minRoom = minRoom)
        val underY = menuY(under, under.maxHeight)
        assertTrue(underY >= 40 && underY + under.maxHeight <= 60)
    }

    @Test
    fun theMenuLinesUpWithTheButtonsRightEdgeInsideTheMargins() {
        // No side insets: the band across is just the margins.
        assertEquals(1000 - 825, menuX(anchorRight = 1000, left = 33, right = 1080 - 33, menuWidth = 825))
        // A button at the left edge cannot drag the menu past the margin.
        assertEquals(33, menuX(anchorRight = 100, left = 33, right = 1080 - 33, menuWidth = 825))
        // Nor past the right margin.
        assertEquals(1080 - 33 - 825, menuX(anchorRight = 1080, left = 33, right = 1080 - 33, menuWidth = 825))
    }

    @Test
    fun aCenteredCardUsesTheSafeBandsCenterNotTheButtonsRightEdge() {
        // The quality badge is centred, but end-aligning its card to the tap
        // target shifts the whole card left. Centre it in the usable band.
        val left = 33
        val right = 1080 - 33
        val width = 825
        val x = centeredMenuX(left, right, width)
        assertEquals((left + right) / 2f, x + width / 2f, 0.5f)
        assertEquals(127, x)

        // Asymmetric cutouts centre the card in what remains, not the window.
        assertEquals(187, centeredMenuX(left = 153, right = right, menuWidth = width))
    }

    @Test
    fun aSideCutoutPushesTheMenuInwards() {
        // A 120px cutout on the left, hosted at the window's origin.
        val left = menuSafeStart(inset = 120, hostStart = 0) + 33
        val right = menuSafeEnd(hostSize = 1080, windowSize = 1080, inset = 0, hostStart = 0) - 33
        assertEquals(153, menuX(anchorRight = 200, left = left, right = right, menuWidth = 825))
        // And a side navigation bar on the right.
        val rightWithBar = menuSafeEnd(hostSize = 1080, windowSize = 1080, inset = 126, hostStart = 0) - 33
        assertEquals(1080 - 126 - 33 - 825, menuX(anchorRight = 1080, left = 33, right = rightWithBar, menuWidth = 825))
    }

    @Test
    fun theBandStartsBelowTheStatusBarAndEndsAboveTheGestureBar() {
        // A 2400px window with a 110px status bar and a 63px gesture bar,
        // hosted at the window's origin.
        assertEquals(110, menuSafeStart(inset = 110, hostStart = 0))
        assertEquals(2400 - 63, menuSafeEnd(hostSize = 2400, windowSize = 2400, inset = 63, hostStart = 0))
    }

    @Test
    fun aMenuOpenedUpwardsNeverReachesUnderTheStatusBar() {
        val safeTop = menuSafeStart(inset = 110, hostStart = 0) + 12
        val safeBottom = menuSafeEnd(2400, 2400, 63, 0) - 12
        val slot = menuSlot(lowTop, lowBottom, safeTop, safeBottom, gap, minRoom)
        val y = menuY(slot, slot.maxHeight)
        assertEquals(safeTop, y)
    }

    @Test
    fun aHostBelowTheWindowTopOnlyLosesWhatIsLeftOfTheBar() {
        assertEquals(30, menuSafeStart(inset = 110, hostStart = 80))
        assertEquals(0, menuSafeStart(inset = 110, hostStart = 200))
        // The host's own bottom stays the limit when it ends above the gesture bar.
        assertEquals(1500, menuSafeEnd(hostSize = 1500, windowSize = 2400, inset = 63, hostStart = 200))
    }

    @Test
    fun anUnknownWindowHeightFallsBackToTheHost() {
        assertEquals(2400 - 63, menuSafeEnd(hostSize = 2400, windowSize = 0, inset = 63, hostStart = 0))
    }
}
