package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * Whether the liquid glass surfaces are turned on.
 *
 * The phone's own glass is drawn by the vendored backdrop engine and needs
 * Android 12's runtime shaders; the desktop draws the same surfaces with haze.
 * The switch is read by both, so it lives here rather than in either of them.
 */
val LocalLiquidGlassEnabled = staticCompositionLocalOf { false }

/** The hairline along a glass surface's edge. */
val GLASS_EDGE_WIDTH = 0.5.dp

/** What stands in for the glass rim wherever the glass itself is not drawn. */
val GLASS_EDGE_COLOR = Color.White.copy(alpha = 0.10f)

/** How much of the glass tint is laid over what is behind it. */
private const val SURFACE_OPACITY = 0.4f

/**
 * Icon and label colour for content sitting on a glass surface.
 *
 * Glass shows whatever is behind it rather than the theme's surface colour, so
 * the usual onSurface greys have nothing dependable to sit against. Pure black
 * or white off the theme's luminance is the only tint that holds against
 * arbitrary artwork, and it is what Echo's own glass nav bar uses.
 */
@Composable
fun glassContentColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) Color.Black else Color.White

/**
 * Selected-tab indicator colour for a glass surface: the inverse of
 * [glassContentColor] rather than the same tint at lower alpha — white in
 * light theme, black in dark theme, so the pill reads as a shaded scrim
 * rather than as more of the same tint already on the glyph and label.
 */
@Composable
fun glassIndicatorColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) Color.White else Color.Black

/**
 * A lightweight visual match for liquid glass over a stable background.
 *
 * This keeps the same translucent tint and hairline as the full glass surface,
 * but intentionally performs no backdrop capture, blur, lens refraction or
 * shadow rendering. When liquid glass is disabled or unsupported, [fallbackColor]
 * preserves the control's existing filled appearance.
 *
 * The edge is drawn as a gradient rather than by the backdrop engine's specular
 * rim, so a surface over a stable background reads the same on both targets —
 * the phone's own engine is for the surfaces that sample what moves behind them.
 */
@Composable
fun Modifier.lightweightLiquidGlass(
    shape: CornerBasedShape,
    fallbackColor: Color,
): Modifier {
    val useGlass = LocalLiquidGlassEnabled.current && isGlassSupported()
    val glassTint = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) {
        Color(0xFFFAFAFA)
    } else {
        Color(0xFF121212)
    }
    return clip(shape)
        .background(
            color = if (useGlass) glassTint.copy(alpha = SURFACE_OPACITY) else fallbackColor,
            shape = shape,
        )
        .border(
            width = GLASS_EDGE_WIDTH,
            brush = if (useGlass) {
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.05f)),
                )
            } else {
                Brush.verticalGradient(listOf(GLASS_EDGE_COLOR, GLASS_EDGE_COLOR))
            },
            shape = shape,
        )
}
