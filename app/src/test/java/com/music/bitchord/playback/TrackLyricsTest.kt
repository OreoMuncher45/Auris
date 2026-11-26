package com.music.bitchord.playback

import com.music.bitchord.data.lyrics.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackLyricsTest {
    private val a = listOf(LyricLine(0, "first song"))
    private val b = listOf(LyricLine(0, "second song"))

    @Test
    fun aNewTracksLookupDropsThePreviousTracksLinesAtOnce() {
        val lyrics = TrackLyrics()
        lyrics.lookUp("A")
        lyrics.publish("A", a)
        // Track change: while B's lookup runs, A's lines are not shown at B's position.
        assertTrue(lyrics.lookUp("B"))
        assertNull(lyrics.lines)
        assertTrue(lyrics.publish("B", b))
        assertEquals(b, lyrics.lines)
    }

    @Test
    fun aRepeatedLookupForTheSameTrackKeepsItsLines() {
        val lyrics = TrackLyrics()
        lyrics.lookUp("A")
        lyrics.publish("A", a)
        assertFalse(lyrics.lookUp("A"))
        assertEquals(a, lyrics.lines)
    }

    @Test
    fun aLookupThatAnswersAfterTheTrackChangedPublishesNothing() {
        val lyrics = TrackLyrics()
        lyrics.lookUp("A")
        lyrics.lookUp("B")
        assertFalse(lyrics.publish("A", a))
        assertNull(lyrics.lines)
    }

    @Test
    fun clearingDropsTheLinesAndTheOwner() {
        val lyrics = TrackLyrics()
        lyrics.lookUp("A")
        lyrics.publish("A", a)
        lyrics.clear()
        assertNull(lyrics.lines)
        assertFalse(lyrics.publish("A", a))
        assertFalse(lyrics.lookUp("A"))
    }
}
