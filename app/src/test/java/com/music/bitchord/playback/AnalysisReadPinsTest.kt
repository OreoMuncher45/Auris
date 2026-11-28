package com.music.bitchord.playback

import com.music.bitchord.data.sources.SourceStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Every open in an analysis round reads one resolved stream. */
class AnalysisReadPinsTest {

    private val row = "addon1|track9"

    @After fun cleanUp() {
        AnalysisReadPins.forget(row)
    }

    @Test
    fun theRoundsLaterOpensReuseTheFirstOnesStream() {
        var resolves = 0
        val first = AnalysisReadPins.streamFor(row) { resolves++; SourceStream("https://a/opus") }
        // A later open would have been answered with another encoding.
        val tail = AnalysisReadPins.streamFor(row) { resolves++; SourceStream("https://b/aac") }

        assertSame(first, tail)
        assertEquals(1, resolves)
    }

    @Test
    fun theNextRoundResolvesAfresh() {
        AnalysisReadPins.streamFor(row) { SourceStream("https://a/opus") }
        AnalysisReadPins.forget(row)
        val next = AnalysisReadPins.streamFor(row) { SourceStream("https://b/opus") }
        assertEquals("https://b/opus", next?.url)
    }

    @Test
    fun aResolveThatAnsweredNothingIsNotPinned() {
        AnalysisReadPins.streamFor(row) { null }
        assertEquals("https://c/opus", AnalysisReadPins.streamFor(row) { SourceStream("https://c/opus") }?.url)
    }

    @Test
    fun aFallbackStreamIsPinnedForTheRoundLikeACappedOne() {
        var cappedAsks = 0
        var fallbackAsks = 0
        // Nothing at the capped rate: the player's own copy serves the read.
        val first = AnalysisReadPins.streamFor(
            row,
            capped = { cappedAsks++; null },
            fallback = { fallbackAsks++; SourceStream("https://player/flac") },
        )
        // The head, the grown head and the tail: each a later open in the round,
        // where a fresh resolve could have answered with another stream.
        repeat(3) {
            val later = AnalysisReadPins.streamFor(
                row,
                capped = { cappedAsks++; SourceStream("https://other/aac") },
                fallback = { fallbackAsks++; SourceStream("https://other/flac") },
            )
            assertSame(first, later)
        }
        assertEquals("https://player/flac", first?.url)
        assertEquals(1, cappedAsks)
        assertEquals(1, fallbackAsks)
    }

    @Test
    fun aCappedAnswerNeverAsksTheFallback() {
        var fallbackAsks = 0
        val stream = AnalysisReadPins.streamFor(
            row,
            capped = { SourceStream("https://a/opus") },
            fallback = { fallbackAsks++; SourceStream("https://player/flac") },
        )
        assertEquals("https://a/opus", stream?.url)
        assertEquals(0, fallbackAsks)
    }
}
