package com.music.bitchord.playback.smart

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AnalysisStoreSchemaTest {

    private fun analysis() = TrackAnalysis(status = TrackAnalysis.STATUS_READY, trackId = "t", bpm = 120.0)

    @Test
    fun aSchemaThreeEntryIsDiscardedSoTheTrackIsMeasuredAgain() {
        val directory = Files.createTempDirectory("analysis-store").toFile()
        try {
            AnalysisStore { directory }.save("t", analysis())
            // Rewrite the saved entry as a build before schema 4 would have
            // left it: possibly measured before the models were downloaded.
            val file = assertNotNull(File(directory, "smart_analysis").listFiles()?.single())
            file.writeText(file.readText().replaceFirst(Regex("\"version\"\\s*:\\s*\\d+"), "\"version\":3"))

            assertNull(AnalysisStore { directory }.load("t"))
            assertFalse(file.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun anEntryWrittenNowStillRestores() {
        val directory = Files.createTempDirectory("analysis-store").toFile()
        try {
            AnalysisStore { directory }.save("t", analysis())
            assertNotNull(AnalysisStore { directory }.load("t"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
