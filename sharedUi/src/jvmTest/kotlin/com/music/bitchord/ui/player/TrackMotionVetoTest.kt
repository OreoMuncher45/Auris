package com.music.bitchord.ui.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackMotionVetoTest {
    @Test
    fun theRevealReplacesTheSlideOnlyWhereItIsDrawn() {
        assertFalse(TrackMotionVeto.allows(visible = true, landscape = false) { true })
        // Landscape draws no reveal: the change keeps its slide.
        assertTrue(TrackMotionVeto.allows(visible = true, landscape = true) { true })
    }

    @Test
    fun noRevealOrAHiddenPlayer() {
        assertTrue(TrackMotionVeto.allows(visible = true, landscape = false) { false })
        assertFalse(TrackMotionVeto.allows(visible = false, landscape = true) { false })
    }
}
