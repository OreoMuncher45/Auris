package com.music.bitchord.playback

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The queue's search, as arithmetic.
 *
 * Every rule a listener will judge this feature by is here: what a typed name
 * matches, and what happens when the same recording is both on the device and
 * in the catalogue. The phone's half — reading [com.music.bitchord.download.Downloads]
 * and asking YouTube Music — is wiring, and wiring is not where the answers
 * live.
 */
class QueueSearchTest {

    private fun song(
        id: String,
        title: String,
        artist: String = "Someone",
        album: String? = null,
        local: Boolean = false,
    ) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        albumName = album,
        localUri = if (local) "content://media/external/audio/media/1" else null,
    )

    @Test
    fun `an empty query is not a search`() {
        val tracks = listOf(song("a", "Anything"))
        assertFalse(QueueSearch.matches(tracks[0], ""))
        assertFalse(QueueSearch.matches(tracks[0], "   "))
        assertTrue(QueueSearch.localMatches(tracks, "").isEmpty())
    }

    @Test
    fun `title, artist and album all answer the name`() {
        val track = song("a", "Bounce", artist = "System of a Down", album = "Toxicity")
        assertTrue(QueueSearch.matches(track, "bounce"))
        assertTrue(QueueSearch.matches(track, "system"))
        assertTrue(QueueSearch.matches(track, "toxicity"))
        assertFalse(QueueSearch.matches(track, "chop suey"))
    }

    @Test
    fun `case and stray spaces do not matter`() {
        val track = song("a", "Bounce", artist = "SOAD")
        assertTrue(QueueSearch.matches(track, "  BOUNCE "))
        assertTrue(QueueSearch.matches(track, "soad"))
    }

    @Test
    fun `the device's own songs come first, and the catalogue's copy of one is not repeated`() {
        val downloaded = song("same", "Bounce", local = true)
        val catalogue = listOf(
            song("same", "Bounce"),
            song("other", "Aerials"),
        )
        val merged = QueueSearch.merge(listOf(downloaded), catalogue)
        assertEquals(listOf("same", "other"), merged.map { it.videoId })
        // The row that survived is the one that plays without a network.
        assertTrue(merged.first().localUri != null)
    }

    @Test
    fun `a catalogue row with no local twin is kept`() {
        val merged = QueueSearch.merge(
            local = listOf(song("local", "Bounce", local = true)),
            online = listOf(song("remote", "Chop Suey")),
        )
        assertEquals(listOf("local", "remote"), merged.map { it.videoId })
    }

    @Test
    fun `the same recording is not listed twice even from the catalogue alone`() {
        val merged = QueueSearch.merge(
            local = emptyList(),
            online = listOf(song("dup", "Bounce"), song("dup", "Bounce")),
        )
        assertEquals(listOf("dup"), merged.map { it.videoId })
    }

    @Test
    fun `an answer is a list to choose from, not a catalogue`() {
        val many = (1..80).map { song("id$it", "Song $it") }
        assertEquals(QueueSearch.MAX_RESULTS, QueueSearch.merge(emptyList(), many).size)
        assertEquals(
            QueueSearch.MAX_RESULTS,
            QueueSearch.merge(many.map { it.copy(localUri = "content://x/$it") }, many).size,
        )
    }

    @Test
    fun `the device's search is the same match rule`() {
        val tracks = listOf(
            song("a", "Bounce", local = true),
            song("b", "Aerials", local = true),
        )
        assertEquals(listOf("a"), QueueSearch.localMatches(tracks, "bounce").map { it.videoId })
    }
}
