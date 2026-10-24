package com.music.bitchord.ui.player

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The expanded player's colours, derived from the artwork alone.
 *
 * The ink is a legible tint of the sleeve's *secondary* colour — a colour the
 * cover really uses but is not made of. The dominant colour is also what the
 * blurred backdrop is mostly made of, so ink built from it disappears into the
 * wash; a supporting colour is the note that reads as "this record" without
 * echoing the background. A sleeve with only one colour falls back to that
 * colour, and a neutral sleeve keeps neutral ink.
 *
 * Lightness follows the artwork's own brightness rather than forcing the
 * backdrop dark: dark ink on a bright sleeve, light ink on a dark one. That is
 * what keeps the text readable while the backdrop keeps the sleeve's colours.
 */
@Immutable
internal data class NowPlayingPalette(
    /** Title, transport, filled progress, and every icon at rest. */
    val fg: Color,
    val alphas: NowPlayingAlphas = NowPlayingAlphas(),
)

/**
 * The player's opacity roles. Keeping them here rather than as literals at
 * each glyph means a cover change can never leave one control behind.
 */
@Immutable
internal data class NowPlayingAlphas(
    /** Title, play/pause, previous, next, filled progress and volume. */
    val primary: Float = 1f,
    /** Artist, time labels, the quality badge and the current lyric. */
    val secondary: Float = 0.70f,
    /** Lyrics, AirPlay, queue and volume icons, and the device name. */
    val tertiary: Float = 0.65f,
    /** The unfilled part of the progress and volume tracks. */
    val track: Float = 0.30f,
    /** The discs behind the round star and "..." buttons. */
    val buttonFill: Float = 0.20f,
)

/** The palette before any artwork has been read: neutral, dark, near-white ink. */
internal val DefaultNowPlayingPalette = NowPlayingPalette(
    fg = Oklch(0.92f, 0f, 0f).toColor(),
)

/**
 * [art] is a sampled set of the sleeve's ARGB pixels, in reading order (the
 * last samples are the lower half of the cover). The function is pure so the
 * awkward covers — dark, bright, grey, mono, saturated, two-colour and
 * neutral — can be scored in a unit test with nothing composable involved.
 */
internal fun computeNowPlayingPalette(art: List<Int>): NowPlayingPalette {
    val visible = art.filter { (it ushr 24) != 0 }
    val buckets = cluster(visible)
    if (buckets.isEmpty()) return DefaultNowPlayingPalette

    val total = buckets.sumOf { it.count }.coerceAtLeast(1)
    val accent = secondaryAccent(buckets, total)
    val brightBackdrop = controlLuminance(visible) >= BRIGHT_BACKDROP_LUMINANCE

    val fg = when {
        accent == null -> if (brightBackdrop) DARK_NEUTRAL_INK else LIGHT_NEUTRAL_INK
        brightBackdrop -> accent.copy(
            l = (accent.l - DARK_INK_DROP).coerceIn(DARK_INK_MIN_L, DARK_INK_MAX_L),
            chroma = accent.chroma.coerceIn(MIN_INK_CHROMA, MAX_INK_CHROMA),
        ).toColor()
        else -> accent.copy(
            l = (accent.l + LIGHT_INK_LIFT).coerceIn(LIGHT_INK_MIN_L, LIGHT_INK_MAX_L),
            chroma = accent.chroma.coerceIn(MIN_INK_CHROMA, MAX_INK_CHROMA),
        ).toColor()
    }

    return NowPlayingPalette(fg = fg)
}

private class Accent(val oklch: Oklch, val weight: Float)

/**
 * The colour the automatic ink should follow: the second colour the sleeve is
 * really made of, not the one that owns it.
 *
 * Colours are ranked by how much of the cover they take up against how vivid
 * they are. The leader is the primary colour — the one the backdrop already
 * carries — and the next-ranked colour far enough away in hue is the
 * secondary. A sleeve with a single chromatic colour returns that colour so
 * the ink still comes from the record; a grey sleeve returns null.
 */
private fun secondaryAccent(buckets: List<Bucket>, total: Int): Oklch? {
    val accents = buckets.mapNotNull { bucket ->
        val oklch = Oklch.fromArgb(bucket.color.toArgb())
        val share = bucket.count.toFloat() / total
        if (
            share < MIN_SECONDARY_SHARE ||
            oklch.chroma < MIN_ACCENT_CHROMA ||
            oklch.l < MIN_ACCENT_L ||
            oklch.l > MAX_ACCENT_L
        ) {
            return@mapNotNull null
        }
        Accent(oklch, oklch.chroma * share)
    }.sortedByDescending { it.weight }

    val primary = accents.firstOrNull() ?: return null
    val secondary = accents.firstOrNull {
        hueGap(it.oklch.hue, primary.oklch.hue) >= SECONDARY_MIN_HUE_GAP
    }
    return (secondary ?: primary).oklch
}

/**
 * Relative luminance of the artwork's lower half, where the transport and
 * action icons sit. Samples arrive in reading order, so the back half of the
 * list is the bottom of the sleeve.
 */
private fun controlLuminance(samples: List<Int>): Float {
    if (samples.isEmpty()) return 0f
    val lower = if (samples.size > 1) {
        samples.subList(samples.size / 2, samples.size)
    } else {
        samples
    }
    var sum = 0f
    lower.forEach { sum += relativeLuminance(it) }
    return sum / lower.size
}

private fun hueGap(one: Float, two: Float): Float {
    val gap = abs(one - two) % 360f
    return if (gap > 180f) 360f - gap else gap
}

/** WCAG relative luminance, gamma-decoded rather than read off the bytes. */
private fun relativeLuminance(argb: Int): Float {
    fun linear(shift: Int) = srgbToLinear(((argb shr shift) and 0xFF) / 255f)
    return 0.2126f * linear(16) + 0.7152f * linear(8) + 0.0722f * linear(0)
}

/** One colour's share of the sleeve, averaged rather than quantised to its bin. */
private class Bucket {
    var count = 0
    var red = 0L
    var green = 0L
    var blue = 0L

    val color: Color
        get() {
            val n = count.coerceAtLeast(1)
            return Color(
                red = (red / n).toInt(),
                green = (green / n).toInt(),
                blue = (blue / n).toInt(),
            )
        }
}

/**
 * 5 bits per channel is enough to separate a red accent from a grey sleeve,
 * and small enough that a 128 px cover produces a few dozen buckets rather
 * than one per pixel.
 */
private fun cluster(pixels: List<Int>): List<Bucket> {
    val buckets = HashMap<Int, Bucket>()
    pixels.forEach { argb ->
        val red = (argb shr 16) and 0xFF
        val green = (argb shr 8) and 0xFF
        val blue = argb and 0xFF
        val key = (red shr 3 shl 10) or (green shr 3 shl 5) or (blue shr 3)
        val bucket = buckets.getOrPut(key, ::Bucket)
        bucket.count++
        bucket.red += red
        bucket.green += green
        bucket.blue += blue
    }
    return buckets.values.toList()
}

/** OKLCH: L 0..1, chroma, hue in degrees. */
private data class Oklch(val l: Float, val chroma: Float, val hue: Float) {
    companion object {
        fun fromArgb(argb: Int): Oklch {
            val r = srgbToLinear(((argb shr 16) and 0xFF) / 255f)
            val g = srgbToLinear(((argb shr 8) and 0xFF) / 255f)
            val b = srgbToLinear((argb and 0xFF) / 255f)

            val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
            val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
            val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)

            val lightness = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s
            val a = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s
            val bb = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
            var hue = Math.toDegrees(atan2(bb, a).toDouble()).toFloat()
            if (hue < 0f) hue += 360f
            return Oklch(lightness, sqrt(a * a + bb * bb), hue)
        }
    }

    fun toColor(): Color {
        fun linear(chroma: Float): FloatArray {
            val radians = Math.toRadians(hue.toDouble())
            val a = chroma * cos(radians).toFloat()
            val b = chroma * sin(radians).toFloat()
            val l = this.l + 0.3963377774f * a + 0.2158037573f * b
            val m = this.l - 0.1055613458f * a - 0.0638541728f * b
            val s = this.l - 0.0894841775f * a - 1.2914855480f * b
            val l3 = l * l * l
            val m3 = m * m * m
            val s3 = s * s * s
            return floatArrayOf(
                4.0767416621f * l3 - 3.3077115913f * m3 + 0.2309699292f * s3,
                -1.2684380046f * l3 + 2.6097574011f * m3 - 0.3413193965f * s3,
                -0.0041960863f * l3 - 0.7034186147f * m3 + 1.7076147010f * s3,
            )
        }

        fun inGamut(rgb: FloatArray) = rgb.all { it in -0.0001f..1.0001f }

        // Out of gamut, give up chroma and nothing else: changing L would
        // break the legibility the ink was chosen for, and changing H would
        // change the record.
        var chroma = this.chroma.coerceAtLeast(0f)
        if (!inGamut(linear(chroma))) {
            var low = 0f
            var high = chroma
            repeat(GAMUT_STEPS) {
                val mid = (low + high) / 2f
                if (inGamut(linear(mid))) low = mid else high = mid
            }
            chroma = low
        }
        val rgb = linear(chroma)
        return Color(
            red = linearToSrgb(rgb[0].coerceIn(0f, 1f)),
            green = linearToSrgb(rgb[1].coerceIn(0f, 1f)),
            blue = linearToSrgb(rgb[2].coerceIn(0f, 1f)),
        )
    }
}

private fun srgbToLinear(channel: Float): Float =
    if (channel <= 0.04045f) channel / 12.92f
    else ((channel + 0.055f) / 1.055f).pow(2.4f)

private fun linearToSrgb(channel: Float): Float =
    if (channel <= 0.0031308f) channel * 12.92f
    else 1.055f * channel.pow(1f / 2.4f) - 0.055f

private fun cbrt(value: Float): Float =
    if (value < 0f) -(-value).pow(1f / 3f) else value.pow(1f / 3f)

/** A colour below this share of the cover is too rare to carry the ink. */
private const val MIN_SECONDARY_SHARE = 0.04f
/** A near-grey has no hue worth tinting the ink with. */
private const val MIN_ACCENT_CHROMA = 0.04f
private const val MIN_ACCENT_L = 0.10f
private const val MAX_ACCENT_L = 0.95f
/** A supporting colour has to be visibly different, not a shade of the lead. */
private const val SECONDARY_MIN_HUE_GAP = 30f

/** Above this the sleeve is bright enough to need dark ink. */
private const val BRIGHT_BACKDROP_LUMINANCE = 0.50f

/** Bright sleeve: deepen the colour onto the backdrop rather than lighten it. */
private const val DARK_INK_DROP = 0.55f
private const val DARK_INK_MIN_L = 0.18f
private const val DARK_INK_MAX_L = 0.34f

/** Dark sleeve: lift the colour clear of the backdrop. */
private const val LIGHT_INK_LIFT = 0.42f
private const val LIGHT_INK_MIN_L = 0.72f
private const val LIGHT_INK_MAX_L = 0.82f

/**
 * A floor on the ink's chroma, so a muted secondary still reads as its colour
 * rather than as grey, and a ceiling so a vivid patch cannot land as neon.
 * The ceiling is generous because OKLCH chroma is capped by the gamut at these
 * lightnesses anyway; the floor is what keeps the ink from going grey.
 */
private const val MIN_INK_CHROMA = 0.09f
private const val MAX_INK_CHROMA = 0.22f

private val DARK_NEUTRAL_INK = Oklch(0.20f, 0f, 0f).toColor()
private val LIGHT_NEUTRAL_INK = Oklch(0.92f, 0f, 0f).toColor()

/** Binary-search steps for the largest in-gamut chroma; far below a JND. */
private const val GAMUT_STEPS = 18
