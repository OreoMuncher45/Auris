package com.music.bitchord

import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.LocalMusicSort
import com.music.bitchord.ui.screens.sortedForLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Local Music's Track number sort, and the tags it reads. */
class LocalTrackNumberSortTest {

    private fun song(id: String, title: String, album: String? = "Album", track: Int? = null, disc: Int? = null) =
        Song(
            videoId = id,
            title = title,
            artist = "Artist",
            thumbnailUrl = null,
            albumName = album,
            trackNumber = track,
            discNumber = disc,
        )

    @Test
    fun mediaStoreUnpacksDiscPrefix() {
        assertEquals(3, LocalMediaRepository.trackNumberFromMediaStore(3))
        assertEquals(3, LocalMediaRepository.trackNumberFromMediaStore(1003))
        assertEquals(1, LocalMediaRepository.trackNumberFromMediaStore(2001))
        assertNull(LocalMediaRepository.trackNumberFromMediaStore(0))
        assertNull(LocalMediaRepository.trackNumberFromMediaStore(-1))
        assertNull(LocalMediaRepository.trackNumberFromMediaStore(null))
        assertNull(LocalMediaRepository.discNumberFromMediaStore(3))
        assertEquals(1, LocalMediaRepository.discNumberFromMediaStore(1003))
        assertEquals(2, LocalMediaRepository.discNumberFromMediaStore(2001))
    }

    @Test
    fun tagParsesHeadOfTotal() {
        assertEquals(3, LocalMediaRepository.numberFromTag("3"))
        assertEquals(3, LocalMediaRepository.numberFromTag("3/12"))
        assertEquals(7, LocalMediaRepository.numberFromTag(" 7 / 10 "))
        assertNull(LocalMediaRepository.numberFromTag(null))
        assertNull(LocalMediaRepository.numberFromTag("abc"))
        assertNull(LocalMediaRepository.numberFromTag("0"))
    }

    @Test
    fun sortsEachAlbumByDiscThenTrack() {
        val songs = listOf(
            song("b2", "A", album = "B", track = 2),
            song("a-d2t1", "Z", track = 1, disc = 2),
            song("a-t2", "Y", track = 2, disc = 1),
            song("b1", "B", album = "b", track = 1),
            song("a-t1", "X", track = 1),
        )
        assertEquals(
            listOf("a-t1", "a-t2", "a-d2t1", "b1", "b2"),
            songs.sortedForLibrary(LocalMusicSort.TRACK_NUMBER).map { it.videoId },
        )
    }

    @Test
    fun untaggedRowsFollowTheTaggedOnesAndNoAlbumGoesLast() {
        val songs = listOf(
            song("none", "A", album = null, track = 1),
            song("untagged", "B"),
            song("t1", "C", track = 1),
        )
        assertEquals(
            listOf("t1", "untagged", "none"),
            songs.sortedForLibrary(LocalMusicSort.TRACK_NUMBER).map { it.videoId },
        )
    }
}
