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

    @Test
    fun aLevelWindowKeepsItsPrecisionAcrossAReload() {
        withStore { store, _ ->
            val levels = floatArrayOf(0.334f, 0.3341f, 0.01005f, 0.5f)
            store.save(
                "t",
                analysis().copy(levelWindows = listOf(CurveWindow(10.0, 20.0, levels))),
            )
            val restored = assertNotNull(store.load("t")).levelWindows.single().values
            // Within a ten-thousandth: two bars that differ in memory still
            // differ after a reload, so loop screening decides the same way.
            for (i in levels.indices) assertEquals(levels[i], restored[i], 1e-4f)
            kotlin.test.assertTrue(restored[1] > restored[0])
        }
    }

    @Test
    fun aWindowStartsExactlyWhereItDidAfterAReload() {
        withStore { store, _ ->
            // Starting between milliseconds: rounded, the restored window ended
            // early and a loop at the edge of its coverage lost part of its end.
            val window = CurveWindow(10.0004, 44100.0 / 1024, FloatArray(172) { 0.5f })
            store.save("t", analysis().copy(levelWindows = listOf(window)))
            val restored = assertNotNull(store.load("t")).levelWindows.single()
            assertEquals(window.startTime, restored.startTime)
            assertEquals(window.endTime, restored.endTime)
        }
    }

    @Test
    fun twoIdsWithOneHashEachRestoreTheirOwnAnalysis() {
        withStore { store, _ ->
            // "Aa" and "BB" share String.hashCode() and length: under the old
            // file name the second save replaced the first.
            check("Aa".hashCode() == "BB".hashCode())
            store.save("Aa", analysis().copy(bpm = 100.0), "Aa#analysis")
            store.save("BB", analysis().copy(bpm = 140.0), "BB#analysis")

            val a = assertNotNull(store.loadEntry("Aa"))
            val b = assertNotNull(store.loadEntry("BB"))
            assertEquals(100.0, a.analysis.bpm)
            assertEquals("Aa#analysis", a.measuredOn)
            assertEquals(140.0, b.analysis.bpm)
            assertEquals("BB#analysis", b.measuredOn)
        }
    }
}
