package com.music.bitchord.ui.player

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.pow

/*
 * Judging the player's ink against the surface actually drawn under it.
 *
 * [computeNowPlayingPalette] decides between light and dark ink from the raw
 * sleeve's lower half. The chrome never sits on the raw sleeve: it sits on the
 * mesh, the full-cover blur or the legacy blobs, each under scrims of its own,
 * or on a Canvas under its dim. A mostly white sleeve therefore got deep ink
 * over a backdrop that was dark by the time it was drawn. Everything here works
 * on the colours of that drawn surface instead, keeping the record's hue and
 * moving only the ink's lightness — and, if no lightness will do, grading the
 * backdrop's existing scrim.
 *
 * Pure functions over packed ARGB ints, so they run off the main thread and
 * can be tested without a screen.
 */

/** WCAG AA for text. */
internal const val TEXT_MIN_CONTRAST = 4.5f

/** WCAG AA for icons and other non-text controls. */
internal const val ICON_MIN_CONTRAST = 3f

/**
 * The most the player will grade its backdrop to make the ink hold. Past this
 * the cover stops reading as the cover. Enough on its own for light ink over
 * any colour at all: even pure white comes down to a grey that light ink holds on.
 */
internal const val MAX_INK_WASH = 0.65f

/** How many horizontal bands of a backdrop are sampled under the chrome. */
internal const val INK_BANDS = 3

private const val WHITE_ARGB = 0xFFFFFFFF.toInt()
private const val BLACK_ARGB = 0xFF000000.toInt()

/**
 * The surface under the player's chrome, as the opaque colours it shows.
 *
 * Two renderings of the same bands, because the backdrops grade their lower
 * part harder than their upper: [light] is each band where it is lightest —
 * what light ink has to hold against — and [dark] where it is darkest, which
 * is what dark ink has to hold against.
 */
@Immutable
internal class InkSurface(
    val light: IntArray,
    val dark: IntArray,
    /**
     * Black a renderer lays *over* the grade on the dark side — the banner's
     * wash, drawn above the mesh whose scrim carries the grade. Applied after
     * the white grade when judging dark ink, in the order it is drawn.
     */
    val darkOverlay: Float = 0f,
)

/**
 * The extremes of each horizontal strip of a picture: its lightest and its
 * darkest cell. A broad bright patch under one control can hide in a strip's
 * average; it cannot hide in its lightest cell. Light ink is judged against
 * [lightest], dark ink against [darkest].
 */
@Immutable
internal class BandBounds(val lightest: IntArray, val darkest: IntArray) {
    companion object {
        /** A picture with no variation across each strip. */
        fun uniform(colors: IntArray) = BandBounds(colors, colors)
    }
}

/**
 * What the player settled on for one surface: the ink, whether it is the dark
 * side, and how hard the surface's scrim is graded to carry it — black under
 * light ink, white under dark.
 */
@Immutable
internal data class InkResolution(val fg: Color, val dark: Boolean, val wash: Float)

/**
 * The backdrops' inks, published from the scopes that draw them and read only
 * by the ink's own effect — never by the player's composition.
 */
@Stable
internal class PlayerInkSurfaces {
    /** The main view: the artwork mesh or the legacy blobs. */
    var main: InkSurface? by mutableStateOf(null)

    /** The full-cover blur behind lyrics, queue, tablet and landscape. */
    var panel: InkSurface? by mutableStateOf(null)
}

/**
 * Which backdrop the chrome is standing on right now: the main view's mesh or
 * legacy blobs, or the full-cover blur. A Canvas owning the screen brings its
 * own surface with its palette — see [LivePalette.surface].
 */
internal enum class InkSurfaceKind { MAIN, PANEL }

/**
 * Each 8-bit sRGB level in linear light, once: the same values the WCAG
 * formula gives, without a `pow` per channel per pixel.
 */
private val SRGB_TO_LINEAR = FloatArray(256) { level ->
    val c = level / 255f
    if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
}

/** WCAG relative luminance, in linear light. */
internal fun wcagLuminance(argb: Int): Float =
    0.2126f * SRGB_TO_LINEAR[(argb shr 16) and 0xFF] +
        0.7152f * SRGB_TO_LINEAR[(argb shr 8) and 0xFF] +
        0.0722f * SRGB_TO_LINEAR[argb and 0xFF]

/** WCAG contrast ratio between two relative luminances. */
internal fun contrastRatio(a: Float, b: Float): Float {
    val hi = maxOf(a, b)
    val lo = minOf(a, b)
    return (hi + 0.05f) / (lo + 0.05f)
}

/**
 * [over] at [alpha] composited onto opaque [under], per channel in sRGB — the
 * way Skia blends, so the result is the pixel the screen shows.
 */
internal fun blendArgb(over: Int, under: Int, alpha: Float): Int {
    val a = alpha.coerceIn(0f, 1f)
    fun channel(shift: Int): Int {
        val top = (over shr shift) and 0xFF
        val bottom = (under shr shift) and 0xFF
        return (bottom + (top - bottom) * a + 0.5f).toInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

/** [color] under a black scrim of [alpha]. */
internal fun scrimmed(color: Int, alpha: Float): Int = blendArgb(BLACK_ARGB, color, alpha)

/**
 * Whether [ink] holds on [background] at the player's own opacity roles: the
 * primary and secondary roles are text and need 4.5:1, the tertiary role is
 * icons and needs 3:1.
 */
internal fun inkHolds(ink: Int, background: Int, alphas: NowPlayingAlphas): Boolean {
    val bg = wcagLuminance(background)
    fun at(alpha: Float) = contrastRatio(wcagLuminance(blendArgb(ink, background, alpha)), bg)
    return at(alphas.primary) >= TEXT_MIN_CONTRAST &&
        at(alphas.secondary) >= TEXT_MIN_CONTRAST &&
        at(alphas.tertiary) >= ICON_MIN_CONTRAST
}

/**
 * The ink for chrome over [surface], built from [accent].
 *
 * Each side starts at the lightness the accent prefers there and moves only
 * outward — light ink lighter, dark ink darker — until it holds on every band
 * of the surface. If no lightness holds, the surface's scrim is graded by the
 * least that lets one. The side needing less grading wins, then the side that
 * moved less from where it wanted to be; light wins a tie. The hue never moves.
 */
internal fun resolveInk(
    accent: InkAccent,
    surface: InkSurface,
    alphas: NowPlayingAlphas = NowPlayingAlphas(),
): InkResolution {
    // Capped short of white, not at white. Gamut mapping gives up chroma as
    // lightness rises, and at OKLCH L=1 there is none left to give: the
    // resolver could settle on a colourless pixel, so "Use artwork" drew a
    // white transport on a coloured screen. Below this ceiling a hue still
    // survives, and if nothing in the band holds, the backdrop wash below
    // buys the contrast instead of the ink's colour.
    val lights = generateSequence(accent.lightL) { it + L_STEP }
        .takeWhile { it <= LIGHT_INK_CEILING + 1e-4f }
        .map { accent.at(it).toArgb() }.toList()
    val darks = generateSequence(accent.darkL) { it - L_STEP }.takeWhile { it >= -1e-4f }
        .map { accent.at(it).toArgb() }.toList()

    class Side(val index: Int, val wash: Float, val ink: Int)

    fun firstHolding(inks: List<Int>, bands: IntArray, toward: Int, wash: Float): Int {
        // The grade first, then anything drawn over it — the renderer's order.
        val overlay = if (toward == WHITE_ARGB) surface.darkOverlay else 0f
        val shown = IntArray(bands.size) { scrimmed(blendArgb(toward, bands[it], wash), overlay) }
        return inks.indexOfFirst { ink -> shown.all { inkHolds(ink, it, alphas) } }
    }

    // The least wash at which some lightness holds. Monotone — more black
    // under light ink, or more white under dark, only ever helps — so a
    // bisection finds it in a dozen tries rather than a sweep.
    fun side(inks: List<Int>, bands: IntArray, toward: Int): Side? {
        if (inks.isEmpty() || bands.isEmpty()) return null
        firstHolding(inks, bands, toward, 0f).let { if (it >= 0) return Side(it, 0f, inks[it]) }
        if (firstHolding(inks, bands, toward, MAX_INK_WASH) < 0) return null
        var low = 0f
        var high = MAX_INK_WASH
        repeat(WASH_BISECTIONS) {
            val mid = (low + high) / 2f
            if (firstHolding(inks, bands, toward, mid) >= 0) high = mid else low = mid
        }
        val index = firstHolding(inks, bands, toward, high)
        return Side(index, high, inks[index])
    }

    val light = side(lights, surface.light, BLACK_ARGB)
    val dark = side(darks, surface.dark, WHITE_ARGB)
    val darkWins = dark != null && (
        light == null ||
            dark.wash < light.wash - WASH_TIE ||
            (abs(dark.wash - light.wash) <= WASH_TIE && dark.index < light.index)
        )
    return when {
        darkWins -> InkResolution(Color(dark!!.ink), dark = true, wash = dark.wash)
        light != null -> InkResolution(Color(light.ink), dark = false, wash = light.wash)
        // Unreachable in practice — see MAX_INK_WASH — but never leave the
        // chrome without an answer.
        else -> InkResolution(Color(lights.last()), dark = false, wash = MAX_INK_WASH)
    }
}

private fun abs(value: Float) = if (value < 0f) -value else value

/** One lightness step: about a just-noticeable difference in OKLCH. */
private const val L_STEP = 0.02f

/** How light the ink may go: far enough to read, short of losing the hue. */
internal const val LIGHT_INK_CEILING = 0.95f

/** Bisections of the wash: 0.65 / 2^10, well under a percent. */
private const val WASH_BISECTIONS = 10

/** Washes this close are the same grade to the eye. */
private const val WASH_TIE = 0.005f

/**
 * Mean sRGB colour of each of [bands] equal horizontal strips of a [width] x
 * [height] ARGB image, top to bottom.
 */
internal fun bandAverages(pixels: IntArray, width: Int, height: Int, bands: Int = INK_BANDS): IntArray {
    require(width > 0 && height > 0 && pixels.size >= width * height)
    return IntArray(bands) { band ->
        val from = band * height / bands
        val to = ((band + 1) * height / bands).coerceAtLeast(from + 1).coerceAtMost(height)
        averageRows(pixels, width, from, to)
    }
}

/**
 * The lightest and darkest pixel of each of [bands] strips of a [width] x
 * [height] ARGB image, by WCAG luminance.
 *
 * Pixels, not cells: an average of any size can dilute a thin bright stripe
 * that falls across its edge, and the pictures read here are what is drawn —
 * the mesh's texels are interpolated as they are, the full-cover blur is
 * already blurred — so their extremes are the extremes on screen.
 */
internal fun bandBounds(
    pixels: IntArray,
    width: Int,
    height: Int,
    bands: Int = INK_BANDS,
): BandBounds {
    require(width > 0 && height > 0 && pixels.size >= width * height)
    val lightest = IntArray(bands)
    val darkest = IntArray(bands)
    for (band in 0 until bands) {
        val top = band * height / bands
        val bottom = ((band + 1) * height / bands).coerceAtLeast(top + 1).coerceAtMost(height)
        var maxL = -1f
        var minL = 2f
        for (i in top * width until bottom * width) {
            val pixel = pixels[i] or (0xFF shl 24)
            val l = wcagLuminance(pixel)
            if (l > maxL) { maxL = l; lightest[band] = pixel }
            if (l < minL) { minL = l; darkest[band] = pixel }
        }
    }
    return BandBounds(lightest, darkest)
}

/** Mean colour of rows [from] until [to] of an ARGB image [width] wide. */
internal fun averageRows(pixels: IntArray, width: Int, from: Int, to: Int): Int {
    var red = 0L
    var green = 0L
    var blue = 0L
    var count = 0
    for (row in from until to) {
        val base = row * width
        for (x in 0 until width) {
            val pixel = pixels[base + x]
            red += (pixel shr 16) and 0xFF
            green += (pixel shr 8) and 0xFF
            blue += pixel and 0xFF
            count++
        }
    }
    if (count == 0) return BLACK_ARGB
    return (0xFF shl 24) or
        ((red / count).toInt() shl 16) or
        ((green / count).toInt() shl 8) or
        (blue / count).toInt()
}

/**
 * The alpha of a vertical gradient scrim at [fraction] of its height, between
 * stops given as parallel [positions] (ascending, 0..1) and [alphas].
 */
internal fun scrimAlphaAt(fraction: Float, positions: FloatArray, alphas: FloatArray): Float {
    val f = fraction.coerceIn(0f, 1f)
    if (f <= positions.first()) return alphas.first()
    for (i in 1 until positions.size) {
        if (f <= positions[i]) {
            val span = positions[i] - positions[i - 1]
            val t = if (span <= 0f) 1f else (f - positions[i - 1]) / span
            return alphas[i - 1] + (alphas[i] - alphas[i - 1]) * t
        }
    }
    return alphas.last()
}

/**
 * One gradient stop for a backdrop's own black scrim of [scrimAlpha] with the
 * ink's grade folded in, so the grade costs no draw of its own.
 *
 * Black [blackWash] and then white [whiteWash] laid over the scrim collapse
 * into a single grey at a single alpha: composited over any colour c they give
 * c·(1-a)(1-b)(1-w) + w, which is one layer of alpha 1-(1-a)(1-b)(1-w) and
 * grey w over that alpha.
 */
internal fun gradedScrimStop(scrimAlpha: Float, blackWash: Float, whiteWash: Float): Color {
    val keep = (1f - scrimAlpha) * (1f - blackWash) * (1f - whiteWash)
    val alpha = (1f - keep).coerceIn(0f, 1f)
    if (alpha <= 0f) return Color.Transparent
    val grey = (whiteWash / alpha).coerceIn(0f, 1f)
    return Color(grey, grey, grey, alpha)
}

// --- What each backdrop shows under the chrome ------------------------------

/** [FullArtworkBlurBackdrop]'s legibility scrim: where its stops sit, and their black. */
internal val FULL_BLUR_SCRIM_POSITIONS = floatArrayOf(0f, 0.55f, 1f)
internal val FULL_BLUR_SCRIM_ALPHAS = floatArrayOf(0.34f, 0.48f, 0.64f)

/** [ArtworkMeshBackdrop]'s scrim, top and bottom of the surface. */
internal const val MESH_SCRIM_TOP = 0.06f
internal const val MESH_SCRIM_BOTTOM = 0.30f

/**
 * How far down the screen the main view's chrome begins at the earliest: the
 * title under a full-bleed banner. The mesh's scrim is lightest there.
 */
private const val MESH_CHROME_TOP = 0.40f

/** The banner's wash at its strongest, at the foot of the screen — see [PortraitBackdrop]. */
internal const val HERO_WASH_MAX = 0.12f

/** The dim over a Spotify Canvas while its control deck is up. */
internal const val CANVAS_DECK_DIM = 0.5f

/**
 * The full-cover blur's bands under its scrim: each band at the top of its
 * stretch of the gradient for light ink, at the bottom for dark.
 */
internal fun fullBlurInkSurface(bounds: BandBounds): InkSurface {
    val n = bounds.lightest.size
    fun at(fraction: Float) = scrimAlphaAt(fraction, FULL_BLUR_SCRIM_POSITIONS, FULL_BLUR_SCRIM_ALPHAS)
    return InkSurface(
        light = IntArray(n) { scrimmed(bounds.lightest[it], at(it.toFloat() / n)) },
        dark = IntArray(n) { scrimmed(bounds.darkest[it], at((it + 1f) / n)) },
    )
}

/**
 * The artwork mesh's bands as the chrome sees them: at the mesh's scrim where
 * the chrome starts for light ink, and under the full scrim plus the banner's
 * wash for dark — whichever band ends up behind whichever control.
 */
internal fun meshInkSurface(bounds: BandBounds): InkSurface {
    val lightest = MESH_SCRIM_TOP + (MESH_SCRIM_BOTTOM - MESH_SCRIM_TOP) * MESH_CHROME_TOP
    return InkSurface(
        light = IntArray(bounds.lightest.size) { scrimmed(bounds.lightest[it], lightest) },
        // The mesh's scrim carries the grade; the banner's wash is drawn over
        // both, so it is applied after the grade — see [InkSurface.darkOverlay].
        dark = IntArray(bounds.darkest.size) { scrimmed(bounds.darkest[it], MESH_SCRIM_BOTTOM) },
        darkOverlay = HERO_WASH_MAX,
    )
}

/** What the mesh backdrop shows before it has ever had a mesh: its flat fallback. */
internal fun fallbackMeshInkSurface(): InkSurface =
    meshInkSurface(BandBounds.uniform(IntArray(INK_BANDS) { FallbackBackdrop.toArgb() }))

/**
 * Publishes the surface of the mesh [ArtworkMeshBackdrop] is actually showing.
 *
 * That backdrop keeps the last mesh it was given while the next one is null,
 * and draws its flat fallback only if it has never had one. This follows the
 * same rule. The mesh on screen is recorded the moment it arrives, before any
 * work that could be cancelled: a track change that lands mid-calculation
 * cancels the calculation, not the record, so the null that follows still
 * re-reads the retained mesh rather than publishing the fallback under it.
 */
internal class MeshInkPublisher<M : Any>(
    private val bands: suspend (M) -> BandBounds,
    private val publish: (InkSurface) -> Unit,
) {
    private var shown: M? = null

    suspend fun onMesh(mesh: M?) {
        if (mesh != null) shown = mesh
        val current = shown
        publish(if (current == null) fallbackMeshInkSurface() else meshInkSurface(bands(current)))
    }
}

/** A Spotify Canvas frame's bands under the dim the deck brings with it. */
internal fun canvasInkSurface(bounds: BandBounds): InkSurface = InkSurface(
    light = IntArray(bounds.lightest.size) { scrimmed(bounds.lightest[it], CANVAS_DECK_DIM) },
    dark = IntArray(bounds.darkest.size) { scrimmed(bounds.darkest[it], CANVAS_DECK_DIM) },
)

/**
 * The scrim over a portrait motion cover that runs the screen behind the
 * controls: where its stops sit, its black, and how much of the ink's grade
 * each stop carries. The grade ramps in above the controls rather than
 * dimming the clip where it is the picture.
 */
internal val PORTRAIT_CLIP_SCRIM_POSITIONS = floatArrayOf(0f, 0.25f, 0.40f, 1f)
internal val PORTRAIT_CLIP_SCRIM_ALPHAS = floatArrayOf(0f, 0f, 0f, 0.70f)
internal val PORTRAIT_CLIP_WASH_SHARE = floatArrayOf(0f, 0f, 1f, 1f)

/**
 * What the controls stand on with a portrait motion cover up: the clip where
 * it reaches them, and the mesh below it where a shorter clip ends — both
 * under the clip's scrim, which carries the grade for both. Every band of
 * either has to hold, since which control sits over which is not known.
 *
 * The mesh is drawn with its own scrim (graded nothing while the clip is up),
 * then the banner's wash, then the clip's scrim; its dark side takes all
 * three before the grade, in that order.
 */
internal fun portraitOverMeshInkSurface(clip: InkSurface, mesh: InkSurface?): InkSurface {
    if (mesh == null) return clip
    val clipScrim = PORTRAIT_CLIP_SCRIM_ALPHAS.last()
    val meshDark = IntArray(mesh.dark.size) { scrimmed(scrimmed(mesh.dark[it], mesh.darkOverlay), clipScrim) }
    return InkSurface(light = clip.light + mesh.light, dark = clip.dark + meshDark)
}

/**
 * A portrait motion cover's bands as the controls see them: the clip's own
 * frame, under its scrim at its lightest where the controls start (light ink)
 * and at its darkest at the foot of the screen (dark ink).
 */
internal fun portraitClipInkSurface(bounds: BandBounds): InkSurface {
    val top = scrimAlphaAt(MESH_CHROME_TOP, PORTRAIT_CLIP_SCRIM_POSITIONS, PORTRAIT_CLIP_SCRIM_ALPHAS)
    return InkSurface(
        light = IntArray(bounds.lightest.size) { scrimmed(bounds.lightest[it], top) },
        dark = IntArray(bounds.darkest.size) { scrimmed(bounds.darkest[it], PORTRAIT_CLIP_SCRIM_ALPHAS.last()) },
    )
}

/** WCAG relative luminance of a [Color], for tests and callers holding one. */
internal fun Color.wcagLuminance(): Float = wcagLuminance(toArgb())
