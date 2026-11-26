package com.music.bitchord.playback.smart

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RenditionKeysTest {

    @AfterTest
    fun restore() {
        AutomixAnalysisSource.rendition = { null }
    }

    @Test
    fun eachRenditionHasItsOwnEntries() {
        val plain = AutomixAnalysisSource
        assertEquals("vid", plain.playbackKey("vid", null))
        assertEquals("vid#premium", plain.playbackKey("vid", "premium"))
        assertNotEquals(plain.analysisCacheKey("vid", null), plain.analysisCacheKey("vid", "premium"))
        assertNotEquals(plain.analysisSubstituteKey("vid", null), plain.analysisSubstituteKey("vid", "premium"))
        // The ordinary rendition's keys are unchanged.
        assertEquals("vid#analysis", plain.analysisCacheKey("vid", null))
    }

    @Test
    fun onlyTheCurrentRenditionsCopiesAreCanonical() {
        AutomixAnalysisSource.rendition = { "premium" }
        assertTrue(AutomixAnalysisSource.isCanonicalYouTubeRendition("vid", "vid#premium"))
        assertTrue(AutomixAnalysisSource.isCanonicalYouTubeRendition("vid", "vid#analysis-premium"))
        assertFalse(AutomixAnalysisSource.isCanonicalYouTubeRendition("vid", "vid"))
        assertFalse(AutomixAnalysisSource.isCanonicalYouTubeRendition("vid", "vid#analysis"))
        assertEquals("vid", AutomixAnalysisSource.videoIdOfAnalysisKey("vid#analysis-premium"))
        assertEquals("vid", AutomixAnalysisSource.videoIdOfAnalysisKey("vid#analysis-premium#alt2"))
    }

    @Test
    fun twoRenditionsAnalysesAreStoredApartAndReadBackUnderTheirOwnKeys() {
        val directory = Files.createTempDirectory("analysis-store").toFile()
        try {
            val store = AnalysisStore { directory }
            val ordinary = AutomixAnalysisSource.scopedTrackId("vid", null)
            val premium = AutomixAnalysisSource.scopedTrackId("vid", "premium")
            assertNotEquals(ordinary, premium)
            store.save(ordinary, TrackAnalysis(status = TrackAnalysis.STATUS_READY, trackId = ordinary, bpm = 120.0), "vid#analysis")
            store.save(premium, TrackAnalysis(status = TrackAnalysis.STATUS_READY, trackId = premium, bpm = 121.0), "vid#analysis-premium")

            val a = assertNotNull(store.loadEntry(ordinary))
            val b = assertNotNull(store.loadEntry(premium))
            assertEquals(120.0, a.analysis.bpm)
            assertEquals("vid#analysis", a.measuredOn)
            assertEquals(121.0, b.analysis.bpm)
            assertEquals("vid#analysis-premium", b.measuredOn)
        } finally {
            directory.deleteRecursively()
        }
    }
}
