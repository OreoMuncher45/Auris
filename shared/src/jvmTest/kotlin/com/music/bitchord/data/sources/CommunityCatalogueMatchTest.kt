package com.music.bitchord.data.sources

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertTrue

class CommunityCatalogueMatchTest {

    private fun row(
        id: String,
        title: String,
        artist: String,
        album: String,
        seconds: Int?,
    ): Song = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = seconds?.let { "%d:%02d".format(it / 60, it % 60) },
        albumName = album,
        sourceQuality = "LOSSLESS",
        isExplicit = false,
    )

    private fun rowsForWishYouWereHere(): List<Song> = listOf(
        row("3697671542", "Wish You Were Here (Take 1)", "Pink Floyd", "Wish You Were Here 50", 301),
        row("116914042", "Wish You Were Here", "Pink Floyd", "Wish You Were Here", 334),
        row("1520205182", "Wish You Were Here (Live)", "Pink Floyd", "Pulse (Live)", 395),
        row("116914064", "Wish You Were Here (Live)", "Pink Floyd", "Delicate Sound of Thunder (Live)", 289),
        row("1352940262", "Wish You Were Here (Live at Knebworth 1990)", "Pink Floyd", "Live at Knebworth 1990", 288),
        row("3697671432", "Wish You Were Here (feat. Stéphane Grappelli)", "Pink Floyd", "Wish You Were Here 50", 372),
        row("139138685", "Wish You Were Here", "Bee Gees", "One", 285),
        row("15489584", "Wish You Were Here", "Incubus", "Morning View", 211),
        row("637113942", "Wish You Were Here", "Avril Lavigne", "Goodbye Lullaby (Expanded Edition)", 225),
        row("378389921", "Wish You Were Here", "The Milk Carton Kids", "Wish You Were Here", 285),
    )

    private fun rowsForGhostbusters(): List<Song> = listOf(
        row("969252", "Ghostbusters (from the \"Ghostbusters\" Original Motion Picture Soundtrack)", "Ray Parker Jr.", "Ghostbusters (Original Motion Picture Soundtrack)", 238),
        row("128547767", "Ghostbusters", "Ray Parker, Jr.", "Ghostbusters (Original Motion Picture Soundtrack)", 245),
        row("781292102", "Ghostbusters (Instrumental Version)", "Ray Parker Jr.", "Ghostbusters (Original Motion Picture Soundtrack)", 287),
    )

    private fun rowsForRadiohead(): List<Song> = listOf(
        row("138544273", "Bullet Proof ... I Wish I Was", "Radiohead", "The Bends", 208),
        row("1930119807", "Bullet Proof.. I Wish I Was", "Eliza Lumley", "She Talks In Maths: Interpretations Of Radiohead", 170),
    )

    @Test
    fun pinkFloydWithoutRuntimeMatches() {
        val target = TrackMatcher.Target(
            title = "Wish You Were Here",
            artist = "PINK FLOYD",
            durationSec = null,
            album = "Echoes: The Best of Pink Floyd",
        )
        val matches = TrackMatcher.ranked(rowsForWishYouWereHere(), target)
        assertTrue(
            matches.isNotEmpty(),
            "no match for Pink Floyd; wanted=${TrackMatcher.queries(target)}",
        )
    }

    @Test
    fun pinkFloydWithRuntimeMatches() {
        val target = TrackMatcher.Target(
            title = "Wish You Were Here",
            artist = "PINK FLOYD",
            durationSec = 334,
            album = "Echoes: The Best of Pink Floyd",
        )
        val matches = TrackMatcher.ranked(rowsForWishYouWereHere(), target)
        assertTrue(matches.isNotEmpty(), "no match for Pink Floyd with a runtime")
    }

    @Test
    fun ghostbustersMatches() {
        val target = TrackMatcher.Target(
            title = "Ghostbusters",
            artist = "Ray Parker, Jr.",
            durationSec = 246,
            album = "Ghostbusters (Original Motion Picture Soundtrack) (2016)",
        )
        val matches = TrackMatcher.ranked(rowsForGhostbusters(), target)
        assertTrue(matches.isNotEmpty(), "no match for Ghostbusters")
    }

    @Test
    fun radioheadMatches() {
        val target = TrackMatcher.Target(
            title = "Bullet Proof ... I Wish I Was",
            artist = "Radiohead",
            durationSec = 209,
            album = "The Bends",
        )
        val matches = TrackMatcher.ranked(rowsForRadiohead(), target)
        assertTrue(matches.isNotEmpty(), "no match for Radiohead")
    }
}
