package com.music.bitchord.playback.audio

import android.media.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The match is deliberately exact. A bit-perfect mixer at another rate, another
 * channel count or another encoding is still a conversion at the seam, so the
 * only useful answer is the one that names precisely what the sink writes.
 */
class BitPerfectMixerTest {

    private fun candidate(
        index: Int,
        encoding: Int,
        rate: Int = 48_000,
        channels: Int = 2,
        bitPerfect: Boolean = true,
    ) = BitPerfectMixer.Candidate(
        index = index,
        encoding = encoding,
        sampleRateHz = rate,
        channelCount = channels,
        bitPerfect = bitPerfect,
    )

    @Test
    fun `picks the candidate matching the sink's encoding and rate`() {
        val candidates = listOf(
            candidate(index = 0, encoding = AudioFormat.ENCODING_PCM_16BIT),
            candidate(index = 1, encoding = AudioFormat.ENCODING_PCM_FLOAT),
        )

        assertEquals(
            1,
            BitPerfectMixer.select(
                candidates,
                sampleRateHz = 48_000,
                channelCount = 2,
                sinkEncoding = AudioFormat.ENCODING_PCM_FLOAT,
            )?.index,
        )
        assertEquals(
            0,
            BitPerfectMixer.select(
                candidates,
                sampleRateHz = 48_000,
                channelCount = 2,
                sinkEncoding = AudioFormat.ENCODING_PCM_16BIT,
            )?.index,
        )
    }

    @Test
    fun `refuses a mixer format the sink cannot write`() {
        val candidates = listOf(
            candidate(index = 0, encoding = AudioFormat.ENCODING_PCM_24BIT_PACKED),
            candidate(index = 1, encoding = AudioFormat.ENCODING_PCM_FLOAT),
        )

        assertNull(
            BitPerfectMixer.select(
                candidates,
                sampleRateHz = 96_000,
                channelCount = 2,
                sinkEncoding = AudioFormat.ENCODING_PCM_16BIT,
            ),
        )
    }

    @Test
    fun `refuses another sample rate`() {
        assertNull(
            BitPerfectMixer.select(
                listOf(candidate(index = 0, encoding = AudioFormat.ENCODING_PCM_FLOAT, rate = 48_000)),
                sampleRateHz = 96_000,
                channelCount = 2,
                sinkEncoding = AudioFormat.ENCODING_PCM_FLOAT,
            ),
        )
    }

    @Test
    fun `refuses another channel count`() {
        assertNull(
            BitPerfectMixer.select(
                listOf(candidate(index = 0, encoding = AudioFormat.ENCODING_PCM_FLOAT, channels = 2)),
                sampleRateHz = 48_000,
                channelCount = 1,
                sinkEncoding = AudioFormat.ENCODING_PCM_FLOAT,
            ),
        )
    }

    @Test
    fun `refuses a mixer the framework is not offering as bit perfect`() {
        assertNull(
            BitPerfectMixer.select(
                listOf(
                    candidate(
                        index = 0,
                        encoding = AudioFormat.ENCODING_PCM_FLOAT,
                        bitPerfect = false,
                    ),
                ),
                sampleRateHz = 48_000,
                channelCount = 2,
                sinkEncoding = AudioFormat.ENCODING_PCM_FLOAT,
            ),
        )
    }

    @Test
    fun `no candidates is no request`() {
        assertNull(
            BitPerfectMixer.select(
                emptyList(),
                sampleRateHz = 48_000,
                channelCount = 2,
                sinkEncoding = AudioFormat.ENCODING_PCM_FLOAT,
            ),
        )
    }
}
