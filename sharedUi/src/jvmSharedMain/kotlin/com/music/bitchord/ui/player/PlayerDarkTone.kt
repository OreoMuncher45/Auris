package com.music.bitchord.ui.player

import androidx.compose.ui.graphics.toArgb
import kotlin.math.pow

/*
 * The artwork mesh in dark theme.
 *
 * The mesh keeps the sleeve's own lightness, which is right in light theme and
 * wrong in dark: a pale cover turned the whole player into a pale screen, and
 * the ink resolver then answered it with dark ink and a white grade, so the
 * player read as a light-theme screen. In dark theme each mesh cell's OKLCH
 * lightness above [DARK_MESH_KNEE] is compressed at [DARK_MESH_SLOPE]. Hue is
 * kept, chroma is nudged back up for what the compression greys out, and a
 * cell already at or below the knee is returned untouched, so a dark cover is
 * exactly what it was.
 *
 * Applied to thirty-six cells per read, off the main thread, inside the same
 * pass that averages them — see `meshOf`.
 */

/** Cells at or below this OKLCH lightness are left as they are. */
internal const val DARK_MESH_KNEE = 0.45f

/** How much of the lightness above [DARK_MESH_KNEE] survives. */
internal const val DARK_MESH_SLOPE = 0.25f

/** Chroma put back on a compressed cell; gamut mapping trims any excess. */
internal const val DARK_MESH_CHROMA = 1.3f

/** [argb] as the dark-theme mesh paints it. Pure; safe off the main thread. */
internal fun darkThemeMeshCell(argb: Int): Int {
    val color = Oklch.fromArgb(argb)
    if (color.l <= DARK_MESH_KNEE) return argb
    val lightness = DARK_MESH_KNEE + (color.l - DARK_MESH_KNEE) * DARK_MESH_SLOPE
    return Oklch(lightness, color.chroma * DARK_MESH_CHROMA, color.hue).toColor().toArgb()
}

/** Linear light back to 8-bit sRGB, at 1/4095 steps: a lookup, not a `pow`. */
private val LINEAR_TO_SRGB = IntArray(4096) { step ->
    val c = step / 4095f
    val s = if (c <= 0.0031308f) c * 12.92f else 1.055f * c.toDouble().pow(1.0 / 2.4).toFloat() - 0.055f
    (s * 255f + 0.5f).toInt().coerceIn(0, 255)
}

private fun encode(linear: Float): Int = LINEAR_TO_SRGB[(linear.coerceIn(0f, 1f) * 4095f + 0.5f).toInt()]

/**
 * The full-cover blur behind lyrics and the queue, toned for dark theme in
 * place: the same knee and slope as [darkThemeMeshCell], per pixel.
 *
 * The blur is thousands of pixels and a playing clip re-blurs it several
 * times a second, so this is the cheap form of the same curve: OKLab
 * lightness is read as the cube root of luminance (exact for greys, close
 * for the soft colours a blur leaves), the pixel is scaled in linear light
 * to the compressed lightness, which keeps its hue, and chroma is pushed
 * back out from the grey axis by [DARK_MESH_CHROMA]. Table lookups and one
 * cube root per pixel; pixels at or under the knee are not touched.
 */
internal fun IntArray.darkThemeToned(): IntArray {
    for (i in indices) {
        val pixel = this[i]
        val r = SRGB_TO_LINEAR[(pixel shr 16) and 0xFF]
        val g = SRGB_TO_LINEAR[(pixel shr 8) and 0xFF]
        val b = SRGB_TO_LINEAR[pixel and 0xFF]
        val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val l = Math.cbrt(y.toDouble()).toFloat()
        if (l <= DARK_MESH_KNEE) continue
        val toned = DARK_MESH_KNEE + (l - DARK_MESH_KNEE) * DARK_MESH_SLOPE
        val ratio = toned / l
        val k = ratio * ratio * ratio
        val y2 = y * k
        fun channel(c: Float) = encode(y2 + (c * k - y2) * DARK_MESH_CHROMA)
        this[i] = (pixel and (0xFF shl 24)) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }
    return this
}
