package com.music.bitchord.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MixPreviewRuleTest {
    @Test
    fun aBlockedPairShowsNoBlendPreview() {
        assertTrue(MixPreviewRule.clears(loopChosen = false, planBlocked = true))
    }

    @Test
    fun anAllowedBlendOrFallbackFadeIsPreviewedAndALoopIsNot() {
        assertFalse(MixPreviewRule.clears(loopChosen = false, planBlocked = false))
        assertTrue(MixPreviewRule.clears(loopChosen = true, planBlocked = false))
    }
}
