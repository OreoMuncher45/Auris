package com.music.bitchord.ui.player

import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The player's ink is judged against the surface actually drawn under its
 * chrome, keeping the record's hue and moving only its lightness. Whatever it
 * settles on has to clear WCAG AA there at the player's own opacity roles:
 * 4.5:1 for the primary and secondary (text) roles, 3:1 for the tertiary
 * (icon) role.
 */
class PlayerInkContrastTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val alphas = NowPlayingAlphas()

    private fun grey(level: Int) = (0xFF shl 24) or (level shl 16) or (level shl 8) or level
    private fun rgb(hex: Int) = (0xFF shl 24) or hex

    private fun assertHolds(choice: InkResolution, surface: InkSurface) {
        val bands = if (choice.dark) surface.dark else surface.light
        val toward = if (choice.dark) white else black
        val ink = choice.fg.toArgb()
        bands.forEach { band ->
            val shown = blendArgb(toward, band, choice.wash)
            assertTrue(
                inkHolds(ink, shown, alphas),
                "ink ${Integer.toHexString(ink)} fails over ${Integer.toHexString(shown)}",
            )
        }
    }

    /** A mostly white sleeve with a red figure — the ticket's cover, roughly. */
    private val whiteSleeveRed: NowPlayingPalette = computeNowPlayingPalette(
        List(820) { rgb(0xF4F2EE) } + List(150) { rgb(0xC62828) } + List(30) { rgb(0x111111) },
    )

    private val purple = intArrayOf(rgb(0x4B2B6E), rgb(0x2D1450), rgb(0x1C0A33))

    // --- The arithmetic -----------------------------------------------------

    @Test
    fun contrastRatioMatchesWcagEndpoints() {
        assertEquals(21f, contrastRatio(1f, 0f), 0.001f)
        assertEquals(1f, contrastRatio(0.3f, 0.3f), 0.001f)
        assertEquals(contrastRatio(0.2f, 0.7f), contrastRatio(0.7f, 0.2f), 0.0001f)
        assertEquals(1f, wcagLuminance(white), 0.0001f)
        assertEquals(0f, wcagLuminance(black), 0.0001f)
    }

    @Test
    fun blendCompositesPerChannelInSrgb() {
        assertEquals(white, blendArgb(white, black, 1f))
        assertEquals(black, blendArgb(white, black, 0f))
        assertEquals(grey(128), blendArgb(white, black, 0.5f))
        assertEquals(grey(102), scrimmed(white, 0.6f))
    }

    @Test
    fun scrimAlphaInterpolatesBetweenStops() {
        assertEquals(0.34f, scrimAlphaAt(0f, FULL_BLUR_SCRIM_POSITIONS, FULL_BLUR_SCRIM_ALPHAS), 0.0001f)
        assertEquals(0.48f, scrimAlphaAt(0.55f, FULL_BLUR_SCRIM_POSITIONS, FULL_BLUR_SCRIM_ALPHAS), 0.0001f)
        assertEquals(0.56f, scrimAlphaAt(0.775f, FULL_BLUR_SCRIM_POSITIONS, FULL_BLUR_SCRIM_ALPHAS), 0.0001f)
        assertEquals(0.64f, scrimAlphaAt(2f, FULL_BLUR_SCRIM_POSITIONS, FULL_BLUR_SCRIM_ALPHAS), 0.0001f)
    }

    @Test
    fun gradedScrimStopEqualsTheLayersItReplaces() {
        // One gradient stop stands in for scrim, then black wash, then white
        // wash. Over any colour it has to give what the three layers would.
        val under = intArrayOf(white, black, grey(90), rgb(0x4B2B6E), rgb(0xD8D4CF))
        val cases = listOf(Triple(0.34f, 0.2f, 0f), Triple(0.06f, 0f, 0.3f), Triple(0.30f, 0.15f, 0.25f))
        for ((scrim, blackWash, whiteWash) in cases) {
            val stop = gradedScrimStop(scrim, blackWash, whiteWash).toArgb()
            val stopAlpha = ((stop ushr 24) and 0xFF) / 255f
            for (c in under) {
                val layered = blendArgb(white, scrimmed(scrimmed(c, scrim), blackWash), whiteWash)
                val single = blendArgb(stop or (0xFF shl 24), c, stopAlpha)
                for (shift in intArrayOf(16, 8, 0)) {
                    val a = (layered shr shift) and 0xFF
                    val b = (single shr shift) and 0xFF
                    assertTrue(abs(a - b) <= 2, "channel $shift: layered $a vs single $b")
                }
            }
        }
    }

    @Test
    fun bandAveragesSplitTopToBottom() {
        val pixels = intArrayOf(white, white, black, black, grey(100), grey(100))
        assertEquals(listOf(white, black, grey(100)), bandAverages(pixels, width = 2, height = 3).toList())
    }

    @Test
    fun theSleeveAloneStillPicksTheSameInkAsBefore() {
        // Until a backdrop is read the player shows the palette's own answer,
        // which is v2's: a bright sleeve gets its accent deepened.
        val accent = whiteSleeveRed.accent
        assertEquals(accent.at(accent.darkL), whiteSleeveRed.fg)
    }

    // --- The cases from the ticket ------------------------------------------

    @Test
    fun theReproWhiteSleeveOverAPurpleBackdropGetsLightRedInk() {
        // Ticket 02: the sleeve is bright, so v2 deepened its red to near
        // black — over the dark purple the player actually drew. Judged
        // against that surface the same red goes light, with no grading.
        assertTrue(wcagLuminance(whiteSleeveRed.fg.toArgb()) < 0.1f, "the old answer was dark")
        val surface = InkSurface(light = purple, dark = purple)
        val choice = resolveInk(whiteSleeveRed.accent, surface, alphas)
        assertFalse(choice.dark)
        assertEquals(0f, choice.wash)
        assertHolds(choice, surface)
        // Still this record's red, not white.
        assertTrue(choice.fg.red - choice.fg.blue > 0.08f, "the ink lost its hue: ${choice.fg}")
    }

    @Test
    fun legacyMeshFallbackBlobsHoldTheInk() {
        // Legacy mesh before (or without) a palette read paints the fallback
        // blobs, led by dark purple.
        val surface = legacyMeshInkSurface(MeshPalette(emptyList()))
        val choice = resolveInk(whiteSleeveRed.accent, surface, alphas)
        assertHolds(choice, surface)
    }

    @Test
    fun pureWhiteCoverOverItsBlurIsGraded() {
        // A white cover's blur under its 34-64 % scrim is light grey at the
        // top and mid grey at the bottom: neither side holds ungraded.
        val surface = fullBlurInkSurface(IntArray(INK_BANDS) { white })
        val choice = resolveInk(NeutralInkAccent, surface, alphas)
        assertTrue(choice.wash > 0f)
        assertHolds(choice, surface)
    }

    @Test
    fun brightSleeveOverAWhiteMeshKeepsItsDarkInk() {
        // Dark ink over a mesh that is white all the way down stays dark and
        // red, with no grade. Under the scrim and the banner's wash the foot
        // of the screen is a light grey, so the red may deepen a step or two
        // to keep the device name at 4.5:1 — never lighten, never change hue.
        val surface = meshInkSurface(intArrayOf(white, white, white))
        val choice = resolveInk(whiteSleeveRed.accent, surface, alphas)
        assertTrue(choice.dark)
        assertEquals(0f, choice.wash)
        assertTrue(wcagLuminance(choice.fg.toArgb()) <= wcagLuminance(whiteSleeveRed.fg.toArgb()))
        assertTrue(choice.fg.red > choice.fg.blue, "the ink lost its hue: ${choice.fg}")
        assertHolds(choice, surface)
    }

    @Test
    fun brightSleeveOverALightGreyMeshKeepsDarkInkWithALightGrade() {
        // A light-grey mesh comes down to mid grey at the foot of the screen
        // under the mesh's scrim and the banner's wash — where the device
        // name sits. Text at the secondary role cannot reach 4.5:1 on mid grey
        // at any lightness, so the ink stays dark and red and the foot of the
        // mesh is lifted a little instead.
        val surface = meshInkSurface(intArrayOf(grey(0xE8), grey(0xDC), grey(0xD0)))
        val choice = resolveInk(whiteSleeveRed.accent, surface, alphas)
        assertTrue(choice.dark)
        assertTrue(choice.wash in 0.01f..0.25f, "grade ${choice.wash}")
        assertTrue(choice.fg.red > choice.fg.blue, "the ink lost its hue: ${choice.fg}")
        assertHolds(choice, surface)
    }

    @Test
    fun darkCoverIsLeftAlone() {
        val navy = computeNowPlayingPalette(List(850) { rgb(0x1B2440) } + List(150) { rgb(0xFF8A3D) })
        val surface = meshInkSurface(intArrayOf(rgb(0x26324F), rgb(0x1A2440), rgb(0x0F1526)))
        val choice = resolveInk(navy.accent, surface, alphas)
        assertFalse(choice.dark)
        assertEquals(0f, choice.wash)
        assertEquals(navy.fg, choice.fg)
    }

    @Test
    fun canvasIsJudgedUnderItsDim() {
        // A white Canvas under the deck's half dim is mid grey.
        val surface = canvasInkSurface(IntArray(INK_BANDS) { white })
        val choice = resolveInk(NeutralInkAccent, surface, alphas)
        assertHolds(choice, surface)
    }

    @Test
    fun someInkAlwaysHoldsWithinTheCap() {
        for (c in intArrayOf(white, rgb(0xFFFF00), rgb(0x00FFFF), grey(0x77), rgb(0xFF8080), black)) {
            val surface = InkSurface(light = intArrayOf(c), dark = intArrayOf(c))
            val choice = resolveInk(whiteSleeveRed.accent, surface, alphas)
            assertTrue(choice.wash <= MAX_INK_WASH)
            assertHolds(choice, surface)
        }
    }

    @Test
    fun theGradeIsTheLeastThatHolds() {
        val surface = fullBlurInkSurface(IntArray(INK_BANDS) { white })
        val choice = resolveInk(NeutralInkAccent, surface, alphas)
        val bands = if (choice.dark) surface.dark else surface.light
        val toward = if (choice.dark) white else black
        val less = (choice.wash - 0.02f).coerceAtLeast(0f)
        val darkInks = generateSequence(NeutralInkAccent.darkL) { it - 0.02f }.takeWhile { it >= 0f }
        val lightInks = generateSequence(NeutralInkAccent.lightL) { it + 0.02f }.takeWhile { it <= 1f }
        val inks = (if (choice.dark) darkInks else lightInks).map { NeutralInkAccent.at(it).toArgb() }
        assertFalse(
            inks.any { ink -> bands.all { inkHolds(ink, blendArgb(toward, it, less), alphas) } },
            "a lighter grade would also have held",
        )
    }

    @Test
    fun lightWinsATie() {
        val surface = InkSurface(light = intArrayOf(grey(0x77)), dark = intArrayOf(grey(0x77)))
        val tie = resolveInk(NeutralInkAccent, InkSurface(intArrayOf(black), intArrayOf(white)), alphas)
        assertFalse(tie.dark, "over black for light and white for dark both are free; light keeps it")
        assertHolds(resolveInk(NeutralInkAccent, surface, alphas), surface)
    }
}
