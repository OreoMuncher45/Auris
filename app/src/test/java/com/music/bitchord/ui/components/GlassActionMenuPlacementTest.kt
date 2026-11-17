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
        val y = glassMenuY(slot, lowTop, lowBottom, gap, height)
        assertEquals("starts exactly at the top of the band", top, y)
        assertEquals("ends one gap above the button", lowTop - gap, y + height)
    }

    @Test
    fun aShortMenuAboveTheButtonStandsOnIt() {
        val slot = glassMenuSlot(lowTop, lowBottom, top, bottom, gap, minRoom)
        val y = glassMenuY(slot, lowTop, lowBottom, gap, 400)
        assertEquals(lowTop - gap - 400, y)
    }

    @Test
    fun aButtonNearTheTopOpensDownwardsAndStaysAboveTheBandsBottom() {
        val slot = glassMenuSlot(100, 188, top, bottom, gap, minRoom)
        assertTrue(slot.below)
        val y = glassMenuY(slot, 100, 188, gap, slot.maxHeight)
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
    fun aButtonOutsideTheBandLeavesNoNegativeRoom() {
        val slot = glassMenuSlot(0, 8, top = 40, bottom = 60, gap = gap, minRoom = minRoom)
        assertTrue(slot.maxHeight >= 0)
    }

    @Test
    fun theMenuLinesUpWithTheButtonsRightEdgeInsideTheMargins() {
        assertEquals(1000 - 825, glassMenuX(anchorRight = 1000, hostWidth = 1080, menuWidth = 825, margin = 33))
        // A button at the left edge cannot drag the menu past the margin.
        assertEquals(33, glassMenuX(anchorRight = 100, hostWidth = 1080, menuWidth = 825, margin = 33))
        // Nor past the right margin.
        assertEquals(1080 - 33 - 825, glassMenuX(anchorRight = 1080, hostWidth = 1080, menuWidth = 825, margin = 33))
    }

    @Test
    fun theBandStartsBelowTheStatusBarAndEndsAboveTheGestureBar() {
        // A 2400px window with a 110px status bar and a 63px gesture bar,
        // hosted at the window's origin.
        assertEquals(110, glassMenuSafeTop(insetTop = 110, hostTop = 0))
        assertEquals(2400 - 63, glassMenuSafeBottom(hostHeight = 2400, windowHeight = 2400, insetBottom = 63, hostTop = 0))
    }

    @Test
    fun aMenuOpenedUpwardsNeverReachesUnderTheStatusBar() {
        val safeTop = glassMenuSafeTop(insetTop = 110, hostTop = 0) + 12
        val safeBottom = glassMenuSafeBottom(2400, 2400, 63, 0) - 12
        val slot = glassMenuSlot(lowTop, lowBottom, safeTop, safeBottom, gap, minRoom)
        val y = glassMenuY(slot, lowTop, lowBottom, gap, slot.maxHeight)
        assertEquals(safeTop, y)
    }

    @Test
    fun aHostBelowTheWindowTopOnlyLosesWhatIsLeftOfTheBar() {
        assertEquals(30, glassMenuSafeTop(insetTop = 110, hostTop = 80))
        assertEquals(0, glassMenuSafeTop(insetTop = 110, hostTop = 200))
        // The host's own bottom stays the limit when it ends above the gesture bar.
        assertEquals(1500, glassMenuSafeBottom(hostHeight = 1500, windowHeight = 2400, insetBottom = 63, hostTop = 200))
    }

    @Test
    fun anUnknownWindowHeightFallsBackToTheHost() {
        assertEquals(2400 - 63, glassMenuSafeBottom(hostHeight = 2400, windowHeight = 0, insetBottom = 63, hostTop = 0))
    }
}
