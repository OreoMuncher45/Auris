package com.music.bitchord.data.artwork

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkCompressorTest {

    @Test
    fun aCoverAtTheSizeWantedIsDecodedWhole() {
        assertEquals(1, ArtworkCompressor.sampleSizeFor(1400, 1400, 1400))
        assertEquals(1, ArtworkCompressor.sampleSizeFor(1000, 1000, 1000))
    }

    @Test
    fun aCoverSlightlyLargerIsStillDecodedWhole() {
        // Just over the size wanted: decoded whole and then scaled down, so
        // the copy kept is the size wanted and not a blurry enlargement.
        assertEquals(1, ArtworkCompressor.sampleSizeFor(1200, 1200, 1000))
    }

    @Test
    fun aCoverManyTimesLargerIsSampledDownFirst() {
        // A host that ignores the size hint would otherwise cost a bitmap of
        // its own size before the copy is scaled down.
        assertEquals(2, ArtworkCompressor.sampleSizeFor(4000, 4000, 1400))
        assertEquals(8, ArtworkCompressor.sampleSizeFor(8000, 8000, 1000))
    }

    @Test
    fun aPortraitCoverIsSampledByItsLongestSide() {
        assertEquals(2, ArtworkCompressor.sampleSizeFor(1000, 4000, 1400))
    }

    @Test
    fun aCoverWithNoSizeDecodesWhole() {
        assertEquals(1, ArtworkCompressor.sampleSizeFor(0, 0, 1000))
        assertEquals(1, ArtworkCompressor.sampleSizeFor(1200, 1200, 0))
    }
}
