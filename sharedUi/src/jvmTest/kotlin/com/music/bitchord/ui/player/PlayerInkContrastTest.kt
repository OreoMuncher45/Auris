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
        val overlay = if (choice.dark) surface.darkOverlay else 0f
        bands.forEach { band ->
            // The grade, then anything the renderer lays over it.
            val shown = scrimmed(blendArgb(toward, band, choice.wash), overlay)
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
    fun lightInkKeepsTheArtworkHueInsteadOfClimbingToWhite() {
        // A blue-violet sleeve. The uncapped ladder let the light candidates
        // climb to OKLCH L=1, where gamut mapping has no chroma left to give:
        // the transport came out white even with "Use artwork" on.
        val accent = InkAccent(hue = 265f, chroma = 0.16f, lightL = 0.80f, darkL = 0.25f)
        // Mid-grey forces the light candidate to move instead of letting the
        // test pass at index zero, where even an uncapped ladder looks fixed.
        val surface = InkSurface(light = intArrayOf(rgb(0x484848)), dark = intArrayOf(rgb(0x484848)))
        val choice = resolveInk(accent, surface, alphas)
        val ink = choice.fg.toArgb()
        val r = (ink shr 16) and 0xFF
        val g = (ink shr 8) and 0xFF
        val b = ink and 0xFF
        val spread = maxOf(r, g, b) - minOf(r, g, b)
        assertTrue(
            spread > 24,
            "the sleeve's hue should survive in the ink, got ${Integer.toHexString(ink)}",
        )
        assertFalse(
            r > 235 && g > 235 && b > 235,
            "near-white ink is the bug, not a tint: ${Integer.toHexString(ink)}",
        )
        // The cap must not cost the contrast the resolver exists for.
        assertHolds(choice, surface)
    }


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

    @Test
    fun aSurfaceIsRepublishedOnlyWhenItReallyMoves() {
        // A live blur lands up to thirty times a second; only a move the eye
        // could see re-judges the ink.
        fun surface(level: Int) = fullBlurInkSurface(BandBounds.uniform(IntArray(INK_BANDS) { grey(level) }))
        assertTrue(inkSurfaceMoved(null, surface(100)), "the first surface is always published")
        assertFalse(inkSurfaceMoved(surface(100), surface(102)), "a drift of a couple of levels is not")
        assertTrue(inkSurfaceMoved(surface(100), surface(120)), "a real change is")
    }

    @Test
    fun aBrightPatchCannotHideInItsStripsAverage() {
        // A dark strip with a broad white patch at one side: its average is
        // a dark grey light ink holds on, but the patch is what sits behind
        // one of the controls. The strip's lightest cell must decide.
        val width = 60
        val height = 30
        val pixels = IntArray(width * height) { i -> if (i % width < 12) white else grey(0x10) }
        val average = bandAverages(pixels, width, height)
        val bounds = bandBounds(pixels, width, height)
        assertTrue(wcagLuminance(bounds.lightest[0]) > 0.9f, "the patch survives in the bounds")
        assertTrue(wcagLuminance(average[0]) < 0.25f, "and is lost in the average")
        val surface = meshInkSurface(bounds)
        val choice = resolveInk(NeutralInkAccent, surface, alphas)
        assertHolds(choice, surface)
        // And over the patch itself, as drawn.
        val patch = scrimmed(white, MESH_SCRIM_TOP + (MESH_SCRIM_BOTTOM - MESH_SCRIM_TOP) * 0.40f)
        if (!choice.dark) {
            assertTrue(inkHolds(choice.fg.toArgb(), blendArgb(black, patch, choice.wash), alphas))
        }
    }

    @Test
    fun aThinStripeAcrossAnyBoundaryStillDecides() {
        // A four-pixel white stripe on a dark ground, placed across x = 10 —
        // where a six-cell split of a 60-pixel strip would cut it in two and
        // dilute it on both sides. The chosen ink has to hold over the stripe
        // itself after the mesh's scrim, not over a cell's average.
        val width = 60
        val height = 30
        val pixels = IntArray(width * height) { i -> if (i % width in 8..11) white else grey(0x10) }
        val bounds = bandBounds(pixels, width, height)
        bounds.lightest.forEach { assertEquals(white, it, "the stripe is the strip's lightest") }
        val surface = meshInkSurface(bounds)
        val choice = resolveInk(NeutralInkAccent, surface, alphas)
        assertHolds(choice, surface)
        val stripe = scrimmed(white, MESH_SCRIM_TOP + (MESH_SCRIM_BOTTOM - MESH_SCRIM_TOP) * 0.40f)
        if (!choice.dark) {
            assertTrue(
                inkHolds(choice.fg.toArgb(), blendArgb(black, stripe, choice.wash), alphas),
                "the ink fails over the stripe itself",
            )
        }
    }

    @Test
    fun luminanceTableMatchesTheFormula() {
        fun formula(argb: Int): Float {
            fun linear(shift: Int): Float {
                val c = ((argb shr shift) and 0xFF) / 255f
                return if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
            }
            return 0.2126f * linear(16) + 0.7152f * linear(8) + 0.0722f * linear(0)
        }
        for (c in intArrayOf(white, black, grey(1), grey(10), grey(0x77), rgb(0x4B2B6E), rgb(0xC62828), rgb(0x00FF7F))) {
            assertEquals(formula(c), wcagLuminance(c), 1e-5f)
        }
    }

    @Test
    fun aDarkOnlyMoveIsStillPublished() {
        // The lightest pixels hold still while the darkest move: dark ink is
        // judged on the darkest, so this must not be suppressed.
        val before = InkSurface(light = intArrayOf(white), dark = intArrayOf(grey(0xC0)))
        val after = InkSurface(light = intArrayOf(white), dark = intArrayOf(grey(0x20)))
        assertTrue(inkSurfaceMoved(before, after))
        assertTrue(inkSurfaceMoved(before, InkSurface(intArrayOf(white), intArrayOf(grey(0xC0)), darkOverlay = 0.2f)))
        assertTrue(inkSurfaceMoved(before, InkSurface(intArrayOf(white, white), intArrayOf(grey(0xC0), grey(0xC0)))))
        assertFalse(inkSurfaceMoved(before, InkSurface(intArrayOf(white), intArrayOf(grey(0xC1)))))
    }

    @Test
    fun aShortPortraitClipIsJudgedOverTheMeshBelowItToo() {
        // A bright clip alone would carry dark ink with no grade; below a
        // shorter clip the controls stand on a dark mesh under the clip's
        // scrim as well. The ink has to hold over both, as drawn.
        val clip = portraitClipInkSurface(BandBounds.uniform(IntArray(INK_BANDS) { white }))
        val mesh = meshInkSurface(BandBounds.uniform(IntArray(INK_BANDS) { grey(0x18) }))
        val clipOnly = resolveInk(whiteSleeveRed.accent, clip, alphas)
        val composed = portraitOverMeshInkSurface(clip, mesh)
        val choice = resolveInk(whiteSleeveRed.accent, composed, alphas)
        assertHolds(choice, composed)
        // What the clip alone would have chosen, with its own grade, fails
        // over the mesh below it; the composed surface is what catches that.
        val meshBelow = scrimmed(scrimmed(scrimmed(grey(0x18), MESH_SCRIM_BOTTOM), HERO_WASH_MAX), 0.70f)
        val toward = if (clipOnly.dark) white else black
        assertFalse(
            inkHolds(clipOnly.fg.toArgb(), blendArgb(toward, meshBelow, clipOnly.wash), alphas),
            "the clip-only choice $clipOnly should fail over the mesh below the clip",
        )
    }

    @Test
    fun theBannerWashIsJudgedOverTheGradeNotUnderIt() {
        // The renderer grades the mesh's scrim white, then lays the banner's
        // black wash over both. Judged in that order, a dark ink over a
        // light mesh still holds once the banner's black lands on top.
        val surface = meshInkSurface(BandBounds.uniform(intArrayOf(grey(0xE8), grey(0xDC), grey(0xD0))))
        assertEquals(HERO_WASH_MAX, surface.darkOverlay)
        val choice = resolveInk(whiteSleeveRed.accent, surface, alphas)
        assertTrue(choice.dark)
        surface.dark.forEach { band ->
            val drawn = scrimmed(blendArgb(white, band, choice.wash), HERO_WASH_MAX)
            assertTrue(inkHolds(choice.fg.toArgb(), drawn, alphas), "fails over ${Integer.toHexString(drawn)}")
        }
    }

    @Test
    fun aPortraitClipIsJudgedUnderItsOwnScrim() {
        // A white portrait clip: the controls stand on the clip itself, under
        // a scrim that is clear where they start. Whatever ink is chosen has
        // to hold there, graded through that scrim.
        val surface = portraitClipInkSurface(BandBounds.uniform(IntArray(INK_BANDS) { white }))
        val choice = resolveInk(whiteSleeveRed.accent, surface, alphas)
        assertHolds(choice, surface)
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
        val surface = fullBlurInkSurface(BandBounds.uniform(IntArray(INK_BANDS) { white }))
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
        val surface = meshInkSurface(BandBounds.uniform(intArrayOf(white, white, white)))
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
        val surface = meshInkSurface(BandBounds.uniform(intArrayOf(grey(0xE8), grey(0xDC), grey(0xD0))))
        val choice = resolveInk(whiteSleeveRed.accent, surface, alphas)
        assertTrue(choice.dark)
        assertTrue(choice.wash in 0.01f..0.25f, "grade ${choice.wash}")
        assertTrue(choice.fg.red > choice.fg.blue, "the ink lost its hue: ${choice.fg}")
        assertHolds(choice, surface)
    }

    @Test
    fun darkCoverIsLeftAlone() {
        val navy = computeNowPlayingPalette(List(850) { rgb(0x1B2440) } + List(150) { rgb(0xFF8A3D) })
        val surface = meshInkSurface(BandBounds.uniform(intArrayOf(rgb(0x26324F), rgb(0x1A2440), rgb(0x0F1526))))
        val choice = resolveInk(navy.accent, surface, alphas)
        assertFalse(choice.dark)
        assertEquals(0f, choice.wash)
        assertEquals(navy.fg, choice.fg)
    }

    @Test
    fun canvasIsJudgedUnderItsDim() {
        // A white Canvas under the deck's half dim is mid grey.
        val surface = canvasInkSurface(BandBounds.uniform(IntArray(INK_BANDS) { white }))
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
        val surface = fullBlurInkSurface(BandBounds.uniform(IntArray(INK_BANDS) { white }))
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
