package com.music.bitchord.playback.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoopDeckCopyTest {

    private val opus = AutomixAnalysisSource.analysisCacheKey("vid")
    private val substitute = AutomixAnalysisSource.analysisSubstituteKey("vid")

    @Test
    fun aPlanIsCutFromTheCopyItWasMadeOn() {
        assertEquals(opus, loopDeckCopy(measured = opus, playing = "vid", plannedOn = opus, videoId = "vid").key)
    }

    @Test
    fun aResultMeasuredOnAnotherCopyAfterThePlanSuppliesNoDeck() {
        // Planned on the substitute's provisional grid; the whole-track pass
        // then published one measured on the Opus copy.
        val decision = loopDeckCopy(measured = opus, playing = "vid", plannedOn = substitute, videoId = "vid")
        assertNull(decision.key)
        assertEquals("planned on $substitute, now measured on $opus", decision.refusal)
    }

    @Test
    fun aPlayerOnAnUpgradeOrSubstituteGetsNoDeckFromTheOpusGrid() {
        assertNull(loopDeckCopy(measured = opus, playing = "vid#alt2", plannedOn = opus, videoId = "vid").key)
        assertNull(loopDeckCopy(measured = substitute, playing = "vid", plannedOn = substitute, videoId = "vid").key)
    }

    @Test
    fun aGridWithNoKnownCopyGetsNoDeck() {
        assertNull(loopDeckCopy(measured = null, playing = "vid", plannedOn = null, videoId = "vid").key)
    }
}
