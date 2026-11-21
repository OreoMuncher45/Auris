package com.music.bitchord.data.sources

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An extension asked for a lossless copy must not hand back a different
 * recording under the right title: a re-upload tagged with the uploader's
 * name, a tribute act, a DJ set's cut, a fan blend. Each refusal leaves
 * [TrackMatcher.ranked] empty, which is what sends the resolver on to the
 * next source and finally YouTube.
 *
 * The second half pins down what must keep matching: remasters, featured
 * and split credits, packaged and other-script titles, and live albums.
 */
class TrackMatcherWrongRecordingTest {

    private fun song(
        title: String,
        artist: String,
        durationText: String? = null,
        album: String? = null,
    ) = Song(
        videoId = "$title|$artist|$album",
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = durationText,
        albumName = album,
    )

    private fun refused(candidate: Song, target: TrackMatcher.Target) {
        assertNull(TrackMatcher.score(candidate, target), "'${candidate.title}' by '${candidate.artist}' was accepted")
        assertTrue(TrackMatcher.ranked(listOf(candidate), target).isEmpty())
    }

    private fun accepted(candidate: Song, target: TrackMatcher.Target) {
        assertNotNull(TrackMatcher.score(candidate, target), "'${candidate.title}' by '${candidate.artist}' was refused")
    }

    // ── The four reported swaps ─────────────────────────────────────────────

    private val bohemian = TrackMatcher.Target("Bohemian Rhapsody", "Queen", durationSec = 355)

    @Test
    fun `a re-upload tagged with the uploader's name is not Queen's master`() {
        // Same runtime to the second: this is what used to admit it through
        // the credits-disagree override, on a title that only *contained* the
        // one asked for.
        refused(song("Bohemian Rhapsody Kit0", "KitO", "5:55"), bohemian)
        refused(song("Bohemian Rhapsody Kit0", "KitO", "5:54"), bohemian)
    }

    @Test
    fun `a tribute show named after the artist is not the artist`() {
        val jolene = TrackMatcher.Target("Jolene", "Dolly Parton", durationSec = 162)
        refused(song("Jolene", "Dolly Parton- Experience ® Tribute Show", "2:50"), jolene)
        // Not even at the exact runtime, through the credits override.
        refused(song("Jolene", "Dolly Parton- Experience ® Tribute Show", "2:42"), jolene)
        assertTrue(!TrackMatcher.sharesArtist("Dolly Parton", "Dolly Parton- Experience ® Tribute Show"))
    }

    private val beByYou = TrackMatcher.Target("Be By You", "River Lane", durationSec = 210)

    @Test
    fun `a DJ live mix cut is not the single`() {
        refused(song("Be By You (Mixed)", "River Lane", "3:10"), beByYou)
        refused(song("Be By You", "River Lane", "3:20", album = "Club Night Live (DJ Mix)"), beByYou)
        refused(song("Be By You Live DJ Mix", "River Lane"), beByYou)
        refused(song("Be By You (Continuous Mix)", "River Lane", "3:25"), beByYou)
    }

    private val choosinTexas = TrackMatcher.Target("Choosin' Texas", "Ella Langley", durationSec = 204)

    @Test
    fun `a fan blend is not the song`() {
        refused(song("choosin texas (kylino blend)", "kylino", "3:24"), choosinTexas)
        refused(song("choosin texas (kylino blend)", "Ella Langley", "3:30"), choosinTexas)
    }

    @Test
    fun `the genuine row wins over a tribute in the same answer`() {
        val jolene = TrackMatcher.Target("Jolene", "Dolly Parton", durationSec = 162)
        val rows = listOf(
            song("Jolene", "Dolly Parton- Experience ® Tribute Show", "2:42"),
            song("Jolene", "Dolly Parton", "2:41"),
        )
        assertEquals(listOf("Dolly Parton"), TrackMatcher.ranked(rows, jolene).map { it.artist })
    }

    // ── Legitimate matches that must survive ────────────────────────────────

    @Test
    fun `a remaster is the same recording`() {
        accepted(song("Bohemian Rhapsody - Remastered 2011", "Queen", "5:55"), bohemian)
        accepted(song("Bohemian Rhapsody (2011 Remaster)", "Queen", "5:54", album = "A Night At The Opera (2011 Remaster)"), bohemian)
    }

    @Test
    fun `featured and split credits still share an artist`() {
        accepted(song("Be By You (feat. Hollow Pine)", "River Lane", "3:30"), beByYou)
        accepted(song("Be By You", "River Lane, Hollow Pine", "3:31"), beByYou)
        accepted(
            song("Señorita", "Shawn Mendes & Camila Cabello", "3:11"),
            TrackMatcher.Target("Señorita", "Shawn Mendes, Camila Cabello", 191),
        )
        // A band credit that contains the name is still the band, when it is
        // not an imitation act.
        accepted(
            song("Purple Haze", "The Jimi Hendrix Experience", "2:51"),
            TrackMatcher.Target("Purple Haze", "Jimi Hendrix", 170),
        )
    }

    @Test
    fun `packaged and other-script titles still match`() {
        accepted(
            song("Paniyon Sa", "Atif Aslam, Tulsi Kumar", "4:01"),
            TrackMatcher.Target("Paniyon Sa (From \"Satyamev Jayate\")", "Atif Aslam", 241),
        )
        accepted(
            song("Koi Kogare", "milet", "4:21"),
            TrackMatcher.Target("コイコガレ", "milet", 261),
        )
        accepted(song("Choosin' Texas", "Ella Langley", "3:24"), choosinTexas)
        accepted(song("Choosin Texas", "Ella Langley", "3:25"), choosinTexas)
    }

    @Test
    fun `a live request still meets its live album row`() {
        // Marker on the request's title, row filed plain on the live album.
        accepted(
            song("Wish You Were Here", "Pink Floyd", "6:35", album = "Pulse (Live)"),
            TrackMatcher.Target("Wish You Were Here (Live)", "Pink Floyd", 395),
        )
        // No marker on either title: the request is itself from that album.
        accepted(
            song("Wish You Were Here", "Pink Floyd", "6:35", album = "Pulse (Live)"),
            TrackMatcher.Target("Wish You Were Here", "Pink Floyd", 395, album = "Pulse (Live)"),
        )
        // But the studio request does not get the live album's row.
        refused(
            song("Wish You Were Here", "Pink Floyd", "5:34", album = "Delicate Sound of Thunder (Live)"),
            TrackMatcher.Target("Wish You Were Here", "Pink Floyd", 334, album = "Wish You Were Here"),
        )
    }

    @Test
    fun `an act genuinely named with an imitation word still matches itself`() {
        accepted(
            song("Lost", "Cover Drive", "3:30"),
            TrackMatcher.Target("Lost", "Cover Drive", 210),
        )
    }

    @Test
    fun `a DJ artist in an Artist - Title upload is not a DJ mix`() {
        accepted(
            song("Taki Taki", "DJ Snake", "3:32"),
            TrackMatcher.Target("DJ Snake - Taki Taki", "DJ Snake", 212),
        )
    }
}
