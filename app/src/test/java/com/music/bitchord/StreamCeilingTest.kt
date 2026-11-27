package com.music.bitchord

import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.StreamRequest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A capped request (the analyser's light read) holds an answer to its ceiling. */
class StreamCeilingTest {

    private val capped = StreamRequest.Capped(192)

    private fun stream(codec: String, kbps: Int?) = SourceStream("https://x", format = StreamFormat(codec = codec, kbps = kbps))

    @Test
    fun aLossyAnswerAtOrUnderTheCapFits() {
        assertTrue(SourceResolver.withinCeiling(stream("opus", 160), capped))
        assertTrue(SourceResolver.withinCeiling(stream("aac", 192), capped))
    }

    @Test
    fun aRateAboveTheCapDoesNot() {
        // What JioSaavn hands back whatever is asked for.
        assertFalse(SourceResolver.withinCeiling(stream("aac", 320), capped))
    }

    @Test
    fun aLosslessAnswerNeverFitsACap() {
        assertFalse(SourceResolver.withinCeiling(stream("flac", null), capped))
    }

    @Test
    fun anUncappedRequestHasNoCeilingToBreak() {
        assertTrue(SourceResolver.withinCeiling(stream("flac", null), StreamRequest.Lossless))
        assertTrue(SourceResolver.withinCeiling(stream("aac", 320), StreamRequest.Best))
    }
}
