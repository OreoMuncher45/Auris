package com.music.bitchord.playback.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModellessResultsTest {

    private fun analysis(id: String, bpm: Double = 120.0) =
        TrackAnalysis(status = TrackAnalysis.STATUS_READY, trackId = id, duration = 200.0, bpm = bpm)

    private fun cacheOf(vararg entries: Pair<String, TrackAnalysis>) =
        AnalysisResultCache(maxEntries = 16).apply {
            for ((id, analysis) in entries) put(id, analysis) { false }
        }

    @Test
    fun `drop forgets a DSP-only result and its copy so the track is measured again`() {
        val modelless = ModellessResults()
        val dsp = analysis("a")
        val results = cacheOf("a" to dsp, "b" to analysis("b"))
        val tried = mutableSetOf("a#opus", "b#opus")
        val measured = mutableMapOf("a" to "a#opus", "b" to "b#opus")
        modelless.record("a", dsp, "a#opus")

        modelless.drop(results, tried, measured)

        assertFalse(results.containsKey("a"))
        assertTrue(results.containsKey("b"))
        assertEquals(setOf("b#opus"), tried)
        assertEquals(mapOf("b" to "b#opus"), measured)
        assertTrue(modelless.isEmpty())
    }

    @Test
    fun `a result replaced since it was recorded is left alone`() {
        val modelless = ModellessResults()
        modelless.record("a", analysis("a"), "a#opus")
        val withModels = analysis("a", bpm = 124.0)
        val results = cacheOf("a" to withModels)
        val tried = mutableSetOf("a#hifi")
        val measured = mutableMapOf("a" to "a#hifi")

        modelless.drop(results, tried, measured)

        assertEquals(withModels, results["a"])
        assertEquals(setOf("a#hifi"), tried)
        assertEquals(mapOf("a" to "a#hifi"), measured)
        assertTrue(modelless.isEmpty())
    }

    @Test
    fun `forget keeps a track measured with the models out of the next drop`() {
        val modelless = ModellessResults()
        val dsp = analysis("a")
        modelless.record("a", dsp, "a#opus")
        modelless.forget("a")
        assertTrue(modelless.isEmpty())

        val results = cacheOf("a" to dsp)
        modelless.drop(results, mutableSetOf("a#opus"), mutableMapOf())
        assertTrue(results.containsKey("a"))
    }
}
