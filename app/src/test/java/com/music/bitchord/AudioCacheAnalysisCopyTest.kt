package com.music.bitchord

import com.music.bitchord.playback.AudioCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which copy the analyser reads for a row.
 *
 * A row with no YouTube id behind it — a Spotify catalogue row — is served by
 * whichever source holds it, and the sources are asked for it *by name*. The
 * name is in the row's own address, so the address is the copy the analyser
 * has to read.
 *
 * It used to be rebuilt from the id alone, under the marker that asks for
 * YouTube's own Opus encoding — a request for a video called `spotify:<id>`,
 * which YouTube refuses. The analyser got nothing for every such row, the
 * track was never measured, and its lane in the panel was blank.
 */
class AudioCacheAnalysisCopyTest {

    @Test
    fun aYouTubeRowIsReadFromYouTubesOwnOpusCopy() {
        val copy = AudioCache.analysisCopyAddress(
            uri = "bitchord://watch?v=abc123&n=Some+Song&a=Some+Artist",
            videoId = "abc123",
            light = false,
        )

        // The marker is what keeps the analyser's bytes out of the player's
        // own entry: a separate file, a separate encoding, the same music.
        assertTrue(copy.contains("automix_opus=1"))
        assertTrue(copy.contains("v=abc123"))
    }

    @Test
    fun aCatalogueRowIsReadThroughItsOwnAddress() {
        val row = "bitchord://watch?v=spotify:2vd2ouW2pbzXWUbzORtxOi" +
            "&n=Guillotines+for+Billionaires&a=Matt+Elliott&d=184"

        val copy = AudioCache.analysisCopyAddress(row, "spotify:2vd2ouW2pbzXWUbzORtxOi", rendition = null, light = true)

        // The row's own address, whole (the id, and the name the sources are
        // asked to match on), marked as the analyser's read so the sources
        // answer it with a light copy rather than the listener's master.
        assertEquals("$row&automix_read=1&automix_rendition=", copy)
        assertTrue(copy.startsWith(row))
        assertTrue(copy.contains("n=Guillotines+for+Billionaires"))
        assertTrue(copy.contains("a=Matt+Elliott"))
        // And no YouTube-Opus marker: there is no YouTube copy of this row to
        // ask for, and asking is what it refused.
        assertFalse(copy.contains("automix_opus"))
    }

    @Test
    fun aRecordingOnlyASourceHoldsIsReadThroughItsOwnAddressMarkedLight() {
        // No YouTube match to measure instead: the source's own audio is the
        // copy, keyed by the source and its track, and asked for light.
        val row = "bitchord://source?s=addon1&t=track9&n=Some+Song&a=Some+Artist&d=200"

        val copy = AudioCache.analysisCopyAddress(row, "addon1|track9", rendition = null, light = true)

        assertEquals("$row&automix_read=1&automix_rendition=", copy)
        assertFalse(copy.contains("automix_opus"))
    }
}
