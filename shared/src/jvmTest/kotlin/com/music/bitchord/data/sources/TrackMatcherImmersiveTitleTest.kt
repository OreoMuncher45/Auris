package com.music.bitchord.data.sources

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * YouTube files its immersive uploads under the stereo track's title with the
 * format appended ("Morning Light Eclipsa Audio"). Lossless catalogues file
 * only the stereo title, so the format name must not count as part of the song.
 */
class TrackMatcherImmersiveTitleTest {

    private fun song(title: String, artist: String, durationText: String) = Song(
        videoId = title,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = durationText,
    )

    @Test
    fun anEclipsaAudioUploadMatchesTheStereoTrack() {
        val target = TrackMatcher.Target("Morning Light Eclipsa Audio", "River Lane, Juniper Hale", durationSec = 200)
        val rows = listOf(song("Morning Light", "River Lane, Juniper Hale", "3:20"))

        assertEquals("Morning Light", TrackMatcher.best(rows, target)?.title)
    }

    @Test
    fun aDashedEclipsaAudioUploadMatchesTheStereoTrack() {
        val target = TrackMatcher.Target("Harbour Lights - Title Track Eclipsa Audio", "River Lane, Juniper Hale", durationSec = 250)
        val rows = listOf(song("Harbour Lights - Title Track", "River Lane, Juniper Hale", "4:10"))

        assertEquals("Harbour Lights - Title Track", TrackMatcher.best(rows, target)?.title)
    }

    @Test
    fun theSearchDoesNotAskForTheFormatName() {
        val target = TrackMatcher.Target("Morning Light Eclipsa Audio", "River Lane", durationSec = 200)

        assertTrue(TrackMatcher.queries(target).none { "eclipsa" in it.lowercase() }, TrackMatcher.queries(target).toString())
    }
}
