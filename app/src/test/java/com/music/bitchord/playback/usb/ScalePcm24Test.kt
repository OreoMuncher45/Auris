package com.music.bitchord.playback.usb

import org.junit.Assert.assertEquals
import org.junit.Test

/** The software gain on 24-bit PCM, for the USB output's fallback when the DAC has no volume. */
class ScalePcm24Test {

    private val scale = Class.forName("com.music.bitchord.playback.usb.ExclusiveUsbOutput\$Companion")
        .getDeclaredMethod("scalePcmBytes", ByteArray::class.java, Int::class.javaPrimitiveType, Float::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        .apply { isAccessible = true }
    private val companion = ExclusiveUsbOutput::class.java.getDeclaredField("Companion")
        .apply { isAccessible = true }
        .get(null)

    private fun bytesOf(vararg samples: Int) = ByteArray(samples.size * 3).also { b ->
        samples.forEachIndexed { n, s ->
            b[n * 3] = (s and 0xFF).toByte()
            b[n * 3 + 1] = ((s shr 8) and 0xFF).toByte()
            b[n * 3 + 2] = ((s shr 16) and 0xFF).toByte()
        }
    }

    private fun samplesOf(b: ByteArray) = (b.indices step 3).map { i ->
        val s = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or ((b[i + 2].toInt() and 0xFF) shl 16)
        if (s and 0x800000 != 0) s - 0x1000000 else s
    }

    private fun scaled(gain: Float, vararg samples: Int): List<Int> {
        val b = bytesOf(*samples)
        scale.invoke(companion, b, 0x15, gain, b.size)
        return samplesOf(b)
    }

    @Test
    fun halvesNegativeSamplesAsWellAsPositiveOnes() {
        assertEquals(
            listOf(-2_097_152, 2_097_152, -1, 0, -4_194_304, 4_194_303),
            scaled(0.5f, -4_194_304, 4_194_304, -1, 0, -0x800000, 0x7FFFFE),
        )
    }

    @Test
    fun aQuietNegativeSampleStaysQuiet() {
        assertEquals(listOf(-50), scaled(0.5f, -100))
    }
}
