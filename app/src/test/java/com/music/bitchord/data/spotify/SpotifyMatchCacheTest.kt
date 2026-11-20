package com.music.bitchord.data.spotify

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyMatchCacheTest {

    private fun song(id: String) = Song(videoId = id, title = id, artist = "a", thumbnailUrl = null)

    @Test
    fun `answers a lookup made under the same setup`() {
        val cache = SpotifyMatchCache(10)
        cache.put("sp1", "youtube on", song("yt1"))
        assertEquals("yt1", cache.get("sp1", "youtube on")?.videoId)
    }

    @Test
    fun `a changed source setup is a miss, not a stale match`() {
        val cache = SpotifyMatchCache(10)
        cache.put("sp1", "youtube on", song("yt1"))
        assertNull(cache.get("sp1", "youtube off"))
    }

    @Test
    fun `least recently used goes first`() {
        val cache = SpotifyMatchCache(2)
        cache.put("a", 0, song("1"))
        cache.put("b", 0, song("2"))
        cache.get("a", 0)
        cache.put("c", 0, song("3"))
        assertNull(cache.get("b", 0))
        assertEquals("1", cache.get("a", 0)?.videoId)
        assertEquals(2, cache.size)
    }

    @Test
    fun `a track with no id is never remembered`() {
        val cache = SpotifyMatchCache(10)
        cache.put("", 0, song("1"))
        assertEquals(0, cache.size)
    }
}
