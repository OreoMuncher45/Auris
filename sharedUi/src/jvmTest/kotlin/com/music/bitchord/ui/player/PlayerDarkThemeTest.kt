package com.music.bitchord.ui.player

import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Dark theme with light artwork: the player keeps the cover's hue but stays a
 * dark screen with light ink. Light theme, and dark covers in either theme,
 * are exactly what they were.
 *
 * Each case runs the cover's cells through the mesh's own colour step
 * ([lifted]), the mesh's scrim ([meshInkSurface]) and the ink resolver, the
 * way the player does.
 */
class PlayerDarkThemeTest {

    private fun rgb(hex: Int) = (0xFF shl 24) or hex

    /** Pale cyan and lavender: the shape of the reported cover, not the cover. */
    private val paleCover = intArrayOf(
        rgb(0xE9F3F7), rgb(0xD8EEF5), rgb(0xEFE6F4), rgb(0xCFE4EE), rgb(0xF6EEF2), rgb(0xDCECF4),
    )

    private val darkCover = intArrayOf(
        rgb(0x14161F), rgb(0x1D2433), rgb(0x0E0F14), rgb(0x2A2238), rgb(0x181A20), rgb(0x22283A),
    )

    private val paleSleeve: NowPlayingPalette = computeNowPlayingPalette(
        List(700) { rgb(0xE4F0F5) } + List(200) { rgb(0xEADFF2) } + List(100) { rgb(0x3FA9C9) },
    )

    private fun meshCells(cover: IntArray, darkTheme: Boolean) = IntArray(cover.size) { cover[it].lifted(darkTheme) }

    private fun surfaceOf(cells: IntArray) = meshInkSurface(BandBounds(cells, cells))

    private fun hue(argb: Int) = Oklch.fromArgb(argb).hue

    private fun hueGap(a: Float, b: Float) = abs(a - b).let { min(it, 360f - it) }

    @Test
    fun paleCoverInDarkThemeStaysDarkWithLightInk() {
        val cells = meshCells(paleCover, darkTheme = true)
        cells.forEach { cell ->
            // Every cell is a dark colour even before the scrim; the old
            // light-theme cells sat at about 0.75.
            assertTrue(wcagLuminance(cell) < 0.22f, "cell ${Integer.toHexString(cell)} is not dark")
        }
        val surface = surfaceOf(cells)
        surface.light.forEach { shown ->
            // Under the mesh's scrim, where the chrome starts.
            assertTrue(wcagLuminance(shown) < 0.15f, "drawn ${Integer.toHexString(shown)} is not dark")
        }
        val choice = resolveInk(paleSleeve.accent, surface, paleSleeve.alphas, allowDark = false)
        assertFalse(choice.dark, "dark theme must not take dark ink")
        // Light ink holds on the dark mesh with a light grade. On the old
        // pale mesh it needed 0.58, which is why the resolver went dark.
        assertTrue(choice.wash <= 0.30f, "grade ${choice.wash} hides too much of the cover")
        val old = resolveInk(
            paleSleeve.accent, surfaceOf(meshCells(paleCover, darkTheme = false)), paleSleeve.alphas, allowDark = false,
        )
        assertTrue(choice.wash < old.wash / 2f, "grade ${choice.wash} vs ${old.wash} on the untoned mesh")
    }

    @Test
    fun paleCoverInDarkThemeKeepsItsHue() {
        paleCover.forEach { source ->
            val light = source.lifted(darkTheme = false)
            val dark = source.lifted(darkTheme = true)
            val chroma = Oklch.fromArgb(light).chroma
            // A near-grey has no hue to keep.
            if (chroma > 0.02f) {
                assertTrue(
                    hueGap(hue(light), hue(dark)) < 12f,
                    "hue moved from ${hue(light)} to ${hue(dark)}",
                )
            }
        }
    }

    @Test
    fun paleCoverInDarkThemeIsNeverAnsweredWithAWhiteGrade() {
        // What the old code did: dark ink, and a white grade laid over the
        // cover, which turned the player into a pale screen.
        val old = resolveInk(paleSleeve.accent, surfaceOf(meshCells(paleCover, darkTheme = false)), paleSleeve.alphas)
        assertTrue(old.dark && old.wash > 0f, "precondition: light theme answers a pale cover with dark ink")
        val now = resolveInk(paleSleeve.accent, surfaceOf(meshCells(paleCover, darkTheme = true)), paleSleeve.alphas, allowDark = false)
        assertFalse(now.dark)
    }

    @Test
    fun lightThemeIsUnchanged() {
        paleCover.forEach { source ->
            val cell = source.lifted(darkTheme = false)
            // The light theme mesh keeps the sleeve's own lightness.
            assertTrue(Oklch.fromArgb(cell).l > 0.85f, "light theme cell ${Integer.toHexString(cell)} was darkened")
            assertEquals(source.lifted(), cell)
        }
        // And the resolver, given the old arguments, gives the old answer.
        val surface = surfaceOf(meshCells(paleCover, darkTheme = false))
        assertEquals(
            resolveInk(paleSleeve.accent, surface, paleSleeve.alphas),
            resolveInk(paleSleeve.accent, surface, paleSleeve.alphas, allowDark = true),
        )
    }

    @Test
    fun darkCoverIsUnchangedInEitherTheme() {
        darkCover.forEach { source ->
            assertEquals(source.lifted(darkTheme = false), source.lifted(darkTheme = true))
        }
        val surface = surfaceOf(meshCells(darkCover, darkTheme = true))
        assertEquals(
            resolveInk(paleSleeve.accent, surface, paleSleeve.alphas),
            resolveInk(paleSleeve.accent, surface, paleSleeve.alphas, allowDark = false),
        )
    }

    @Test
    fun cellsAtTheKneeAreLeftAlone() {
        // A mid-dark cell sits under the knee: dark theme must not touch it.
        val mid = rgb(0x3A4A60)
        assertTrue(Oklch.fromArgb(mid).l <= DARK_MESH_KNEE)
        assertEquals(mid, darkThemeMeshCell(mid))
    }

    // --- A bright, saturated cover ------------------------------------------

    /** Cyan, magenta, yellow and white: an '80s-dance style sleeve. */
    private val saturatedCover = intArrayOf(
        rgb(0x00D5D5), rgb(0xFF00FF), rgb(0xFFF000), rgb(0xFFFFFF), rgb(0x2EC4E0), rgb(0x3A3FD0),
    )

    private val saturatedSleeve: NowPlayingPalette = computeNowPlayingPalette(
        List(450) { rgb(0x00D5D5) } + List(250) { rgb(0xFF00FF) } + List(150) { rgb(0xFFF000) } +
            List(100) { rgb(0xFFFFFF) } + List(50) { rgb(0x3A3FD0) },
    )

    @Test
    fun saturatedCoverInDarkThemeStaysDarkWithLightInk() {
        val cells = meshCells(saturatedCover, darkTheme = true)
        cells.forEach { cell ->
            assertTrue(wcagLuminance(cell) < 0.22f, "cell ${Integer.toHexString(cell)} is not dark")
        }
        val surface = surfaceOf(cells)
        surface.light.forEach { shown ->
            assertTrue(wcagLuminance(shown) < 0.16f, "drawn ${Integer.toHexString(shown)} is not dark")
        }
        val choice = resolveInk(saturatedSleeve.accent, surface, saturatedSleeve.alphas, allowDark = false)
        assertFalse(choice.dark)
        assertTrue(choice.wash <= 0.35f, "grade ${choice.wash}")
        // And whatever it settles on is light ink, not a dark one that happens to hold.
        assertTrue(choice.fg.isLightInk(), "ink ${Integer.toHexString(choice.fg.toArgb())} is not light")
    }

    @Test
    fun saturatedCoverOnTheClassicBackdropIsDarkInDarkTheme() {
        // The classic blobs clamp HSL lightness to 0.58, which for a saturated
        // cyan or yellow is still a bright colour.
        val palette = MeshPalette(saturatedCover.take(4).map { androidx.compose.ui.graphics.Color(it) })
        val old = legacyMeshInkSurface(palette)
        assertTrue(old.light.any { wcagLuminance(it) > 0.4f }, "precondition: the classic blobs were bright")
        val dark = legacyMeshInkSurface(palette, darkTheme = true)
        dark.light.forEach { shown ->
            assertTrue(wcagLuminance(shown) < 0.16f, "classic blob ${Integer.toHexString(shown)} is not dark")
        }
        val choice = resolveInk(saturatedSleeve.accent, dark, saturatedSleeve.alphas, allowDark = false)
        assertFalse(choice.dark)
        assertTrue(choice.wash <= 0.35f, "grade ${choice.wash}")
        // Light theme keeps the classic blobs exactly as they were.
        assertTrue(old.light.contentEquals(legacyMeshInkSurface(palette, darkTheme = false).light))
    }

    @Test
    fun saturatedCoverInLightThemeIsUnchanged() {
        saturatedCover.forEach { assertEquals(it.lifted(), it.lifted(darkTheme = false)) }
    }

    // --- The full-cover blur behind lyrics and the queue -------------------

    @Test
    fun paleBlurUsesNormalPanelInkChoice() {
        // Dark theme no longer tones the full-cover blur or forces light ink.
        // Its fixed contrast scrim stays, and the ordinary resolver may choose
        // dark ink instead of paying for a much heavier black grade.
        val surface = fullBlurInkSurface(BandBounds(paleCover.copyOfRange(0, 3), paleCover.copyOfRange(3, 6)))
        val choice = resolveInk(paleSleeve.accent, surface, paleSleeve.alphas)
        val lightOnly = resolveInk(paleSleeve.accent, surface, paleSleeve.alphas, allowDark = false)
        assertTrue(choice.dark)
        assertTrue(choice.wash > 0f)
        assertFalse(lightOnly.dark)
        assertTrue(choice.wash < lightOnly.wash)
    }

    // --- The "Playing from" header ----------------------------------------

    private val captionAlpha = min(1f, NowPlayingAlphas().secondary + 0.25f)

    private fun headerShown(band: Int, grade: Float) = blendArgb(0xFF000000.toInt(), scrimmed(band, HEADER_SCRIM_AT_CAPTION), grade)

    @Test
    fun headerOverAWhiteCoverTopIsLightAndReadable() {
        val white = rgb(0xFFFFFF)
        val cyan = rgb(0x2A8FB0)
        val surface = headerInkSurface(BandBounds(intArrayOf(white, white), intArrayOf(cyan, cyan)))
        val choice = resolveHeaderInk(paleSleeve.accent, surface, NowPlayingAlphas())
        assertFalse(choice.dark)
        assertTrue(choice.wash <= HEADER_MAX_GRADE + 1e-4f, "grade ${choice.wash} past the cap")
        // Total black at the caption stays at or under 60% of the cover.
        val total = 1f - (1f - HEADER_SCRIM_AT_CAPTION) * (1f - choice.wash)
        assertTrue(total <= 0.605f, "header hides $total of the cover")
        val ink = choice.fg.toArgb()
        listOf(white, cyan).forEach { band ->
            val shown = headerShown(band, choice.wash)
            val ratio = contrastRatio(wcagLuminance(blendArgb(ink, shown, captionAlpha)), wcagLuminance(shown))
            assertTrue(ratio >= TEXT_MIN_CONTRAST, "caption $ratio:1 over ${Integer.toHexString(shown)}")
        }
    }

    @Test
    fun headerOverAPlainDarkTopTakesNoGrade() {
        val dusk = rgb(0x6B5A8E)
        val surface = headerInkSurface(BandBounds(intArrayOf(dusk, dusk), intArrayOf(rgb(0x3A3060), rgb(0x3A3060))))
        val choice = resolveHeaderInk(paleSleeve.accent, surface, NowPlayingAlphas())
        assertFalse(choice.dark)
        assertEquals(0f, choice.wash)
    }

    @Test
    fun headerScrimHoldsThroughTheCaption() {
        // The caption sits about 11% down the screen: half-way down the scrim.
        val atCaption = scrimAlphaAt(0.5f, HEADER_SCRIM_POSITIONS, HEADER_SCRIM_ALPHAS)
        assertEquals(HEADER_SCRIM_AT_CAPTION, atCaption)
        // The old scrim was 40% at the top fading to nothing at 14% of the
        // screen: about 9% at the caption, which is what let busy covers through.
        val old = 0.40f * (1f - 0.11f / 0.14f)
        assertTrue(atCaption > old * 4f)
        assertEquals(0f, HEADER_SCRIM_ALPHAS.last())
    }
}
