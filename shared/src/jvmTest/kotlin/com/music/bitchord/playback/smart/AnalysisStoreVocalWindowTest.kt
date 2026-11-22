package com.music.bitchord.playback.smart

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AnalysisStoreVocalWindowTest {

    @Test
    fun aStoredVocalWindowKeepsEveryValueOnItsSideOfTheThreshold() {
        val directory = Files.createTempDirectory("analysis-store").toFile()
        try {
            val store = AnalysisStore { directory }
            val values = floatArrayOf(0.246f, 0.249f, 0.25f, 0.254f, 0.9f, 0.1f)
            store.save(
                "t",
                TrackAnalysis(
                    status = TrackAnalysis.STATUS_READY,
                    trackId = "t",
                    bpm = 120.0,
                    vocalWindows = listOf(CurveWindow(10.0, 10.0, values)),
                ),
            )

            val restored = assertNotNull(store.load("t")).vocalWindows.single().values

            for (i in values.indices) {
                assertEquals(
                    values[i] >= VOCAL_ACTIVE_THRESHOLD,
                    restored[i] >= VOCAL_ACTIVE_THRESHOLD,
                    "value ${values[i]} came back as ${restored[i]}",
                )
            }
            assertEquals(0.9f, restored[4], 1e-6f)
            assertEquals(0.1f, restored[5], 1e-6f)
        } finally {
            directory.deleteRecursively()
        }
    }
}
