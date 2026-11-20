package com.music.bitchord

import com.music.bitchord.data.innertube.InnertubeParser
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which token "Remove from history" spends. A History row's menu carries
 * several feedback actions side by side, and sending the wrong one would
 * quietly do something else to the account — so only the item wearing the
 * DELETE icon counts, whatever order the menu lists them in.
 */
class HistoryRemovalTokenTest {

    private fun row(menuItems: String): String = """
        {"contents": [{"musicResponsiveListItemRenderer": {
          "playlistItemData": {"videoId": "abc123"},
          "flexColumns": [
            {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "A Song"}]}}},
            {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "An Artist"}]}}}
          ],
          "menu": {"menuRenderer": {"items": [$menuItems]}}
        }}]}
    """.trimIndent()

    private fun feedbackItem(icon: String, token: String) = """
        {"menuServiceItemRenderer": {
          "icon": {"iconType": "$icon"},
          "serviceEndpoint": {"feedbackEndpoint": {"feedbackToken": "$token"}}
        }}
    """.trimIndent()

    private fun tokenOf(json: String): String? =
        InnertubeParser.collectSongsDeep(Json.parseToJsonElement(json)).single().feedbackToken

    @Test
    fun `the DELETE item's token is the one taken`() {
        val json = row(
            feedbackItem("NOT_INTERESTED", "dont-recommend") + "," + feedbackItem("DELETE", "remove-me"),
        )
        assertEquals("remove-me", tokenOf(json))
    }

    @Test
    fun `a row with no DELETE item has no token`() {
        assertNull(tokenOf(row(feedbackItem("NOT_INTERESTED", "dont-recommend"))))
    }

    @Test
    fun `a row with no menu has no token`() {
        assertNull(tokenOf(row("")))
    }
}
