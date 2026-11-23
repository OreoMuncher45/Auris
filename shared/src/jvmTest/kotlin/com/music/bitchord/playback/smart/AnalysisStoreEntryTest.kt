package com.music.bitchord.playback.smart

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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

    @Test
    fun theCopyAResultWasMeasuredOnComesBackWithIt() {
        withStore { store, _ ->
            store.save("t", analysis(), measuredOn = "video#analysis#alt2")
            val entry = assertNotNull(store.loadEntry("t"))
            assertEquals("video#analysis#alt2", entry.measuredOn)
            // On the analysis itself too, so the grid and its copy arrive as one.
            assertEquals("video#analysis#alt2", entry.analysis.measuredOn)
        }
    }

    @Test
    fun anEntryFromBeforeTheCopyWasRecordedStillLoadsAndNamesNone() {
        withStore { store, directory ->
            store.save("t", analysis())
            // As an older build wrote it: no field at all.
            val file = directory.walkTopDown().single { it.isFile && it.name.endsWith(".json") }
            file.writeText(file.readText().replace(Regex(",?\"measuredOn\":null"), ""))

            val entry = assertNotNull(store.loadEntry("t"))
            assertEquals(120.0, entry.analysis.bpm)
            assertNull(entry.measuredOn)
        }
    }
}
