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

    @Test
    fun `a later page read only for its suggestions still fails the read`() {
        // Track rows and an episode (so the playlist rows aren't recognised), plus a Suggestions shelf.
        val unreadable = appended(
            row("bbbbbbbbbbb", "SET2"),
            """{ "musicMultiRowListItemRenderer": { "title": { "runs": [ { "text": "Episode" } ] } } }""",
            """{ "musicShelfRenderer": { "title": { "runs": [ { "text": "Suggestions" } ] }, "contents": [ ${row("sssssssssss", "SUGG")} ] } }""",
        )
        assertFailsWith<IllegalStateException> {
            runBlocking {
                collectPlaylistEntries(appended(row("aaaaaaaaaaa", "SET1"), token("P2")), maxPages = 10) { unreadable }
            }
        }
    }

    @Test
    fun `a recognised empty last page ends the list normally`() = runBlocking {
        val lastPage = Json.parseToJsonElement(
            """{ "continuationContents": { "musicPlaylistShelfContinuation": { "contents": [] } } }""",
        )
        val entries = collectPlaylistEntries(appended(row("aaaaaaaaaaa", "SET1"), token("P2")), maxPages = 10) { lastPage }
        assertEquals(listOf("SET1"), entries.map { it.setVideoId })
    }

    private fun suggestionsShelf() =
        """{ "musicShelfRenderer": { "title": { "runs": [ { "text": "Suggestions" } ] }, "contents": [ ${row("sssssssssss", "SUGG")} ] } }"""

    @Test
    fun `an owned playlist's chain ending on a Suggestions-only continuation reads whole`() = runBlocking {
        val suggestionsOnly = Json.parseToJsonElement(
            """{ "continuationContents": { "sectionListContinuation": { "contents": [ ${suggestionsShelf()} ] } } }""",
        )
        val entries = collectPlaylistEntries(appended(row("aaaaaaaaaaa", "SET1"), token("P2")), maxPages = 10) { suggestionsOnly }
        assertEquals(listOf("SET1"), entries.map { it.setVideoId })
    }

    @Test
    fun `an appended later page holding only a Suggestions shelf fails the read`() {
        val onlyShelf = appended(suggestionsShelf())
        assertFailsWith<IllegalStateException> {
            runBlocking {
                collectPlaylistEntries(appended(row("aaaaaaaaaaa", "SET1"), token("P2")), maxPages = 10) { onlyShelf }
            }
        }
    }
}
