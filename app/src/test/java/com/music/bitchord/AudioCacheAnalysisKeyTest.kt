package com.music.bitchord

import com.music.bitchord.playback.AudioCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the analyser's own bytes live.
 *
 * The rule is the same for every source: the bytes the analyser measures are
 * written to an entry of their own, and the entry the player is streaming into
 * is never written by the analyser.
 *
 * A plain stream — a podcast — broke that rule, because it has no video id
 * and no source to build a sibling from, so the analysis round and the player
 * wrote the same file: the analyser fetched the head of the episode into the
 * entry the player was reading, the entry was thrown away and fetched again,
 * and the stream the listener was hearing was cut in the middle.
 */
class AudioCacheAnalysisKeyTest {

    @Test
    fun aPlainStreamIsMeasuredOutOfAnEntryOfItsOwn() {
        val episode = "https://www.podtrac.com/pts/redirect.mp3/pdst.fm/e/mgln.ai/e/495/" +
            "traffic.megaphone.fm/SBP6565525025.mp3?updated=1750161594"

        val key = AudioCache.plainAnalysisKeyAddress(episode)

        // Not the player's own entry, which is the URL itself.
        assertNotEquals(episode, key)
        assertTrue("the analyser's entry is a sibling: $key", key!!.endsWith("#analysis"))
    }

    @Test
    fun twoPlainStreamsDoNotShareAnEntry() {
        assertNotEquals(
            AudioCache.plainAnalysisKeyAddress("https://example.com/one.mp3"),
            AudioCache.plainAnalysisKeyAddress("https://example.com/two.mp3"),
        )
    }

    @Test
    fun theSameEpisodeAlwaysLandsInTheSameEntry() {
        val episode = "https://example.com/one.mp3?updated=1750161594"

        assertEquals(
            AudioCache.plainAnalysisKeyAddress(episode),
            AudioCache.plainAnalysisKeyAddress(episode),
        )
    }

    @Test
    fun aLocalFileHasNoAnalyserEntry() {
        // It is already whole, on the device, and read in place.
        assertNull(AudioCache.plainAnalysisKeyAddress("file:///music/song.m4a"))
    }

    @Test
    fun aPlatformDocumentHasNoAnalyserEntry() {
        assertNull(AudioCache.plainAnalysisKeyAddress("content://media/external/audio/media/42"))
    }
}
