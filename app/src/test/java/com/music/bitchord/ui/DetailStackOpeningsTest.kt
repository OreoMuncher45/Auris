package com.music.bitchord.ui

import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.UiState
import org.junit.Assert.assertEquals
import org.junit.Test

class DetailStackOpeningsTest {
    private fun page(id: String, opening: Long) =
        DetailPage(browseId = id, title = id, subtitle = "", thumbnailUrl = null, songs = UiState.Loading, opening = opening)

    @Test
    fun `an answer for an earlier opening does not reach the reopened page`() {
        // Closed and opened again: only opening 2 is on the stack.
        val stack = listOf(page("mix", opening = 2))
        val after = stack.updateOpening("mix", opening = 1) { it.copy(title = "stale") }
        assertEquals("mix", after.single().title)
    }

    @Test
    fun `only the matching opening is written when two copies are stacked`() {
        val stack = listOf(page("mix", 1), page("other", 2), page("mix", 3))
        val after = stack.updateOpening("mix", opening = 3) { it.copy(title = "fresh") }
        assertEquals(listOf("mix", "other", "fresh"), after.map { it.title })
    }

    @Test
    fun `membership is read from the latest opening of a playlist`() {
        val withA = page("pl", 1).copy(songs = UiState.Success(listOf(song("A"))))
        val withoutA = page("pl", 3).copy(songs = UiState.Success(emptyList()))
        // A was removed elsewhere; the playlist opened again without it.
        val stack = listOf(withA, page("other", 2), withoutA)
        val rows = (stack.latestOpening("pl")?.songs as UiState.Success).data
        assertEquals(emptyList<String>(), rows.map { it.videoId })
    }

    private fun song(id: String) = com.music.bitchord.data.model.Song(videoId = id, title = id, artist = "", thumbnailUrl = null)
}
