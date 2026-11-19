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
        val payloads = CreditsPayloads()
        payloads.record(outgoing)
        payloads.record(current)
        val drawn = payloads.shown(outgoing, current)
        assertSame(outgoing.song, drawn.song)
        assertTrue(drawn.aiTrack)
    }

    @Test
    fun theCurrentInstanceDrawsTheNewestSong() {
        // Same words, different cut: the instance on screen is the old key, but
        // the album link and badges must be the newest song's.
        val before = CreditsKey(song("a", "Song", "Artist", albumId = "old"), aiTrack = false)
        val current = CreditsKey(song("b", "Song", "Artist", albumId = "new"), aiTrack = true)
        val payloads = CreditsPayloads()
        payloads.record(before)
        val drawn = payloads.shown(before, current)
        assertSame(current.song, drawn.song)
        assertTrue(drawn.aiTrack)
    }

    @Test
    fun anOutgoingInstanceFadesOutTheNewestCutOfItsWords() {
        // A, then A2 with the same words (new album link, now marked AI), then B.
        // The crossfade still holds A as the outgoing key; what it fades out
        // must be A2, the cut that was on screen.
        val a = CreditsKey(song("a", "Song", "Artist", albumId = "old"), aiTrack = false)
        val a2 = CreditsKey(song("a2", "Song", "Artist", albumId = "new"), aiTrack = true)
        val b = CreditsKey(song("b", "Other", "Artist"), aiTrack = false)
        val payloads = CreditsPayloads()
        payloads.record(a)
        payloads.record(a2)
        payloads.record(b)
        val drawn = payloads.shown(a, current = b)
        assertEquals("new", drawn.song.albumId)
        assertTrue(drawn.aiTrack)
    }

    @Test
    fun anOutgoingKeyNeverRecordedDrawsItself() {
        val a = CreditsKey(song("a", "Song", "Artist"), aiTrack = true)
        val b = CreditsKey(song("b", "Other", "Artist"), aiTrack = false)
        assertSame(a, CreditsPayloads().shown(a, current = b))
    }
}
