package com.music.bitchord.data.innertube

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A playlist's second page as YouTube Music sends it now: rows appended by an
 * `onResponseReceivedActions` action rather than under `continuationContents`.
 * Shape trimmed from a live response for a 200-song playlist (#947).
 */
class PlaylistShelfContinuationTest {

    private fun row(videoId: String, setVideoId: String) = """
        { "musicResponsiveListItemRenderer": {
          "playlistItemData": { "videoId": "$videoId", "playlistSetVideoId": "$setVideoId" },
          "flexColumns": [
            { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [ { "text": "Song $videoId" } ] } } },
            { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [ { "text": "Artist" } ] } } }
          ]
        } }"""

    private val appendedPage = Json.parseToJsonElement(
        """
        { "onResponseReceivedActions": [ { "appendContinuationItemsAction": { "continuationItems": [
          ${row("aaaaaaaaaaa", "SET1")},
          ${row("bbbbbbbbbbb", "SET2")},
          { "continuationItemRenderer": { "continuationEndpoint": { "continuationCommand": { "token": "PAGE3" } } } }
        ] } } ] }
        """,
    )

    @Test
    fun `an appended continuation page reads as the playlist's next rows`() {
        val page = assertNotNull(InnertubeParser.parsePlaylistShelf(appendedPage))
        assertEquals(listOf("SET1", "SET2"), page.songs.map { it.setVideoId })
        assertEquals("PAGE3", page.continuation)
    }

    private val repeatedSongPage = Json.parseToJsonElement(
        """
        { "onResponseReceivedActions": [ { "appendContinuationItemsAction": { "continuationItems": [
          ${row("aaaaaaaaaaa", "SET1")},
          ${row("aaaaaaaaaaa", "SET2")}
        ] } } ] }
        """,
    )

    @Test
    fun `a song added twice is two entries when read for a reorder`() {
        val entries = assertNotNull(InnertubeParser.parsePlaylistShelf(repeatedSongPage, byEntry = true))
        assertEquals(listOf("SET1", "SET2"), entries.songs.map { it.setVideoId })
        // The open page still lists the recording once.
        val shown = assertNotNull(InnertubeParser.parsePlaylistShelf(repeatedSongPage))
        assertEquals(listOf("SET1"), shown.songs.map { it.setVideoId })
    }

    private val appendedWithSuggestions = Json.parseToJsonElement(
        """
        { "onResponseReceivedActions": [ { "appendContinuationItemsAction": { "continuationItems": [
          ${row("aaaaaaaaaaa", "SET1")},
          { "musicShelfRenderer": { "title": { "runs": [ { "text": "Suggestions" } ] }, "contents": [
            ${row("sssssssssss", "SUGG")}
          ] } },
          { "continuationItemRenderer": { "continuationEndpoint": { "continuationCommand": { "token": "PAGE3" } } } }
        ] } } ] }
        """,
    )

    @Test
    fun `a suggestions shelf appended with the rows stays out of the playlist`() {
        val page = assertNotNull(InnertubeParser.parsePlaylistShelf(appendedWithSuggestions))
        assertEquals(listOf("aaaaaaaaaaa"), page.songs.map { it.videoId })
        assertEquals(listOf("sssssssssss"), page.suggested.map { it.videoId })
        assertEquals("PAGE3", page.continuation)
    }

    private val appendedEpisodes = Json.parseToJsonElement(
        """
        { "onResponseReceivedActions": [ { "appendContinuationItemsAction": { "continuationItems": [
          { "musicMultiRowListItemRenderer": { "title": { "runs": [ { "text": "Episode" } ] } } },
          { "continuationItemRenderer": { "continuationEndpoint": { "continuationCommand": { "token": "PAGE3" } } } }
        ] } } ] }
        """,
    )

    @Test
    fun `an appended page of episodes is left to the general reading`() {
        assertNull(InnertubeParser.parsePlaylistShelf(appendedEpisodes))
    }
}
