package com.music.bitchord.data.sources

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [TrackMatcher.bestOrSameArtist]: the same-artist second chance keeps the
 * requested title. It relaxes only what [TrackMatcher.score] refused beyond
 * the title.
 */
class TrackMatcherSameArtistFallbackTest {

    private fun song(
        title: String,
        artist: String,
        durationText: String? = null,
        id: String = title,
        albumName: String? = null,
        isExplicit: Boolean? = null,
    ) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = durationText,
        albumName = albumName,
        isExplicit = isExplicit,
    )

    private val target = TrackMatcher.Target("Morning Light", "River Lane", durationSec = 200)

    @Test
    fun aDifferentSongByTheSameArtistIsNotPlayed() {
        val rows = listOf(song("Evening Rain", "River Lane", "3:20"))

        assertNull(TrackMatcher.bestOrSameArtist(rows, target))
    }

    @Test
    fun theSameSongRefusedOnlyForItsRuntimeStillPlays() {
        // Far outside score's runtime window, so best() refuses it.
        val rows = listOf(song("Morning Light", "River Lane", "9:59"))
        assertNull(TrackMatcher.best(rows, target))

        assertEquals("Morning Light", TrackMatcher.bestOrSameArtist(rows, target)?.title)
    }

    @Test
    fun theSameTitleIsChosenOverAnEarlierDifferentSong() {
        val rows = listOf(
            song("Evening Rain", "River Lane", "3:20"),
            song("Morning Light", "River Lane", "9:59"),
        )

        assertEquals("Morning Light", TrackMatcher.bestOrSameArtist(rows, target)?.title)
    }

    @Test
    fun aLongerTitleContainingTheRequestedOneIsNotPlayed() {
        val short = TrackMatcher.Target("Love", "River Lane", durationSec = 200)
        val rows = listOf(song("Lovesick", "River Lane", "9:59"))

        assertNull(TrackMatcher.bestOrSameArtist(rows, short))
    }

    @Test
    fun aSameTitledRowByAnotherArtistIsNotPlayed() {
        val rows = listOf(song("Morning Light", "Someone Else", "9:59"))

        assertNull(TrackMatcher.bestOrSameArtist(rows, target))
    }

    @Test
    fun anInstrumentalIsNotPlayedForTheVocal() {
        val rows = listOf(song("Morning Light (Instrumental)", "River Lane", "9:59"))

        assertNull(TrackMatcher.bestOrSameArtist(rows, target))
    }

    @Test
    fun aRowFromAnInstrumentalAlbumIsNotPlayedForTheVocal() {
        val rows = listOf(song("Morning Light", "River Lane", "9:59", albumName = "Morning Light (Instrumental Edition)"))

        assertNull(TrackMatcher.bestOrSameArtist(rows, target))
    }

    @Test
    fun anInstrumentalRequestStillFallsBackToTheInstrumental() {
        val instrumental = TrackMatcher.Target("Morning Light (Instrumental)", "River Lane", durationSec = 200)
        val rows = listOf(song("Morning Light (Instrumental)", "River Lane", "9:59"))

        assertEquals("Morning Light (Instrumental)", TrackMatcher.bestOrSameArtist(rows, instrumental)?.title)
    }

    @Test
    fun theExplicitEditionIsNotPlayedForACleanRequest() {
        val clean = target.copy(isExplicit = false)
        val rows = listOf(song("Morning Light", "River Lane", "9:59", isExplicit = true))

        assertNull(TrackMatcher.bestOrSameArtist(rows, clean))
    }

    @Test
    fun anUnstatedEditionStillFallsBack() {
        val clean = target.copy(isExplicit = false)
        val rows = listOf(song("Morning Light", "River Lane", "9:59"))

        assertEquals("Morning Light", TrackMatcher.bestOrSameArtist(rows, clean)?.title)
    }
}
