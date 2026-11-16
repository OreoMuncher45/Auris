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

    private val ids = listOf("inactive-off", "inactive-on", "inactive-kept", "inactive-youtube", "inactive-yt-config")

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

        val stale = StreamChoice.inactivePins(enabled = setOf("addon"), youtubeEnabled = true, keep = setOf("inactive-kept"), youtubeConfigIds = emptySet())

        assertEquals(mapOf("inactive-off" to false), stale.filterKeys { it in ids })
    }

    @Test
    fun aYouTubePinIsStaleOnlyWhileYouTubeIsOff() {
        StreamChoice.remember("inactive-youtube", SourceStream("https://example.invalid/yt"), substituted = false)
        // A substitute with no source id names no source, so it is never retired.
        pin("inactive-on", null)

        val off = StreamChoice.inactivePins(enabled = emptySet(), youtubeEnabled = false, keep = emptySet(), youtubeConfigIds = emptySet())
        val on = StreamChoice.inactivePins(enabled = emptySet(), youtubeEnabled = true, keep = emptySet(), youtubeConfigIds = emptySet())

        assertEquals(mapOf("inactive-youtube" to true), off.filterKeys { it in ids })
        assertEquals(emptyMap<String, Boolean>(), on.filterKeys { it in ids })
    }

    @Test
    fun aYouTubePinInsideTheWindowStaysOnYouTubeWhenItIsSwitchedOff() {
        // A Spotify row's YouTube fallback is pinned like this too: as YouTube's own.
        StreamChoice.remember("inactive-youtube", SourceStream("https://example.invalid/yt"), substituted = false)

        val inside = StreamChoice.inactivePins(enabled = emptySet(), youtubeEnabled = false, keep = setOf("inactive-youtube"), youtubeConfigIds = emptySet())
        val outside = StreamChoice.inactivePins(enabled = emptySet(), youtubeEnabled = false, keep = emptySet(), youtubeConfigIds = emptySet())

        assertEquals(emptyMap<String, Boolean>(), inside.filterKeys { it in ids })
        assertEquals(mapOf("inactive-youtube" to true), outside.filterKeys { it in ids })
    }

    @Test
    fun aPinCarryingYouTubesConfigIdIsHandedOverAsYouTube() {
        // A source-backed track that fell back to YouTube: YouTube's own config id.
        pin("inactive-yt-config", "yt-config")

        val off = StreamChoice.inactivePins(
            enabled = emptySet(),
            youtubeEnabled = false,
            keep = emptySet(),
            youtubeConfigIds = setOf("yt-config"),
        )
        val on = StreamChoice.inactivePins(
            enabled = setOf("yt-config"),
            youtubeEnabled = true,
            keep = emptySet(),
            youtubeConfigIds = setOf("yt-config"),
        )

        // Classified as YouTube, so its YouTube-origin cache entry is the one dropped.
        assertEquals(mapOf("inactive-yt-config" to true), off.filterKeys { it in ids })
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
            StreamChoice.inactivePins(enabled = setOf("addon"), youtubeEnabled = true, keep = emptySet(), youtubeConfigIds = emptySet()).keys,
        )

        assertNull(StreamChoice.of("inactive-off"))
        assertTrue(StreamChoice.remember("inactive-off", replacement, substituted = true))
        assertEquals("addon", StreamChoice.of("inactive-off")?.sourceConfigId)
    }
}
