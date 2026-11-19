package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.SPOTIFY_MISSING_PREFIX
import com.music.bitchord.data.model.SPOTIFY_PENDING_PREFIX
import com.music.bitchord.data.model.SPOTIFY_ROW_PREFIX
import com.music.bitchord.playback.loadAutoplayTracks
import com.music.bitchord.playback.youtubeSeedFor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The YouTube recording that seeds an explicit station or AutoPlay. */
class AutoplaySeedTest {

    private fun song(videoId: String) = Song(
        videoId = videoId,
        title = "Shape of You",
        artist = "Ed Sheeran",
        thumbnailUrl = null,
    )

    @Test
    fun `a Spotify row is resolved before it seeds the radio`() = runBlocking {
        val rows = listOf(
            "${SPOTIFY_ROW_PREFIX}4uLU6hMCjMI75M1A2tKUQC",
            "${SPOTIFY_PENDING_PREFIX}4uLU6hMCjMI75M1A2tKUQC",
            "${SPOTIFY_MISSING_PREFIX}4uLU6hMCjMI75M1A2tKUQC",
        )

        rows.forEach { rowId ->
            var resolvedRow: Song? = null
            val seed = youtubeSeedFor(song(rowId)) { row ->
                resolvedRow = row
                "dQw4w9WgXcQ"
            }

            assertEquals("dQw4w9WgXcQ", seed)
            assertEquals(rowId, resolvedRow?.videoId)
        }
    }

    @Test
    fun `a source-backed row is resolved before it seeds the radio`() = runBlocking {
        val seed = youtubeSeedFor(song("src:cfg-1::track-42")) { "dQw4w9WgXcQ" }
        assertEquals("dQw4w9WgXcQ", seed)
    }

    @Test
    fun `a YouTube row is its own radio seed`() = runBlocking {
        var resolved = false
        val seed = youtubeSeedFor(song("dQw4w9WgXcQ")) {
            resolved = true
            "oHg5SJYRHAq3mOCvDBVIgw"
        }

        assertEquals("dQw4w9WgXcQ", seed)
        assertFalse(resolved)
    }

    @Test
    fun `the radio receives the resolved YouTube id, not the Spotify row id`() = runBlocking {
        val seedSong = song("${SPOTIFY_ROW_PREFIX}4uLU6hMCjMI75M1A2tKUQC")
        val related = song("related-youtube-id").copy(title = "A Different Song")
        var radioSeed: String? = null

        val result = loadAutoplayTracks(
            existing = listOf(seedSong),
            seedSong = seedSong,
            limit = 5,
            radio = { videoId ->
                radioSeed = videoId
                Result.success(listOf(related))
            },
            resolve = { "dQw4w9WgXcQ" },
        )

        assertTrue(result.isSuccess)
        assertEquals("dQw4w9WgXcQ", radioSeed)
        assertEquals(1, result.getOrNull()?.size)
    }

    @Test
    fun `the radio is not called when the row has no YouTube recording`() = runBlocking {
        val seedSong = song("${SPOTIFY_ROW_PREFIX}unmatched")
        var radioCalled = false

        val result = loadAutoplayTracks(
            existing = listOf(seedSong),
            seedSong = seedSong,
            limit = 5,
            radio = {
                radioCalled = true
                Result.success(emptyList())
            },
            resolve = { null },
        )

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull().orEmpty().isEmpty())
        assertFalse(radioCalled)
    }
}
