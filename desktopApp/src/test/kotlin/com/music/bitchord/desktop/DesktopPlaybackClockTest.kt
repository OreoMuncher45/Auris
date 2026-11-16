package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The window reads the engine's state through [withoutClock], and recomposes only when that
 * changes. A playhead tick must not count as a change; anything else must.
 */
class DesktopPlaybackClockTest {

    private val song = Song(videoId = "a", title = "A", artist = "Artist", thumbnailUrl = null)
    private val playing = DesktopPlaybackState(
        song = song,
        isPlaying = true,
        positionMs = 1_000L,
        positionSampledAtNanos = 10L,
        durationMs = 200_000L,
    )

    @Test
    fun aTickIsNotAChange() {
        val ticked = playing.copy(positionMs = 1_250L, positionSampledAtNanos = 20L)
        assertEquals(playing.withoutClock(), ticked.withoutClock())
    }

    @Test
    fun everythingElseIs() {
        assertNotEquals(playing.withoutClock(), playing.copy(isPlaying = false).withoutClock())
        assertNotEquals(playing.withoutClock(), playing.copy(song = song.copy(videoId = "b")).withoutClock())
        assertNotEquals(playing.withoutClock(), playing.copy(durationMs = 1L).withoutClock())
        // A seek is a deliberate jump, not a tick: it still reaches the window.
        assertNotEquals(playing.withoutClock(), playing.copy(seeks = 1).withoutClock())
    }

    @Test
    fun theClockIsAllThatIsDropped() {
        val stripped = playing.withoutClock()
        assertEquals(0L, stripped.positionMs)
        assertEquals(0L, stripped.positionSampledAtNanos)
        assertEquals(playing.copy(positionMs = 0L, positionSampledAtNanos = 0L), stripped)
    }
}
