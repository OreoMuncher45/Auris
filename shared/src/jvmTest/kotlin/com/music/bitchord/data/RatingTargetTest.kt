package com.music.bitchord.data

import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.SPOTIFY_MISSING_PREFIX
import com.music.bitchord.data.model.SPOTIFY_PENDING_PREFIX
import com.music.bitchord.data.model.SPOTIFY_ROW_PREFIX
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The id a rating is written against.
 *
 * A rating is YouTube Music's, and it takes a YouTube video id. A Spotify row
 * carries a synthetic one, and writing that to the rating API asks YouTube
 * about a video that does not exist — which is why the heart did nothing on
 * every track found in Spotify's catalogue or through a Spotify search.
 */
class RatingTargetTest {

    @Test
    fun aSpotifyRowIsNotAYouTubeVideoId() {
        assertTrue(RatingTarget.isRowId("${SPOTIFY_ROW_PREFIX}4uLU6hMCjMI75M1A2tKUQC"))
        assertTrue(RatingTarget.isRowId("${SPOTIFY_PENDING_PREFIX}4uLU6hMCjMI75M1A2tKUQC"))
        assertTrue(RatingTarget.isRowId("${SPOTIFY_MISSING_PREFIX}4uLU6hMCjMI75M1A2tKUQC"))
    }

    @Test
    fun aYouTubeVideoIdIsNotARowId() {
        assertFalse(RatingTarget.isRowId("dQw4w9WgXcQ"))
        assertFalse(RatingTarget.isRowId(""))
        assertNull(RatingTarget.spotifyIdOf("dQw4w9WgXcQ"))
    }

    @Test
    fun aSourceBackedRowIsNotAYouTubeVideoId() {
        assertTrue(RatingTarget.isRowId("src:cfg-1::track-42"))
        assertNull(RatingTarget.spotifyIdOf("src:cfg-1::track-42"))
    }

    @Test
    fun aMalformedSourceKeyIsNotARowId() {
        assertFalse(RatingTarget.isRowId("src:cfg-1"))
        assertFalse(RatingTarget.isRowId("src::track-42"))
    }

    @Test
    fun theSpotifyIdIsReadOutOfTheRowId() {
        assertEquals(
            "4uLU6hMCjMI75M1A2tKUQC",
            RatingTarget.spotifyIdOf("${SPOTIFY_ROW_PREFIX}4uLU6hMCjMI75M1A2tKUQC"),
        )
        assertEquals(
            "4uLU6hMCjMI75M1A2tKUQC",
            RatingTarget.spotifyIdOf("${SPOTIFY_PENDING_PREFIX}4uLU6hMCjMI75M1A2tKUQC"),
        )
        assertEquals(
            "4uLU6hMCjMI75M1A2tKUQC",
            RatingTarget.spotifyIdOf("${SPOTIFY_MISSING_PREFIX}4uLU6hMCjMI75M1A2tKUQC"),
        )
    }

    @Test
    fun aResolvedRowRemembersItsRecording() {
        val row = "${SPOTIFY_ROW_PREFIX}4uLU6hMCjMI75M1A2tKUQC"
        RatingTarget.remember(row, "dQw4w9WgXcQ")
        assertEquals("dQw4w9WgXcQ", RatingTarget.videoIdFor(row))
    }

    @Test
    fun theFirstAnswerIsTheOneThatStays() {
        // The row was played on one recording; a later search that picks
        // another is a second answer to a question already answered.
        val row = "${SPOTIFY_ROW_PREFIX}0B6xUeuTGrKjL3M3rK9mF1q"
        RatingTarget.remember(row, "dQw4w9WgXcQ")
        RatingTarget.remember(row, "oHg5SJYRHAq3mOCvDBVIgw")
        assertEquals("dQw4w9WgXcQ", RatingTarget.videoIdFor(row))
    }

    @Test
    fun aYouTubeIdIsNeverARowIdEvenWhenRemembered() {
        RatingTarget.remember("dQw4w9WgXcQ", "dQw4w9WgXcQ")
        assertNull(RatingTarget.videoIdFor("dQw4w9WgXcQ"))
    }

    @Test
    fun thePromotedTopTrackCanBeTheRecordingBehindARow() = runBlocking {
        val row = Song(
            videoId = "${SPOTIFY_ROW_PREFIX}top-result",
            title = "Shape of You",
            artist = "Ed Sheeran",
            thumbnailUrl = null,
        )
        val recording = Song(
            videoId = "dQw4w9WgXcQ",
            title = "Shape of You",
            artist = "Ed Sheeran",
            thumbnailUrl = null,
        )

        val resolved = RatingTarget.resolve(row) {
            listOf(SearchResult.TopTrack(recording))
        }

        assertEquals(recording.videoId, resolved)
        assertEquals(recording.videoId, RatingTarget.videoIdFor(row.videoId))
    }

    @Test
    fun aSourceBackedRowIsResolvedToItsYouTubeRecording() = runBlocking {
        val row = Song(
            videoId = "src:cfg-1::track-42",
            title = "Shape of You",
            artist = "Ed Sheeran",
            thumbnailUrl = null,
        )
        val recording = Song(
            videoId = "oHg5SJYRHAq3mOCvDBVIgw",
            title = "Shape of You",
            artist = "Ed Sheeran",
            thumbnailUrl = null,
        )

        val resolved = RatingTarget.resolve(row) {
            listOf(SearchResult.Track(recording))
        }

        assertEquals(recording.videoId, resolved)
    }

    @Test
    fun aRowWithNoMatchingRecordingHasNoRatingTarget() = runBlocking {
        val row = Song(
            videoId = "${SPOTIFY_ROW_PREFIX}no-match",
            title = "Shape of You",
            artist = "Ed Sheeran",
            thumbnailUrl = null,
        )

        val resolved = RatingTarget.resolve(row) { emptyList() }

        assertNull(resolved)
        assertNull(RatingTarget.videoIdFor(row.videoId))
    }

    @Test
    fun aLaterMatcherQueryCanFindTheRecording() = runBlocking {
        val row = Song(
            videoId = "${SPOTIFY_ROW_PREFIX}second-query",
            title = "Shape of You",
            artist = "Ed Sheeran",
            thumbnailUrl = null,
        )
        val recording = Song(
            videoId = "oHg5SJYRHAq3mOCvDBVIgw",
            title = "Shape of You",
            artist = "Ed Sheeran",
            thumbnailUrl = null,
        )
        var searches = 0

        val resolved = RatingTarget.resolve(row) {
            searches += 1
            if (searches == 1) emptyList() else listOf(SearchResult.Track(recording))
        }

        assertEquals(2, searches)
        assertEquals(recording.videoId, resolved)
    }

    @Test
    fun aYouTubeRowNeedsNoCatalogueSearch() = runBlocking {
        var searched = false
        val row = Song(
            videoId = "dQw4w9WgXcQ",
            title = "Shape of You",
            artist = "Ed Sheeran",
            thumbnailUrl = null,
        )

        val resolved = RatingTarget.resolve(row) {
            searched = true
            emptyList()
        }

        assertEquals(row.videoId, resolved)
        assertFalse(searched)
    }
}
