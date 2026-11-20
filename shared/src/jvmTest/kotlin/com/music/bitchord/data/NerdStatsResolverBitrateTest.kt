package com.music.bitchord.data

import com.music.bitchord.data.sources.StreamFormat
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The resolver's bitrate never describes a stream other than the one handed over. */
class NerdStatsResolverBitrateTest {
    private val id = "abcdefghijk"

    @AfterTest
    fun reset() = NerdStats.forgetLastSession()

    @Test
    fun `a declared lossy stream without a bitrate does not borrow YouTube's pick`() {
        NerdStats.onStreamPicked(id, 160)
        NerdStats.onSourceStream(id, StreamFormat(codec = "aac"))
        assertNull(NerdStats.resolverBitrateKbps(id))
    }

    @Test
    fun `a declared lossy bitrate is used and a lossless claim never is`() {
        NerdStats.onStreamPicked(id, 160)
        NerdStats.onSourceStream(id, StreamFormat(codec = "aac", kbps = 256))
        assertEquals(256, NerdStats.resolverBitrateKbps(id))
        NerdStats.onSourceStream(id, StreamFormat(codec = "flac", kbps = 1411))
        assertNull(NerdStats.resolverBitrateKbps(id))
    }

    @Test
    fun `a plain YouTube resolve keeps its pick`() {
        NerdStats.onStreamPicked(id, 160)
        assertEquals(160, NerdStats.resolverBitrateKbps(id))
        NerdStats.onSourceStream(id, StreamFormat())
        assertEquals(160, NerdStats.resolverBitrateKbps(id))
    }
}
