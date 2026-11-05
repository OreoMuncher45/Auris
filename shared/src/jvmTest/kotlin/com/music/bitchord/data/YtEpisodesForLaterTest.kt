package com.music.bitchord.data

import com.music.bitchord.data.model.ShelfItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * YouTube Music's "Episodes for later" is not ours.
 *
 * Auris takes its podcasts from Apple's catalogue, so YouTube's own list of
 * saved podcast episodes is always empty here — and it is filed under the
 * same name as the shelf Auris keeps for itself, one card away from it. The
 * card is dropped wherever a YouTube feed can hand one over.
 */
class YtEpisodesForLaterTest {

    private fun card(title: String, browseId: String?) =
        ShelfItem(title = title, subtitle = "", thumbnailUrl = null, videoId = null, browseId = browseId)

    @Test
    fun `the card YouTube files under SE is gone`() {
        val kept = YtMusicRepository.withoutYoutubeEpisodesForLater(
            listOf(card("Episodes for later", "VLSEabcdef"), card("My mix", "VLPL1234")),
        )
        assertEquals(listOf("My mix"), kept.map { it.title })
    }

    @Test
    fun `the card is gone by name as well`() {
        // The id is what YouTube calls the auto-playlist today; the title is
        // what it was called when the id was something else.
        val kept = YtMusicRepository.withoutYoutubeEpisodesForLater(
            listOf(card("Episodes for later", "VLPL1234"), card("Episodes For Later", null)),
        )
        assertTrue(kept.isEmpty())
    }

    @Test
    fun `the listener's own playlists are left alone`() {
        val items = listOf(
            card("Liked Music", "VLLM"),
            card("Road trip", "VLPL1234"),
            card("Episodes", "VLPL5678"),
        )
        assertEquals(items, YtMusicRepository.withoutYoutubeEpisodesForLater(items))
    }
}
