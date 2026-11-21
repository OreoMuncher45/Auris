package com.music.bitchord.data.model

import kotlin.test.Test
import kotlin.test.assertEquals

class RepeatedRecordingsTest {
    private fun track(
        id: String,
        title: String,
        artist: String,
        length: String? = "4:08",
        video: Boolean = false,
        explicit: Boolean? = null,
    ) = SearchResult.Track(
        Song(
            videoId = id, title = title, artist = artist, thumbnailUrl = null,
            durationText = length, isVideo = video, isExplicit = explicit,
        ),
    )

    private fun ids(rows: List<SearchResult>) = rows.map { it.songOrNull()?.videoId ?: "browse" }

    @Test
    fun oneRecordingListedTwiceUnderTwoIdsIsShownOnce() {
        val rows = listOf(
            track("album", "Kissik", "Singer A"),
            track("single", "kissik ", "singer a"),
        )
        assertEquals(listOf("album"), ids(rows.withoutRepeatedRecordings()))
    }

    @Test
    fun dubbedVersionsWithTheirOwnSingersAreKept() {
        val rows = listOf(
            track("te", "Peelings", "Singer A, Singer B"),
            track("ta", "Peelings", "Singer C, Singer D"),
        )
        assertEquals(listOf("te", "ta"), ids(rows.withoutRepeatedRecordings()))
    }

    @Test
    fun aDifferentLengthIsADifferentCut() {
        val rows = listOf(track("a", "Kissik", "Singer A", "4:08"), track("b", "Kissik", "Singer A", "4:09"))
        assertEquals(listOf("a", "b"), ids(rows.withoutRepeatedRecordings()))
    }

    @Test
    fun rowsWithoutALengthAreNeverMerged() {
        val rows = listOf(track("a", "Kissik", "Singer A", null), track("b", "Kissik", "Singer A", null))
        assertEquals(listOf("a", "b"), ids(rows.withoutRepeatedRecordings()))
    }

    @Test
    fun aVideoIsNotTheSameRowAsTheSong() {
        val rows = listOf(track("a", "Kissik", "Singer A"), track("b", "Kissik", "Singer A", video = true))
        assertEquals(listOf("a", "b"), ids(rows.withoutRepeatedRecordings()))
    }

    @Test
    fun theCleanAndExplicitCutsAreBothKept() {
        // The audio switch picks between them by the explicit flag.
        val rows = listOf(
            track("explicit", "Kissik", "Singer A", explicit = true),
            track("clean", "Kissik", "Singer A", explicit = false),
        )
        assertEquals(listOf("explicit", "clean"), ids(rows.withoutRepeatedRecordings()))
    }

    @Test
    fun theTopResultStaysAndAPlainRepeatOfItGoes() {
        val top = SearchResult.TopTrack(
            Song(videoId = "top", title = "Kissik", artist = "Singer A", thumbnailUrl = null, durationText = "4:08"),
        )
        val browse = SearchResult.Browse(BrowseItem("MPRE1", "Album", "Singer A", null, BrowseType.ALBUM))
        val rows = listOf(top, browse, track("again", "Kissik", "Singer A"))
        assertEquals(listOf("top", "browse"), ids(rows.withoutRepeatedRecordings()))
    }
}
