package com.music.bitchord.download

import com.music.bitchord.data.YtMusicRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which collections a pass walks once "Download liked songs" is on — see [collectionsToSync]. */
class KeepLikedDownloadedTest {

    private val playlist = SavedCollection(id = "PL1", title = "A playlist", playlist = true)

    @Test
    fun `off, only the playlists whose switch is on are walked`() {
        val walked = collectionsToSync(listOf(playlist), mapOf(playlist.id to playlist), keepLiked = false, likedTitle = "Liked songs")
        assertEquals(listOf(playlist), walked)
    }

    @Test
    fun `on with no record, an empty Liked Music stands in for the first fill`() {
        val walked = collectionsToSync(listOf(playlist), mapOf(playlist.id to playlist), keepLiked = true, likedTitle = "Liked songs")
        assertEquals(2, walked.size)
        val liked = walked.last()
        assertEquals(YtMusicRepository.LIKED_MUSIC, liked.id)
        assertEquals("Liked songs", liked.title)
        assertTrue(liked.playlist)
        assertTrue("nothing asked for yet, so every liked song is missing", liked.videoIds.isEmpty())
    }

    @Test
    fun `on with a record, that record is walked once, whatever its own switch said`() {
        val liked = SavedCollection(
            id = "LM",
            title = "Liked Music",
            playlist = true,
            videoIds = listOf("a", "b"),
            autoDownload = false,
        )
        val all = mapOf(playlist.id to playlist, liked.id to liked)
        val walked = collectionsToSync(listOf(playlist), all, keepLiked = true, likedTitle = "Liked songs")
        assertEquals(listOf(playlist, liked), walked)
    }

    @Test
    fun `a Liked Music already kept by its page switch is not walked twice`() {
        val liked = SavedCollection(id = YtMusicRepository.LIKED_MUSIC, title = "Liked Music", playlist = true)
        val all = mapOf(liked.id to liked)
        val walked = collectionsToSync(listOf(liked), all, keepLiked = true, likedTitle = "Liked songs")
        assertEquals(listOf(liked), walked)
    }
}
