package com.music.bitchord.ui.player

import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A Spotify Canvas plays under the dark-theme player: its frames go bright,
 * blue and dark in turn, and the deck must not re-theme with them.
 *
 * Each frame runs through the live palette's own read ([computeLivePalette])
 * and the player's own judging ([inkOutcome]), as a playing Canvas does
 * several times a second.
 */
class CanvasInkStabilityTest {

    private fun rgb(hex: Int) = (0xFF shl 24) or hex

    private val w = 24
    private val h = 48

    /** A frame of two colours, [top] above [split] of its height and [bottom] under it, with a speckle of [fleck]. */
    private fun frame(top: Int, bottom: Int, fleck: Int, split: Float = 0.5f): IntArray =
        IntArray(w * h) { i ->
            val row = i / w
            when {
                i % 7 == 0 -> fleck
                row < h * split -> top
                else -> bottom
            }
        }

    /** A pale, washed-out frame: warm greys and white haze, the shape of the "light" report. */
    private val paleFrame = frame(rgb(0xC9BBA8), rgb(0xE6E1DA), rgb(0x5A3C28))

    /** Snow and ice: white and pale blue with dark rock, the shape of the "blue" report. */
    private val snowFrame = frame(rgb(0xDCE8F2), rgb(0xEEF4FA), rgb(0x3A5A7A))

    /** Fire in the dark: deep browns with orange, the shape of the frame that read right. */
    private val darkFrame = frame(rgb(0x2A1810), rgb(0x1A0E08), rgb(0xD06A20))

    /** A warm cover for the record itself, the sleeve the player already holds. */
    private val sleeve = computeNowPlayingPalette(
        List(600) { rgb(0x3A2418) } + List(300) { rgb(0x8A4A22) } + List(100) { rgb(0xE0A060) },
    )

    private fun outcomeFor(pixels: IntArray, darkTheme: Boolean = true): InkOutcome {
        val live = computeLivePalette(pixels, w, h, LiveColorSource.CANVAS)
        return inkOutcome(
            InkInputs(
                live = live,
                sleeve = sleeve,
                main = null,
                panel = null,
                surface = InkSurfaceKind.MAIN,
                portraitClip = false,
                darkTheme = darkTheme,
            ),
            fromClip = true,
        )
    }

    private fun hue(argb: Int) = Oklch.fromArgb(argb).hue
    private fun hueGap(a: Float, b: Float) = abs(a - b).let { min(it, 360f - it) }

    private val frames = listOf("pale" to paleFrame, "snow" to snowFrame, "dark" to darkFrame)

    @Test
    fun darkThemeCanvasNeverTakesDarkInkOrAWhiteWash() {
        frames.forEach { (name, pixels) ->
            val out = outcomeFor(pixels)
            println("$name: fg=${Integer.toHexString(out.fg.toArgb())} black=${out.canvasBlack} white=${out.canvasWhite} header=${Integer.toHexString(out.headerFg.toArgb())} grade=${out.headerGrade}")
            assertTrue(out.fg.isLightInk(), "$name: dark theme took dark ink ${Integer.toHexString(out.fg.toArgb())}")
            assertTrue(out.headerFg.isLightInk(), "$name: header took dark ink")
            assertTrue(out.canvasWhite == 0f, "$name: a white wash ${out.canvasWhite} over the deck in dark theme")
        }
    }

    @Test
    fun darkThemeCanvasInkKeepsTheRecordsHueAcrossFrames() {
        val hues = frames.map { (name, pixels) -> name to hue(outcomeFor(pixels).fg.toArgb()) }
        println("ink hues: $hues")
        val first = hues.first().second
        hues.forEach { (name, h) ->
            assertTrue(hueGap(first, h) < 12f, "$name: the ink's hue moved from $first to $h")
        }
    }

    @Test
    fun darkThemeCanvasInkDoesNotFlipOverALoopOfFrames() {
        // light -> blue -> correct -> light ..., as the report loops.
        val outs = List(9) { outcomeFor(frames[it % frames.size].second) }
        assertFalse(outs.any { !it.fg.isLightInk() }, "the ink flipped to dark during the loop")
        assertTrue(outs.all { it.canvasWhite == 0f }, "a white wash appeared during the loop")
    }
}
