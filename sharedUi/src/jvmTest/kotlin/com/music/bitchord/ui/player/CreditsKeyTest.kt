package com.music.bitchord.ui.player

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The credits crossfade's key: it is the words, and each instance draws the
 * song it belongs to.
 */
class CreditsKeyTest {

    private fun song(id: String, title: String, artist: String, albumId: String? = null) =
        Song(videoId = id, title = title, artist = artist, thumbnailUrl = null, albumId = albumId)

    @Test
    fun twoCutsWithTheSameWordsAreOneKey() {
        val audio = CreditsKey(song("a", "Song", "Artist", albumId = "album"), aiTrack = false)
        val video = CreditsKey(song("b", "Song", "Artist", albumId = null), aiTrack = true)
        assertEquals(audio, video)
        assertEquals(audio.hashCode(), video.hashCode())
    }

    @Test
    fun aRenamedCutIsANewKey() {
        assertNotEquals(
            CreditsKey(song("a", "Song", "Artist"), aiTrack = false),
            CreditsKey(song("b", "Song (Live)", "Artist"), aiTrack = false),
        )
    }

    @Test
    fun theOutgoingInstanceDrawsItsOwnSongAndBadges() {
        val outgoing = CreditsKey(song("a", "First", "Artist"), aiTrack = true)
        val current = CreditsKey(song("b", "Second", "Artist"), aiTrack = false)
        val drawn = outgoing.shownFor(current)
        assertSame(outgoing.song, drawn.song)
        assertTrue(drawn.aiTrack)
    }

    @Test
    fun theCurrentInstanceDrawsTheNewestSong() {
        // Same words, different cut: the instance on screen is the old key, but
        // the album link and badges must be the newest song's.
        val before = CreditsKey(song("a", "Song", "Artist", albumId = "old"), aiTrack = false)
        val current = CreditsKey(song("b", "Song", "Artist", albumId = "new"), aiTrack = true)
        val drawn = before.shownFor(current)
        assertSame(current.song, drawn.song)
        assertTrue(drawn.aiTrack)
    }
}
