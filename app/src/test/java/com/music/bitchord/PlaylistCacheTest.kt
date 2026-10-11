package com.music.bitchord

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.classipod.PlaylistCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session playlist cache, offline: seeding, labels and exact totals
 * must hold without any network, because a reopen that refetches is the
 * bug being fixed and the test has to prove the cache short-circuits it.
 */
class PlaylistCacheTest {

    private fun song(id: String) = Song(
        videoId = id,
        title = "Title $id",
        artist = "Artist",
        thumbnailUrl = null,
    )

    private fun page(
        ids: List<String>,
        continuation: String?,
        headerSubtitle: String? = null,
    ) = YtMusicRepository.SongPage(
        songs = ids.map(::song),
        continuation = continuation,
        header = headerSubtitle?.let {
            InnertubeParser.BrowseHeader(title = "T", subtitle = it, thumbnailUrl = null)
        },
    )

    @Test
    fun `seed populates once and second seed is ignored`() {
        val entry = PlaylistCache.Entry("test:seed-once")
        PlaylistCache.seed(entry, page(listOf("a", "b"), "tok"))
        assertEquals(listOf("a", "b"), entry.songs.map { it.videoId })
        PlaylistCache.seed(entry, page(listOf("c"), null))
        assertEquals(listOf("a", "b"), entry.songs.map { it.videoId })
    }

    @Test
    fun `header count shows while pages remain, exact at the end`() {
        val entry = PlaylistCache.Entry("test:count-${System.nanoTime()}")
        PlaylistCache.seed(entry, page((1..100).map { "s$it" }, "tok", "Playlist · 1,247 songs"))
        assertEquals("1247", PlaylistCache.countLabel(entry))
        // Feed total is a claim, not a fact: badges wait for exhaustion.
        assertNull(PlaylistCache.exactTotal(entry))
    }

    @Test
    fun `growing floor carries plus until exhausted`() {
        val entry = PlaylistCache.Entry("test:floor-${System.nanoTime()}")
        PlaylistCache.seed(entry, page(listOf("a"), "tok"))
        assertEquals("1+", PlaylistCache.countLabel(entry))
        assertNull(PlaylistCache.exactTotal(entry))
    }

    @Test
    fun `exhausted seed without header counts exactly`() {
        val entry = PlaylistCache.Entry("test:exact-${System.nanoTime()}")
        PlaylistCache.seed(entry, page(listOf("a", "b"), null))
        assertEquals("2", PlaylistCache.countLabel(entry))
        assertEquals(2, PlaylistCache.exactTotal(entry))
    }

    @Test
    fun `same browse id returns the same entry across opens`() {
        val id = "test:same-${System.nanoTime()}"
        val first = PlaylistCache.entry(id)
        PlaylistCache.seed(first, page(listOf("a"), null))
        val second = PlaylistCache.entry(id)
        assertTrue(first === second)
        assertEquals(listOf("a"), second.songs.map { it.videoId })
    }
}
