package com.music.bitchord.data.innertube

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The same cases as DesktopParityTest's continuation tests, here so they run
 * on any host: the desktop module only builds on Linux and Windows.
 */
class ContinuationTokenTest {
    @Test
    fun `a token wrapped in a commandExecutorCommand is found`() {
        val json = Json.parseToJsonElement(
            """
            {"musicPlaylistShelfRenderer": {"contents": [{"continuationItemRenderer": {
              "continuationEndpoint": {"commandExecutorCommand": {"commands": [
                {"playlistVotingRefreshPopupCommand": {}},
                {"continuationCommand": {"token": "TOKEN_IN_EXECUTOR"}}
              ]}}
            }}]}}
            """.trimIndent(),
        )
        assertEquals("TOKEN_IN_EXECUTOR", InnertubeParser.continuationToken(json))
    }

    @Test
    fun `a later shelf's renderer is not followed`() {
        val json = Json.parseToJsonElement(
            """
            {"contents": [
              {"continuationItemRenderer": {"button": {}}},
              {"continuationItemRenderer": {"continuationEndpoint": {
                "continuationCommand": {"token": "OTHER_SHELF"}}}}
            ],
            "continuations": [{"nextContinuationData": {"continuation": "PAGE_MORE"}}]}
            """.trimIndent(),
        )
        assertEquals("PAGE_MORE", InnertubeParser.continuationToken(json))
    }

    @Test
    fun `the direct command on the first renderer still works`() {
        val json = Json.parseToJsonElement(
            """{"contents": [{"continuationItemRenderer": {"continuationEndpoint": {
              "continuationCommand": {"token": "DIRECT"}}}}]}""",
        )
        assertEquals("DIRECT", InnertubeParser.continuationToken(json))
    }
}
