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

    @Test
    fun `a continuation with an episode beside its Suggestions shelf fails the read`() {
        val episodeAndSuggestions = Json.parseToJsonElement(
            """{ "continuationContents": { "sectionListContinuation": { "contents": [
                { "musicMultiRowListItemRenderer": { "title": { "runs": [ { "text": "Episode" } ] } } },
                ${suggestionsShelf()}
            ] } } }""",
        )
        assertFailsWith<IllegalStateException> {
            runBlocking {
                collectPlaylistEntries(appended(row("aaaaaaaaaaa", "SET1"), token("P2")), maxPages = 10) { episodeAndSuggestions }
            }
        }
    }

    private fun untitledRow(videoId: String, setVideoId: String) = """
        { "musicResponsiveListItemRenderer": {
          "playlistItemData": { "videoId": "$videoId", "playlistSetVideoId": "$setVideoId" },
          "flexColumns": [ { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [ { "text": "" } ] } } } ]
        } }"""

    @Test
    fun `a later page with a row that cannot be read fails the read`() {
        val withUnreadableRow = appended(row("bbbbbbbbbbb", "SET2"), untitledRow("ccccccccccc", "SET3"))
        assertFailsWith<IllegalStateException> {
            runBlocking {
                collectPlaylistEntries(appended(row("aaaaaaaaaaa", "SET1"), token("P2")), maxPages = 10) { withUnreadableRow }
            }
        }
    }

    @Test
    fun `a first page with a row that cannot be read fails the read`() {
        val first = appended(row("aaaaaaaaaaa", "SET1"), untitledRow("ccccccccccc", "SET3"))
        assertFailsWith<IllegalStateException> {
            runBlocking { collectPlaylistEntries(first, maxPages = 10) { error("no next page") } }
        }
    }
    // ---- Whole listings: queue all, download sync, "already in this playlist?" ----

    private val episode = """{ "musicMultiRowListItemRenderer": { "title": { "runs": [ { "text": "Episode" } ] } } }"""

    private fun suggestions(vararg rows: String) =
        """{ "musicShelfRenderer": { "title": { "runs": [ { "text": "Suggestions" } ] }, "contents": [ ${rows.joinToString(",")} ] } }"""

    @Test
    fun `a whole listing reads a mixed page's own tracks, leaves its suggestions out, and carries on`() = runBlocking {
        val pages = mapOf(
            "P2" to appended(
                row("bbbbbbbbbbb", "SET2"),
                episode,
                suggestions(row("sssssssssss", "SUGG")),
                token("P3"),
            ),
            "P3" to appended(row("ccccccccccc", "SET3")),
        )
        val songs = collectSongListing(appended(row("aaaaaaaaaaa", "SET1"), token("P2")), maxPages = 10, whole = true) {
            pages.getValue(it)
        }
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"), songs.map { it.videoId })
    }

    @Test
    fun `a whole listing fails when a later page fails to load, and a display listing keeps what it read`() = runBlocking {
        val first = appended(row("aaaaaaaaaaa", "SET1"), token("P2"))
        val failing: suspend (String) -> JsonElement = { throw java.io.IOException("offline") }
        assertFailsWith<java.io.IOException> { collectSongListing(first, maxPages = 10, whole = true, next = failing) }
        assertEquals(listOf("aaaaaaaaaaa"), collectSongListing(first, maxPages = 10, whole = false, next = failing).map { it.videoId })
    }

    @Test
    fun `a whole listing longer than the page cap fails, and a display listing stops at the cap`() = runBlocking {
        var n = 0
        val endless: suspend (String) -> JsonElement = { n++; appended(row("v${n.toString().padStart(10, '0')}", "SET$n"), token("P$n")) }
        val first = appended(row("aaaaaaaaaaa", "SET0"), token("P0"))
        assertFailsWith<IllegalStateException> { collectSongListing(first, maxPages = 3, whole = true, next = endless) }
        n = 0
        assertEquals(3, collectSongListing(first, maxPages = 3, whole = false, next = endless).size)
    }

    @Test
    fun `a whole listing fails on a later page whose own rows were not found`() {
        val onlySuggestions = appended(suggestions(row("sssssssssss", "SUGG")))
        assertFailsWith<IllegalStateException> {
            runBlocking {
                collectSongListing(appended(row("aaaaaaaaaaa", "SET1"), token("P2")), maxPages = 10, whole = true) { onlySuggestions }
            }
        }
    }

    @Test
    fun `a whole listing fails on an unrecognised later page, and a display listing keeps its earlier rows`() = runBlocking {
        val first = appended(row("aaaaaaaaaaa", "SET1"), token("P2"))
        val unrecognised = Json.parseToJsonElement("""{ "contents": {} }""")
        assertFailsWith<IllegalStateException> { collectSongListing(first, maxPages = 10, whole = true) { unrecognised } }
        assertEquals(listOf("aaaaaaaaaaa"), collectSongListing(first, maxPages = 10, whole = false) { unrecognised }.map { it.videoId })
    }

    @Test
    fun `a whole listing still reads a page the general way, and still ends on a recognised empty last page`() = runBlocking {
        // An album-like first page: no playlist shelf, rows found by the general walk.
        val album = Json.parseToJsonElement("""{ "contents": { "rows": [ ${row("aaaaaaaaaaa", "SET1")} ] } }""")
        assertEquals(listOf("aaaaaaaaaaa"), collectSongListing(album, maxPages = 10, whole = true) { error("no next page") }.map { it.videoId })
        val lastPage = Json.parseToJsonElement(
            """{ "continuationContents": { "musicPlaylistShelfContinuation": { "contents": [] } } }""",
        )
        val songs = collectSongListing(appended(row("bbbbbbbbbbb", "SET2"), token("P2")), maxPages = 10, whole = true) { lastPage }
        assertEquals(listOf("bbbbbbbbbbb"), songs.map { it.videoId })
    }
}
