package com.music.bitchord.ui.replay

import com.music.bitchord.data.stats.NameEntry
import com.music.bitchord.data.stats.RankedEntry
import com.music.bitchord.data.stats.RankedSong
import com.music.bitchord.data.stats.ReplayPeriod
import com.music.bitchord.data.stats.ReplaySummary
import com.music.bitchord.data.stats.TrackEntry
import com.music.bitchord.data.model.Song
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pressing maths, away from the screen.
 *
 * The disc is drawn from these numbers, so a band that overlaps its neighbour or
 * a chapter that points at the wrong month is a visual bug that only shows up on
 * a phone. These pin the arithmetic instead.
 */
class RecordPressTest {

    private fun band(
        month: String,
        hours: List<Long>,
        days: Map<Int, Long> = mapOf(1 to 60_000L),
        artist: String = "Aphex Twin",
        plays: Int = 3,
    ) = RecordBand(
        month = YearMonth.parse(month),
        ms = hours.sum(),
        plays = plays,
        topArtist = artist,
        topTracks = listOf(TrackEntry(id = "a", title = "Xtal", artist = artist, ms = hours.sum(), plays = plays)),
        topAlbum = NameEntry(name = "Selected Ambient Works"),
        hours = hours,
        days = days,
    )

    private fun summary(
        artists: List<RankedEntry> = listOf(
            RankedEntry("Aphex Twin", null, null, null, 2_000_000L, 40),
        ),
        busiestDay: String? = "2026-03-04",
    ) = ReplaySummary(
        period = ReplayPeriod.THIS_YEAR,
        label = "2026",
        totalMs = 3_600_000L,
        totalPlays = 60,
        songs = listOf(RankedSong(Song(videoId = "v", title = "Xtal", artist = "Aphex Twin", thumbnailUrl = null), 2_000_000L, 40)),
        artists = artists,
        albums = emptyList(),
        genres = emptyList(),
        hourOfDay = List(24) { 1L },
        busiestDay = busiestDay,
        busiestDayMs = 600_000L,
        distinctSongs = 10,
        distinctArtists = 4,
        distinctAlbums = 3,
        since = "2025-11",
    )

    private fun press() = RecordPress(
        summary = summary(),
        bands = listOf(
            band("2026-01", List(24) { if (it == 2) 600_000L else 0L }, mapOf(4 to 120_000L)),
            band("2026-02", List(24) { if (it == 23) 1_200_000L else 0L }, mapOf(4 to 300_000L)),
            band("2026-03", List(24) { if (it == 12) 300_000L else 0L }, mapOf(4 to 600_000L)),
        ),
        holder = "tester",
    )

    @Test
    fun bands_tile_the_groove_without_gaps() {
        val ranges = bandRange(press().bands)
        assertEquals(3, ranges.size)
        assertEquals(0f, ranges.first().start, 0.0001f)
        assertEquals(1f, ranges.last().endInclusive, 0.0001f)
        ranges.zipWithNext().forEach { (a, b) ->
            assertEquals(a.endInclusive, b.start, 0.0001f)
        }
        // Thickness follows time played: February is twice January.
        val jan = ranges[0].endInclusive - ranges[0].start
        val feb = ranges[1].endInclusive - ranges[1].start
        assertEquals(2f, feb / jan, 0.01f)
    }

    @Test
    fun the_peak_month_is_the_one_with_the_most_time() {
        val p = press()
        assertEquals("2026-02", p.peakBand?.month.toString())
        assertEquals(1, p.peakBandIndex)
    }

    @Test
    fun night_is_ten_pm_to_four_am_and_the_share_is_of_the_whole_press() {
        val p = press()
        // January 2am, February 11pm, March noon.
        assertEquals(1_800_000L, p.nightMs)
        assertEquals(1_800_000f / 2_100_000f, p.nightShare, 0.0001f)
    }

    @Test
    fun the_longest_hour_is_the_one_with_the_most_time_across_every_band() {
        assertEquals(23, press().peakHour)
    }

    @Test
    fun chapters_run_intro_needle_then_each_finding_then_the_close() {
        val ids = press().chapters.map { it.id }
        assertEquals("intro", ids.first())
        assertEquals("stopped", ids[1])
        assertEquals("finale", ids.last())
        assertTrue(ids.contains("artist"))
        assertTrue(ids.contains("night"))
        assertTrue(ids.contains("day"))
        // One per band plus the fixed ends.
        assertTrue(ids.size >= 6)
    }

    @Test
    fun every_zoomed_chapter_names_a_band_that_exists() {
        val p = press()
        p.chapters.filter { it.zoom > 1f }.forEach { chapter ->
            val index = chapter.focusBand
            assertTrue("${chapter.id} points at ${index}", index != null && index in p.bands.indices)
        }
    }

    @Test
    fun the_artist_chapter_lists_the_bands_that_artist_led() {
        val p = press()
        assertEquals(listOf(0, 1, 2), p.artistBands)
        val artist = p.chapters.first { it.id == "artist" }
        assertEquals(3, artist.stats.first { it.first == "Bands led" }.second.toInt())
    }

    @Test
    fun listening_time_reads_as_minutes_hours_then_days() {
        assertEquals("5 min", formatAxis(300_000L))
        assertEquals("2 hr", formatAxis(7_200_000L))
        assertEquals("2 hr 30 min", formatAxis(9_000_000L))
        assertEquals("2 d 3 hr", formatAxis(183_600_000L))
    }

    @Test
    fun small_shares_do_not_round_to_zero_percent() {
        assertEquals("<1%", percent(0.0004f))
        assertEquals("4.2%", percent(0.042f))
        assertEquals("42%", percent(0.42f))
    }

    @Test
    fun hours_are_named_the_way_people_say_them() {
        assertEquals("midnight", formatHourLabel(0))
        assertEquals("3am", formatHourLabel(3))
        assertEquals("noon", formatHourLabel(12))
        assertEquals("11pm", formatHourLabel(23))
    }

    @Test
    fun the_streak_only_counts_days_that_were_played() {
        val p = press()
        // Three bands, each with day 4 played, so the run is 1 day per month
        // (nothing bridges the gap between months here).
        assertEquals(1, p.streak.first)
    }
}
