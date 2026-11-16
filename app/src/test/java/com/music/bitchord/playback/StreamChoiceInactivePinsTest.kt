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
        pin("inactive-youtube", null)

        val stale = StreamChoice.inactivePins(active = setOf("addon"), keep = setOf("inactive-kept"))

        assertEquals(setOf("inactive-off"), stale.filter { it in ids }.toSet())
    }

    @Test
    fun aReleasedPinNoLongerBlocksItsReplacement() {
        pin("inactive-off", "saavn")
        // Before the hand-over the old pin wins: this is the review finding.
        val replacement = SourceStream("https://example.invalid/new", sourceConfigId = "addon")
        assertEquals(false, StreamChoice.remember("inactive-off", replacement, substituted = true))
        assertEquals("saavn", StreamChoice.of("inactive-off")?.sourceConfigId)

        StreamChoice.forgetAll(StreamChoice.inactivePins(active = setOf("addon"), keep = emptySet()))

        assertNull(StreamChoice.of("inactive-off"))
        assertTrue(StreamChoice.remember("inactive-off", replacement, substituted = true))
        assertEquals("addon", StreamChoice.of("inactive-off")?.sourceConfigId)
    }
}
