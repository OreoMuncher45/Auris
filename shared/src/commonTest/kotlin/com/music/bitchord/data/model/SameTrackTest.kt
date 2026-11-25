package com.music.bitchord.data.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SameTrackTest {
    private fun song(id: String, title: String = "Kissik", artist: String = "Singer A") =
        Song(videoId = id, title = title, artist = artist, thumbnailUrl = null)

    @Test
    fun theSameIdIsTheSameTrack() {
        assertTrue(song("yt1").isSameTrackAs(song("yt1")))
    }

    @Test
    fun twoUploadsWithTheSameTitleAndCreditAreTwoTracks() {
        // An album cut and its single, or one film song released once per
        // dubbed version under the same credit: the old match lit both.
        assertFalse(song("yt1").isSameTrackAs(song("yt2")))
    }

    @Test
    fun differentTitlesWithTheSameIdAreStillTheSameTrack() {
        // The player can rewrite a title from the watch response; the id holds.
        assertTrue(song("yt1", title = "Kissik (From \"Film\")").isSameTrackAs(song("yt1")))
    }

    @Test
    fun aSpotifyRowMatchesTheYouTubeTrackItResolvedToByName() {
        assertTrue(song("spotify:abc").isSameTrackAs(song("yt1")))
        assertTrue(song("yt1").isSameTrackAs(song("spotify:abc")))
        assertFalse(song("spotify:abc").isSameTrackAs(song("yt1", artist = "Singer B")))
    }

    @Test
    fun aPendingSpotifyRowIsTheQueueEntryMadeFromIt() {
        // Different credit on purpose: the id alone has to carry it.
        assertTrue(song("sp:abc").isSameTrackAs(song("spotify:abc", artist = "Other")))
        assertTrue(song("sp-miss:abc").isSameTrackAs(song("spotify:abc", artist = "Other")))
    }

    @Test
    fun twoSpotifyTracksWithDifferentIdsAreTwoTracks() {
        assertFalse(song("spotify:abc").isSameTrackAs(song("spotify:def")))
        assertFalse(song("sp:abc").isSameTrackAs(song("spotify:def")))
    }

    @Test
    fun nothingPlayingMatchesNothing() {
        assertFalse(song("yt1").isSameTrackAs(null))
    }

    @Test
    fun aCardWithoutAnIdNeverMatchesAYouTubeTrackByName() {
        assertFalse(isSamePlayingTrack(null, "Kissik", "Singer A", "yt1", "Kissik", "Singer A"))
        assertFalse(isSamePlayingTrack("", "Kissik", "Singer A", "yt1", "Kissik", "Singer A"))
    }
}
