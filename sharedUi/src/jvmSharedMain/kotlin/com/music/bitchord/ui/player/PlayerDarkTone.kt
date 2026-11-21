package com.music.bitchord.ui.player

import androidx.compose.ui.graphics.toArgb

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
