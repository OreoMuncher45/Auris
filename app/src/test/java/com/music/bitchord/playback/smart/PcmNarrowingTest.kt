package com.music.bitchord.playback.smart

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmNarrowingTest {

    @Test
    fun anOutputFormatWithoutAnEncodingIs16Bit() {
        // Never the input track's encoding: that is only what was asked for.
        assertEquals(PcmNarrowing.PCM_16BIT, PcmNarrowing.outputEncodingOf(null))
        assertEquals(PcmNarrowing.PCM_24BIT_PACKED, PcmNarrowing.outputEncodingOf(PcmNarrowing.PCM_24BIT_PACKED))
        assertEquals(PcmNarrowing.PCM_FLOAT, PcmNarrowing.outputEncodingOf(PcmNarrowing.PCM_FLOAT))
    }

    @Test
    fun onlyKnownEncodingsAreAccepted() {
        for (encoding in listOf(2, 3, 4, 21, 22)) assertTrue(PcmNarrowing.supports(encoding))
        // ENCODING_PCM_24BIT_PACKED's neighbours and compressed formats are refused.
        for (encoding in listOf(0, 1, 5, 13, 20, 23)) assertFalse(PcmNarrowing.supports(encoding))
    }

    @Test
    fun floatIsScaledAndClamped() {
        val input = floats(0f, 0.5f, -0.5f, 1f, -1f, 2f, -3f)
        assertEquals(listOf(0, 16383, -16383, 32767, -32767, 32767, -32767), narrow(input, PcmNarrowing.PCM_FLOAT))
    }

    @Test
    fun twentyFourBitKeepsItsTopSixteen() {
        val samples = intArrayOf(0, 0x123456, -0x123456, 0x7fffff, -0x800000, 0x0000ff)
        val input = ByteArray(samples.size * 3)
        samples.forEachIndexed { index, value ->
            input[index * 3] = value.toByte()
            input[index * 3 + 1] = (value shr 8).toByte()
            input[index * 3 + 2] = (value shr 16).toByte()
        }
        assertEquals(samples.map { it shr 8 }, narrow(input, PcmNarrowing.PCM_24BIT_PACKED))
    }

    @Test
    fun thirtyTwoBitKeepsItsTopSixteen() {
        val samples = intArrayOf(0, 0x12345678, -0x12345678, Int.MAX_VALUE, Int.MIN_VALUE)
        val input = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            .apply { samples.forEach { putInt(it) } }.array()
        assertEquals(samples.map { it shr 16 }, narrow(input, PcmNarrowing.PCM_32BIT))
    }

    @Test
    fun eightBitIsUnsignedAroundTheMiddle() {
        val input = byteArrayOf(128.toByte(), 255.toByte(), 0, 64)
        assertEquals(listOf(0, 127 shl 8, -128 shl 8, -64 shl 8), narrow(input, PcmNarrowing.PCM_8BIT))
    }

    @Test
    fun theFrameCountComesFromTheSampleWidth() {
        // The bug this guards: 24-bit read as 16-bit gives 1.5x the samples.
        assertEquals(3, PcmNarrowing.widthOf(PcmNarrowing.PCM_24BIT_PACKED))
        assertEquals(4, PcmNarrowing.widthOf(PcmNarrowing.PCM_FLOAT))
        assertEquals(4, PcmNarrowing.widthOf(PcmNarrowing.PCM_32BIT))
        assertEquals(1, PcmNarrowing.widthOf(PcmNarrowing.PCM_8BIT))
        assertEquals(2, PcmNarrowing.widthOf(PcmNarrowing.PCM_16BIT))
    }

    private fun floats(vararg values: Float): ByteArray =
        ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            .apply { values.forEach { putFloat(it) } }.array()

    private fun narrow(input: ByteArray, encoding: Int): List<Int> {
        val count = input.size / PcmNarrowing.widthOf(encoding)
        val out = ByteArray(count * 2)
        PcmNarrowing.narrow(input, count, encoding, out)
        val shorts = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return List(count) { shorts.get(it).toInt() }
    }
}
