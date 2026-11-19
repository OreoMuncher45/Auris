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
    fun aCjkEclipsaAudioUploadMatchesTheStereoTrack() {
        val target = TrackMatcher.Target("봄날 Eclipsa Audio", "River Lane", durationSec = 200)
        val rows = listOf(song("봄날", "River Lane", "3:20"))

        assertEquals("봄날", TrackMatcher.best(rows, target)?.title)
    }

    @Test
    fun punctuationAfterTheSuffixDoesNotKeepItInACjkCore() {
        val target = TrackMatcher.Target("봄날 Eclipsa Audio", "River Lane", durationSec = 200)
        val rows = listOf(song("봄날 Eclipsa Audio!", "River Lane", "3:20"))

        assertEquals("봄날 Eclipsa Audio!", TrackMatcher.best(rows, target)?.title)
    }

    @Test
    fun everySeparatorBeforeTheLabelMatchesTheStereoTrack() {
        val rows = listOf(song("봄날", "River Lane", "3:20"))
        for (title in listOf("봄날 - Eclipsa Audio", "봄날.Eclipsa Audio", "봄날·Eclipsa Audio", "봄날 (Eclipsa Audio)", "봄날 | Eclipsa Audio")) {
            val target = TrackMatcher.Target(title, "River Lane", durationSec = 200)
            assertEquals("봄날", TrackMatcher.best(rows, target)?.title, title)
            assertTrue(TrackMatcher.queries(target).none { "eclipsa" in it }, "$title -> ${TrackMatcher.queries(target)}")
        }
    }

    @Test
    fun aTrailingLabelAfterTheSuffixLeavesTheCjkCoreAlone() {
        val target = TrackMatcher.Target("봄날 Eclipsa Audio Official", "River Lane", durationSec = 200)
        val rows = listOf(song("봄날", "River Lane", "3:20"))

        assertEquals("봄날", TrackMatcher.best(rows, target)?.title)
    }

    @Test
    fun aBracketedLabelOnALatinTitleMatchesTheStereoTrack() {
        val target = TrackMatcher.Target("Morning Light (Eclipsa Audio)", "River Lane", durationSec = 200)
        val rows = listOf(song("Morning Light", "River Lane", "3:20"))

        assertEquals("Morning Light", TrackMatcher.best(rows, target)?.title)
    }

    @Test
    fun aVersionSharingTheLabelsBracketIsStillAVersion() {
        val target = TrackMatcher.Target("Morning Light (Eclipsa Audio Live)", "River Lane", durationSec = 200)
        val rows = listOf(song("Morning Light (Live)", "River Lane", "3:20"))

        assertEquals("Morning Light (Live)", TrackMatcher.best(rows, target)?.title)
    }

    @Test
    fun aVersionAfterADashedLabelIsStillAVersion() {
        val target = TrackMatcher.Target("Morning Light - Eclipsa Audio Live", "River Lane", durationSec = 200)
        val rows = listOf(
            song("Morning Light", "River Lane", "3:20"),
            song("Morning Light (Live)", "River Lane", "3:20"),
        )

        assertEquals("Morning Light (Live)", TrackMatcher.best(rows, target)?.title)
    }

    @Test
    fun theSearchDoesNotAskForTheFormatName() {
        val target = TrackMatcher.Target("Morning Light Eclipsa Audio", "River Lane", durationSec = 200)

        assertTrue(TrackMatcher.queries(target).none { "eclipsa" in it.lowercase() }, TrackMatcher.queries(target).toString())
    }
}
