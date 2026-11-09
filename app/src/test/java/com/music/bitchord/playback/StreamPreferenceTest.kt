package com.music.bitchord.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The file is authoritative.
 *
 * This policy has been inverted three times, and the third inversion is the
 * one these tests now pin against. It was: a downloaded track streams whenever
 * a network is up and falls back to its file when one is not. Nothing else in
 * the app agreed with it — the disk cache, read-ahead, the stats panel and the
 * download badge all treat a queued downloaded track as the file it names —
 * so the one component that disagreed, [Song.toMediaItem], was quietly opening
 * a `bitchord://` stream for a track that was sitting on disk, on a resolve
 * nothing had warmed. That is the "playing from Downloads keeps loading"
 * report.
 *
 * The one thing that still outranks a file is a source that can serve a better
 * copy, and that is not a question this file can answer: it costs a search, so
 * the file starts playing and the second look swaps a lossless copy in over
 * it. See [QualityUpgrade.couldStillUpgrade].
 */
class StreamPreferenceTest {

    @Test
    fun `a file on the device is what plays, network or not`() {
        assertTrue(preferLocalFile(hasLocalFile = true))
    }

    @Test
    fun `nothing saved means nothing local to play`() {
        assertFalse(preferLocalFile(hasLocalFile = false))
    }

    @Test
    fun `a stream that failed can still be handed the file`() {
        // The fallback rebuilds the item after a stream has already failed. It
        // must not be possible for that rebuild to come back as a stream again.
        assertTrue(preferLocalFile(hasLocalFile = true))
    }
}
