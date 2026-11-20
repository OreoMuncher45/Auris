package com.music.bitchord.data.innertube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [InnertubeParser.parsePlaylistContinuation] reads a playlist's later pages
 * from their own item list. It must give the same rows and token the general
 * walk gave, and step aside for any page it doesn't recognise.
 */
class PlaylistContinuationTest {

    private fun row(id: String, title: String, artist: String) = """
        {"musicResponsiveListItemRenderer": {
          "playlistItemData": {"videoId": "$id", "playlistSetVideoId": "set-$id"},
          "flexColumns": [
            {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "$title"}]}}},
            {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "$artist"}]}}}
          ],
          "fixedColumns": [
            {"musicResponsiveListItemFixedColumnRenderer": {"text": {"runs": [{"text": "3:21"}]}}}
          ]
        }}
    """

    private val next = """
        {"continuationItemRenderer": {"continuationEndpoint":
          {"continuationCommand": {"token": "NEXT", "request": "CONTINUATION_REQUEST_TYPE_BROWSE"}}}}
    """

    private fun page(vararg items: String): JsonObject = Json.parseToJsonElement(
        """
        {"responseContext": {}, "onResponseReceivedActions": [
          {"appendContinuationItemsAction": {"continuationItems": [${items.joinToString(",")}]}}
        ]}
        """,
    ) as JsonObject

    @Test
    fun `reads rows and the next token from the item list`() {
        val root = page(row("a", "One", "X"), row("b", "Two", "Y"), next)
        val parsed = InnertubeParser.parsePlaylistContinuation(root)!!
        assertEquals(listOf("a", "b"), parsed.songs.map { it.videoId })
        assertEquals("NEXT", parsed.continuation)
        assertEquals(listOf("set-a", "set-b"), parsed.songs.map { it.setVideoId })
        assertEquals(listOf("3:21", "3:21"), parsed.songs.map { it.durationText })
    }

    @Test
    fun `matches the general walk on the same page`() {
        val root = page(row("a", "One", "X"), row("b", "Two", "Y"), row("a", "One again", "X"), next)
        val parsed = InnertubeParser.parsePlaylistContinuation(root)!!
        assertEquals(InnertubeParser.collectSongsDeep(root), parsed.songs)
        assertEquals(InnertubeParser.continuationToken(root), parsed.continuation)
    }

    @Test
    fun `the last page has no token`() {
        val parsed = InnertubeParser.parsePlaylistContinuation(page(row("a", "One", "X")))!!
        assertEquals(listOf("a"), parsed.songs.map { it.videoId })
        assertNull(parsed.continuation)
    }

    @Test
    fun `steps aside for anything that is not a track row`() {
        val shelf = """{"musicShelfRenderer": {"title": {"runs": [{"text": "Suggestions"}]}, "contents": []}}"""
        assertNull(InnertubeParser.parsePlaylistContinuation(page(row("a", "One", "X"), shelf)))
        val firstPage = Json.parseToJsonElement("""{"contents": {}}""")
        assertNull(InnertubeParser.parsePlaylistContinuation(firstPage))
    }
}
