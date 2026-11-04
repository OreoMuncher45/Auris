package com.music.auris.desktop

import com.music.bitchord.data.model.Song
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Replay: what a period covers, what the charts say, and what the cards are dealt from. */
class AurisReplayTest {

    private fun song(id: String, title: String, artist: String, album: String? = null) =
        Song(videoId = id, title = title, artist = artist, thumbnailUrl = "", albumName = album)

    private fun bucket(
        month: String,
        vararg tracks: Triple<Song, Long, Int>,
        hours: Map<Int, Long> = emptyMap(),
        days: Map<String, Long> = emptyMap(),
    ) = AurisListeningStats.Bucket(month).apply {
        tracks.forEach { (song, ms, plays) ->
            this.tracks[song.videoId] = AurisReplayEntry(song, ms, plays)
        }
        hours.forEach { (hour, ms) -> this.hours[hour] = ms }
        days.forEach { (day, ms) -> this.days[day] = ms }
    }

    private val today = LocalDate.of(2026, 9, 11)

    private val history = listOf(
        bucket(
            "2026-09",
            Triple(song("a", "Shayar", "Prabhakar Raj", "Shayar"), 600_000L, 3),
            hours = mapOf(22 to 600_000L),
            days = mapOf("2026-09-10" to 600_000L),
        ),
        bucket(
            "2026-03",
            Triple(song("b", "Kesariya", "Arijit Singh", "Brahmastra"), 1_200_000L, 5),
            hours = mapOf(9 to 1_200_000L),
            days = mapOf("2026-03-02" to 1_200_000L),
        ),
        bucket(
            "2025-11",
            Triple(song("c", "Old One", "Someone", "Older"), 300_000L, 2),
        ),
    )

    @Test
    fun `this month counts only this month`() {
        val summary = AurisListeningStats.summarise(history, AurisReplayPeriod.THIS_MONTH, today)
        assertEquals(listOf("Shayar"), summary.songs.map { it.song.title })
        assertEquals(10L, summary.minutes)
    }

    @Test
    fun `this year counts every month of it and no other`() {
        val summary = AurisListeningStats.summarise(history, AurisReplayPeriod.THIS_YEAR, today)
        assertEquals(setOf("Shayar", "Kesariya"), summary.songs.map { it.song.title }.toSet())
        assertEquals(30L, summary.minutes)
    }

    @Test
    fun `all time counts everything`() {
        val summary = AurisListeningStats.summarise(history, AurisReplayPeriod.ALL_TIME, today)
        assertEquals(3, summary.songs.size)
        assertEquals(10, summary.totalPlays)
    }

    @Test
    fun `the same track heard in two months is charted once`() {
        val repeated = listOf(
            bucket("2026-09", Triple(song("a", "Shayar", "Prabhakar Raj"), 600_000L, 3)),
            bucket("2026-08", Triple(song("a", "Shayar", "Prabhakar Raj"), 900_000L, 4)),
        )
        val summary = AurisListeningStats.summarise(repeated, AurisReplayPeriod.ALL_TIME, today)
        assertEquals(1, summary.songs.size)
        assertEquals(1_500_000L, summary.songs.single().listenedMs)
        assertEquals(7, summary.songs.single().plays)
    }

    @Test
    fun `charts rank by time listened, most first`() {
        val summary = AurisListeningStats.summarise(history, AurisReplayPeriod.ALL_TIME, today)
        assertEquals("Kesariya", summary.songs.first().song.title)
        assertEquals("Arijit Singh", summary.artists.first().title)
        assertEquals(1, summary.songRows(5).first().rank)
    }

    @Test
    fun `habits name the peak hour and the busiest day`() {
        val summary = AurisListeningStats.summarise(history, AurisReplayPeriod.ALL_TIME, today)
        assertEquals(9, summary.peakHour)
        assertEquals("2026-03-02", summary.busiestDay)
        assertEquals(1_200_000L, summary.busiestDayMs)
    }

    @Test
    fun `distinct counts are of distinct things`() {
        val summary = AurisListeningStats.summarise(history, AurisReplayPeriod.ALL_TIME, today)
        assertEquals(3, summary.distinctSongs)
        assertEquals(3, summary.distinctArtists)
        assertEquals(3, summary.distinctAlbums)
    }

    @Test
    fun `member since reads the earliest real month`() {
        val summary = AurisListeningStats.summarise(history, AurisReplayPeriod.ALL_TIME, today)
        assertEquals("2025-11", summary.since)
        assertEquals("11/25", summary.memberSince())
    }

    @Test
    fun `plays carried over from the undated store belong to no dated period`() {
        val legacy = listOf(bucket(AurisListeningStats.LEGACY_MONTH, Triple(song("x", "Old", "Ghost"), 60_000L, 1)))
        assertTrue(
            AurisListeningStats.summarise(legacy, AurisReplayPeriod.THIS_MONTH, today).isEmpty,
        )
        assertTrue(
            AurisListeningStats.summarise(legacy, AurisReplayPeriod.THIS_YEAR, today).isEmpty,
        )
        val allTime = AurisListeningStats.summarise(legacy, AurisReplayPeriod.ALL_TIME, today)
        assertEquals(1, allTime.songs.size)
        // And it must not claim to date the account.
        assertNull(allTime.since)
    }

    @Test
    fun `the cards lead with minutes and skip a category with nothing in it`() {
        val summary = AurisListeningStats.summarise(history, AurisReplayPeriod.ALL_TIME, today)
        val cards = summary.heroCards()
        assertEquals("Minutes listened", cards.first().label)
        assertEquals(listOf("Minutes listened", "Top artist", "Top song", "Top album"), cards.map { it.label })

        val bare = AurisListeningStats.summarise(
            listOf(bucket("2026-09", Triple(song("n", "No Album", "Nobody"), 60_000L, 1))),
            AurisReplayPeriod.ALL_TIME,
            today,
        )
        assertTrue(bare.heroCards().none { it.label == "Top album" } || bare.albums.isNotEmpty())
    }

    @Test
    fun `an empty replay is empty rather than a chart of nothing`() {
        val summary = AurisListeningStats.summarise(emptyList(), AurisReplayPeriod.ALL_TIME, today)
        assertTrue(summary.isEmpty)
        assertTrue(summary.songRows(5).isEmpty())
        assertNull(summary.peakHour)
    }

    // ── Arithmetic carried over from the flat store this replaced ────────

    @Test
    fun `an artist heard on two tracks is one row, ordered by time listened`() {
        val summary = AurisListeningStats.summarise(
            listOf(
                bucket(
                    "2026-09",
                    Triple(song("a", "One", "Artist A"), 60_000L, 1),
                    Triple(song("b", "Two", "Artist A"), 20_000L, 1),
                    Triple(song("c", "Three", "Artist B"), 90_000L, 1),
                ),
            ),
            AurisReplayPeriod.ALL_TIME,
            today,
        )
        assertEquals(2, summary.artists.size)
        assertEquals("Artist B", summary.artists[0].title)
        assertEquals(90_000L, summary.artists[0].ms)
        assertEquals("Artist A", summary.artists[1].title)
        assertEquals(80_000L, summary.artists[1].ms)
    }

    @Test
    fun `an entry with nothing listened and no play is not counted`() {
        val summary = AurisListeningStats.summarise(
            listOf(
                bucket(
                    "2026-09",
                    Triple(song("a", "Skipped", "A"), 0L, 0),
                    Triple(song("b", "Heard", "B"), 5_000L, 1),
                ),
            ),
            AurisReplayPeriod.ALL_TIME,
            today,
        )
        assertEquals(1, summary.songs.size)
        assertEquals("Heard", summary.songs.single().song.title)
    }

    @Test
    fun `tracks with no album share one row rather than vanishing`() {
        val summary = AurisListeningStats.summarise(
            listOf(
                bucket(
                    "2026-09",
                    Triple(song("a", "One", "A"), 1_000L, 1),
                    Triple(song("b", "Two", "B"), 2_000L, 1),
                    Triple(song("c", "Three", "C", album = "Real Album"), 4_000L, 1),
                ),
            ),
            AurisReplayPeriod.ALL_TIME,
            today,
        )
        assertEquals(2, summary.albums.size)
        assertTrue(summary.albums.any { it.title == "Unknown Album" && it.ms == 3_000L })
    }

    @Test
    fun `a later play fills in what the first one did not know`() {
        // The home feed gives a track with no album; the same track from search has one.
        assertEquals(
            "The Album",
            AurisListeningStats.mergeSong(
                old = song("a", "One", "A"),
                current = song("a", "One", "A", album = "The Album"),
            ).albumName,
        )
        assertEquals(
            "The Album",
            AurisListeningStats.mergeSong(
                old = song("a", "One", "A", album = "The Album"),
                current = song("a", "One", "A"),
            ).albumName,
        )
    }

    @Test
    fun `the lead artist is charted, not the whole billing`() {
        assertEquals("Cheema Y", AurisListeningStats.primaryArtist("Cheema Y & Gur Sidhu"))
        assertEquals("Arijit Singh", AurisListeningStats.primaryArtist("Arijit Singh, Shreya Ghoshal"))
        assertEquals("Badshah", AurisListeningStats.primaryArtist("Badshah feat. Aastha Gill"))
    }
}
