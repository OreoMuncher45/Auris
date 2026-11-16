package com.music.bitchord.playback

import com.music.bitchord.data.sources.SourceStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A pin served by a source that has been switched off is handed over for
 * tracks away from the playhead, and kept for the ones near it. Ids are unique
 * to this test because [StreamChoice] is process-wide.
 */
class StreamChoiceInactivePinsTest {

    private val ids = listOf("inactive-off", "inactive-on", "inactive-kept", "inactive-youtube")

    @After
    fun tearDown() = ids.forEach(StreamChoice::forget)

    private fun pin(id: String, source: String?) {
        StreamChoice.remember(id, SourceStream("https://example.invalid/$id", sourceConfigId = source), substituted = true)
    }

    @Test
    fun findsOnlyPinsOfInactiveSourcesOutsideTheWindow() {
        pin("inactive-off", "saavn")
        pin("inactive-on", "addon")
        pin("inactive-kept", "saavn")
        StreamChoice.remember("inactive-youtube", SourceStream("https://example.invalid/yt"), substituted = false)

        val stale = StreamChoice.inactivePins(enabled = setOf("addon"), youtubeEnabled = true, keep = setOf("inactive-kept"))

        assertEquals(mapOf("inactive-off" to false), stale.filterKeys { it in ids })
    }

    @Test
    fun aYouTubePinIsStaleOnlyWhileYouTubeIsOff() {
        StreamChoice.remember("inactive-youtube", SourceStream("https://example.invalid/yt"), substituted = false)
        // A substitute with no source id names no source, so it is never retired.
        pin("inactive-on", null)

        val off = StreamChoice.inactivePins(enabled = emptySet(), youtubeEnabled = false, keep = emptySet())
        val on = StreamChoice.inactivePins(enabled = emptySet(), youtubeEnabled = true, keep = emptySet())

        assertEquals(mapOf("inactive-youtube" to true), off.filterKeys { it in ids })
        assertEquals(emptyMap<String, Boolean>(), on.filterKeys { it in ids })
    }

    @Test
    fun aReleasedPinNoLongerBlocksItsReplacement() {
        pin("inactive-off", "saavn")
        // While the old pin is held it wins over any replacement.
        val replacement = SourceStream("https://example.invalid/new", sourceConfigId = "addon")
        assertEquals(false, StreamChoice.remember("inactive-off", replacement, substituted = true))
        assertEquals("saavn", StreamChoice.of("inactive-off")?.sourceConfigId)

        StreamChoice.forgetAll(
            StreamChoice.inactivePins(enabled = setOf("addon"), youtubeEnabled = true, keep = emptySet()).keys,
        )

        assertNull(StreamChoice.of("inactive-off"))
        assertTrue(StreamChoice.remember("inactive-off", replacement, substituted = true))
        assertEquals("addon", StreamChoice.of("inactive-off")?.sourceConfigId)
    }
}
