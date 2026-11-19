package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The duration every surface scales by when the player has nothing to say.
 *
 * An addon stream that arrives without a parsable length leaves the timeline's
 * window at `C.TIME_UNSET`; before this fallback existed, the scrubber scaled
 * taps by zero and the transition watcher returned before it planned anything.
 * Both symptoms were reported as one: "turn YouTube off and seeking dies and
 * AutoMix becomes the old one."
 */
class PlaybackDurationFallbackTest {

    @Test
    fun `a measured duration always wins`() {
        assertEquals(200_000L, playbackDurationMs(200_000L, 225_000L))
    }

    @Test
    fun `the queue row stands in when the player has none`() {
        assertEquals(225_000L, playbackDurationMs(0L, 225_000L))
    }

    @Test
    fun `TIME_UNSET is not mistaken for a runtime`() {
        // C.TIME_UNSET is Long.MIN_VALUE + 1; a `!= 0` test would have scaled
        // every tap by a negative number instead of falling back.
        assertEquals(225_000L, playbackDurationMs(Long.MIN_VALUE + 1, 225_000L))
    }

    @Test
    fun `nothing known is zero rather than negative`() {
        assertEquals(0L, playbackDurationMs(0L, 0L))
        assertEquals(0L, playbackDurationMs(Long.MIN_VALUE + 1, -5L))
    }
}
