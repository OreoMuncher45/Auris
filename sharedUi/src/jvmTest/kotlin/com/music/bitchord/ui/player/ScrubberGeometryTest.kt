package com.music.bitchord.ui.player

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The quality badge sits under the seek bar, and a tap on it must never land
 * in the seek bar's touch box: the bar scrubs as soon as a finger goes down.
 */
class ScrubberGeometryTest {
    private val seekBoxBottom = SEEK_ACTIVE_HEIGHT + SEEK_TOUCH_PADDING
    private val badgeTapTop = seekBoxBottom + SCRUBBER_LABEL_GAP

    @Test
    fun badgeTapTargetStartsBelowTheSeekTouchBox() {
        assertTrue(badgeTapTop > seekBoxBottom, "badge tap top $badgeTapTop overlaps seek box ending at $seekBoxBottom")
    }

    @Test
    fun badgeTapTargetIsAsTallAsTheSeekTouchBox() {
        assertTrue(SCRUBBER_LABEL_SLOT >= seekBoxBottom)
    }

    @Test
    fun transportGapGivesBackTheSlotGrowth() {
        // Roomy screen: the gap shrinks by exactly the growth, so the transport stays put.
        val spread = 80.dp
        val before = 8.dp + spread / 2 - scrubberDropOf(spread)
        assertEquals(before - SCRUBBER_SLOT_GROWTH, scrubberToTransportGap(spread))
    }

    @Test
    fun transportGapNeverDropsBelowTheMinimum() {
        for (spread in listOf(0.dp, 4.dp, 10.dp, 20.dp)) {
            assertTrue(scrubberToTransportGap(spread) >= SCRUBBER_MIN_GAP, "spread $spread")
        }
    }
}
