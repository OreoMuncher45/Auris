package com.music.bitchord.playback.smart

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AnalysisStoreEntryTest {

    private fun withStore(block: (AnalysisStore, File) -> Unit) {
        val directory = Files.createTempDirectory("analysis-store").toFile()
        try {
            block(AnalysisStore { directory }, directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun analysis(confidence: Double = 0.9) = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY,
        trackId = "t",
        bpm = 120.0,
        headBeats = BeatSide(listOf(0.5, 1.0), listOf(0.5), 120.0, confidence),
    )

    @Test
    fun anOpeningGridKeepsItsConfidenceExactlyAcrossAReload() {
        withStore { store, _ ->
            // Just under a 0.55 threshold; rounded to three places it passed it.
            store.save("t", analysis(confidence = 0.5496))
            assertEquals(0.5496, assertNotNull(store.load("t")).headBeats!!.confidence)
        }
    }
}
