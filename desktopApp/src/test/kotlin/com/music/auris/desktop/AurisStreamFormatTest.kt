package com.music.auris.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The quality badge and the stats line are both read off [AurisStreamFormat], and every label
 * they draw has to mean the same thing it means on Android.
 */
class AurisStreamFormatTest {

    @Test
    fun `ffmpeg codec names are recognised as lossless`() {
        assertTrue(AurisStreamFormat(codec = "flac").isLossless)
        assertTrue(AurisStreamFormat(codec = "alac").isLossless)
        assertTrue(AurisStreamFormat(codec = "wavpack").isLossless)
        // FFmpeg names every uncompressed flavour pcm_<layout>.
        assertTrue(AurisStreamFormat(codec = "pcm_s16le").isLossless)
        assertFalse(AurisStreamFormat(codec = "aac").isLossless)
        assertFalse(AurisStreamFormat(codec = "opus").isLossless)
    }

    @Test
    fun `hi-res is past CD, not merely lossless`() {
        val cd = AurisStreamFormat("flac", sampleRateHz = 44_100, bitDepth = 16, channels = 2)
        val hiRes = AurisStreamFormat("flac", sampleRateHz = 96_000, bitDepth = 24, channels = 2)
        assertTrue(cd.isLossless)
        assertFalse(cd.isHiRes)
        assertTrue(hiRes.isHiRes)
    }

    @Test
    fun `high quality is decided on the rate, not the source`() {
        // YouTube's Opus tops out around 160 and does not qualify; a 320kbps AAC does, wherever it
        // came from.
        assertFalse(AurisStreamFormat("opus", kbps = 160).isHiQuality)
        assertTrue(AurisStreamFormat("aac", kbps = 320).isHiQuality)
        assertTrue(AurisStreamFormat("mp3", kbps = 256).isHiQuality)
        // Lossless is never *also* called high quality: the badge picks one.
        assertFalse(AurisStreamFormat("flac", kbps = 1_411).isHiQuality)
    }

    @Test
    fun `dolby atmos is distinct from lossless`() {
        val atmos = AurisStreamFormat(codec = "eac3", kbps = 768)
        assertTrue(atmos.isDolbyAtmos)
        assertFalse(atmos.isLossless)
    }

    @Test
    fun `a lossless stream states what its samples decode to`() {
        // 16-bit 44.1kHz stereo is 1411kbps, the figure Tidal and Apple Music print — not whatever
        // the FLAC container happened to compress to.
        assertEquals(
            "FLAC · 16-bit · 44.1 kHz · 1411 kbps · Stereo",
            AurisStreamFormat("flac", kbps = 900, sampleRateHz = 44_100, bitDepth = 16, channels = 2).summary,
        )
    }

    @Test
    fun `a lossy stream states its own rate under its usual codec name`() {
        assertEquals(
            "AAC · 44.1 kHz · 302 kbps · Stereo",
            AurisStreamFormat("aac", kbps = 302, sampleRateHz = 44_100, channels = 2).summary,
        )
        // YouTube describes every audio rendition as audio/mp4 whatever is inside it, which is why
        // the claimed format is never what the player shows.
        assertEquals("AAC · 320 kbps", AurisStreamFormat("audio/mp4; codecs=\"mp4a.40.2\"", kbps = 320).summary)
    }

    @Test
    fun `nothing known is stated rather than guessed at`() {
        assertEquals("Unknown format", AurisStreamFormat().summary)
        assertEquals("Opus · 160 kbps", AurisStreamFormat("opus", kbps = 160).summary)
    }
}
