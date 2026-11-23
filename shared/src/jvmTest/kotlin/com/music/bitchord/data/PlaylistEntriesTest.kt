package com.music.bitchord.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlaylistEntriesTest {

    private fun row(videoId: String, setVideoId: String) = """
        { "musicResponsiveListItemRenderer": {
          "playlistItemData": { "videoId": "$videoId", "playlistSetVideoId": "$setVideoId" },
          "flexColumns": [
            { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [ { "text": "Song $videoId" } ] } } },
            { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [ { "text": "Artist" } ] } } }
          ]
        } }"""

    private fun token(next: String) =
        """{ "continuationItemRenderer": { "continuationEndpoint": { "continuationCommand": { "token": "$next" } } } }"""

    private fun appended(vararg items: String): JsonElement = Json.parseToJsonElement(
        """{ "onResponseReceivedActions": [ { "appendContinuationItemsAction": { "continuationItems": [ ${items.joinToString(",")} ] } } ] }""",
    )

    @Test
    fun `every page's entries are read in order`() = runBlocking {
        val pages = mapOf("P2" to appended(row("bbbbbbbbbbb", "SET2")))
        val entries = collectPlaylistEntries(appended(row("aaaaaaaaaaa", "SET1"), token("P2")), maxPages = 10) {
            pages.getValue(it)
        }
        assertEquals(listOf("SET1", "SET2"), entries.map { it.setVideoId })
    }

    @Test
    fun `a later page that cannot be read fails rather than cutting the list short`() {
        // An episode among the rows: the page is not read as the playlist's rows.
        val unreadable = appended(
            row("bbbbbbbbbbb", "SET2"),
            """{ "musicMultiRowListItemRenderer": { "title": { "runs": [ { "text": "Episode" } ] } } }""",
        )
        assertFailsWith<IllegalStateException> {
            runBlocking {
                collectPlaylistEntries(appended(row("aaaaaaaaaaa", "SET1"), token("P2")), maxPages = 10) { unreadable }
            }
        }
    }

    @Test
    fun `a first page that is not a playlist reads as no entries`() = runBlocking {
        val notAPlaylist = Json.parseToJsonElement("""{ "contents": {} }""")
        assertEquals(emptyList(), collectPlaylistEntries(notAPlaylist, maxPages = 10) { error("no next page") })
    }
}
