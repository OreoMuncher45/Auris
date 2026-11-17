package com.music.bitchord.ui.screens

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Flow's quick-access tile draws a bundled still of the card's clip. It is
 * decoded on every home open, so it has to stay at tile size: 48dp at the
 * densest phone density (xxhdpi, 3x), which is 144px. A copy at the clip's own
 * 360px would decode 6x the pixels for the same 48dp square.
 *
 * The size is read from the WebP header, not by decoding the image, so the test
 * needs no native Skia on the machine that runs it.
 */
class FlowTileCoverTest {

    private val cover = File("src/commonMain/composeResources/drawable/flow_cover.webp")

    @Test
    fun theStillIsTileSized() {
        assertEquals(144 to 144, webpSize(cover.readBytes()))
    }

    @Test
    fun theStillStaysSmall() {
        assertTrue(cover.length() < 8 * 1024, "flow_cover.webp is ${cover.length()} bytes")
    }

    private fun webpSize(b: ByteArray): Pair<Int, Int> {
        fun u8(i: Int) = b[i].toInt() and 0xFF
        fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        fun u24(i: Int) = u16(i) or (u8(i + 2) shl 16)
        assertEquals("RIFF", String(b, 0, 4, Charsets.US_ASCII))
        assertEquals("WEBP", String(b, 8, 4, Charsets.US_ASCII))
        return when (val chunk = String(b, 12, 4, Charsets.US_ASCII)) {
            // Lossy: a key frame's start code, then 14-bit width and height.
            "VP8 " -> (u16(26) and 0x3FFF) to (u16(28) and 0x3FFF)
            // Lossless: 14-bit width-1 and height-1 packed after the signature.
            "VP8L" -> {
                val bits = u8(21) or (u8(22) shl 8) or (u8(23) shl 16) or (u8(24) shl 24)
                ((bits and 0x3FFF) + 1) to (((bits ushr 14) and 0x3FFF) + 1)
            }
            // Extended: 24-bit canvas width-1 and height-1.
            "VP8X" -> (u24(24) + 1) to (u24(27) + 1)
            else -> error("unknown WebP chunk $chunk")
        }
    }
}
