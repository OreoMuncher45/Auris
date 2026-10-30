package com.music.bitchord.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * The player's automatic ink now follows the sleeve's secondary colour, with
 * lightness chosen by how bright the cover is. These are the cases that made
 * the previous engines land on grey or on the wrong colour.
 */
class NowPlayingPaletteTest {
    private data class Cover(val name: String, val pixels: List<Int>)

    private fun cover(name: String, vararg shares: Pair<Int, Int>): Cover =
        Cover(name, shares.flatMap { (color, count) -> List(count) { color } })

    private fun argb(value: Long) = value.toInt()

    private fun fg(name: String) = computeNowPlayingPalette(
        covers.first { it.name == name }.pixels,
    ).fg

    private val covers = listOf(
        cover(
            "blue sleeve with red second colour",
            argb(0xFF1E3A8A) to 850,
            argb(0xFFE53935) to 150,
        ),
        cover(
            "blue sleeve with a rare red speck",
            argb(0xFF1E3A8A) to 980,
            argb(0xFFE53935) to 20,
        ),
        cover("pure red", argb(0xFFE53935) to 1000),
        cover("bright yellow", argb(0xFFFFEB3B) to 1000),
        cover("all white", argb(0xFFFFFFFF) to 1000),
        cover("all black", argb(0xFF000000) to 1000),
    )

    @Test
    fun secondaryColourCarriesTheInk() {
        val ink = fg("blue sleeve with red second colour")
        println("secondary ink = $ink")
        // The cover is mostly blue; a primary-colour ink would be blue. The
        // secondary red has to be the one tinting the controls.
        assertTrue("expected red-tinted ink, got $ink", ink.red > ink.blue)
        assertTrue("the red tint should be visible, not a hint: $ink", ink.red - ink.blue > 0.12f)
    }

    @Test
    fun rareColourDoesNotCarryTheInk() {
        val ink = fg("blue sleeve with a rare red speck")
        // Two percent is too little of the cover to speak for it, so the
        // single real colour remains the source.
        assertTrue("a 2% speck should not tint the ink red: $ink", ink.blue > ink.red)
    }

    @Test
    fun singleColourSleeveReflectsItsOwnHue() {
        val red = fg("pure red")
        assertTrue("red should tint the ink red", red.red > red.green && red.red > red.blue)
        assertTrue("a dark red sleeve needs light ink", luminance(red) >= 0.5f)

        val yellow = fg("bright yellow")
        assertTrue(
            "yellow should tint the ink yellow",
            yellow.red > yellow.blue && yellow.green > yellow.blue,
        )
        assertTrue("a bright yellow sleeve needs dark ink", luminance(yellow) < 0.5f)
    }

    @Test
    fun neutralSleevesChooseLegibleNeutralInk() {
        val white = fg("all white")
        val black = fg("all black")

        // Neutral in, neutral out: no hue is invented for a grey sleeve.
        assertEquals(white.red, white.green, 0.01f)
        assertEquals(white.green, white.blue, 0.01f)
        assertEquals(black.red, black.green, 0.01f)
        assertEquals(black.green, black.blue, 0.01f)

        // But the lightness still follows the sleeve, so both stay legible.
        assertTrue("white sleeve should take dark ink", luminance(white) < 0.5f)
        assertTrue("black sleeve should take light ink", luminance(black) >= 0.5f)
    }

    private fun luminance(color: Color): Float {
        val argb = color.toArgb()
        fun linear(shift: Int): Float {
            val channel = ((argb shr shift) and 0xFF) / 255f
            return if (channel <= 0.04045f) channel / 12.92f
            else ((channel + 0.055f) / 1.055f).pow(2.4f)
        }
        return 0.2126f * linear(16) + 0.7152f * linear(8) + 0.0722f * linear(0)
    }
}
