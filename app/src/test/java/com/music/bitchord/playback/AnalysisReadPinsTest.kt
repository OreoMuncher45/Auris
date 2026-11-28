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

    @Test
    fun aLightReadNothingAnsweredFailsInsteadOfBeingServedUnpinned() {
        // Neither the capped resolve nor the player's copy answered the head
        // probe: the read fails here rather than falling through to a resolve
        // whose stream is not pinned for the round.
        try {
            AnalysisReadPins.lightRead(row, capped = { null }, fallback = { null })
            org.junit.Assert.fail("a light read with no copy was served")
        } catch (expected: java.io.IOException) {
            // The round ends short.
        }
        // Nothing pinned, so no later open of this round can append a stream
        // that a later capped resolve happened to find.
        org.junit.Assert.assertNull(AnalysisReadPins.get(row))
    }

    @Test
    fun aNewAnalysisRoundResolvesItsLightCopyAfresh() {
        // The production round start, not the helper: the first round's pin is
        // dropped there, so the second round resolves a new stream.
        AnalysisReadPins.streamFor(row) { SourceStream("https://first/expired") }
        AudioCache.startAnalysisRound(row)
        val second = AnalysisReadPins.streamFor(row) { SourceStream("https://second/fresh") }
        assertEquals("https://second/fresh", second?.url)
    }

    @Test
    fun aYouTubeRoundLeavesNoLightPinToDrop() {
        // A YouTube id is not a catalogue row: nothing it reads goes through the pins.
        AnalysisReadPins.put("abcdefghijk", SourceStream("https://kept"))
        AudioCache.startAnalysisRound("abcdefghijk")
        assertEquals("https://kept", AnalysisReadPins.get("abcdefghijk")?.url)
        AnalysisReadPins.forget("abcdefghijk")
    }
}
