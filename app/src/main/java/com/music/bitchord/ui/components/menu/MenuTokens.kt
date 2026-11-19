package com.music.bitchord.ui.components.menu

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings

// The one set of numbers every menu, sheet and dialog in the app is drawn
// with. A menu that wants a different radius, padding or spring is a menu that
// has stopped looking like the others, so these live here and nowhere else.
//
// The idea of one panel, one row and one set of motion values across every
// pop-up is taken from Zenith (rezapranata089-bit/Zenith, GPL-3.0), a sibling
// fork of BitChord; the values are Auris's own.

/** Sizes shared by every menu surface and row. */
internal object MenuTokens {
    /** The anchored menu's corners — the same curve the held menu uses. */
    val Radius = 22.dp

    /** The least an anchored menu keeps between itself and the host's safe edges. */
    val Margin = 12.dp

    /** Between a button and the menu hung off it. */
    val Gap = 6.dp

    /** Room below a button that is enough to open downwards whatever is above. */
    val MinRoom = 220.dp

    /** How wide an anchored menu wants to be; never wider than the host. */
    val Width = 300.dp

    /**
     * The flat plate's opacity under a menu over a page, where glass is off.
     * The plate's usual strength is for artwork, whose colour shows through
     * as a tint; rows of text show through as words, and have to be all but
     * covered for the menu's own to read.
     */
    const val PagePlateAlpha = 0.96f

    /** Above the first row and below the last, inside the panel. */
    val PanelPadding = 6.dp

    val DividerThickness = 0.5.dp

    /** A menu divider's inset from the panel's sides. */
    val DividerInset = 14.dp

    /** Rendered icon size: one per variant, the glyphs themselves untouched. */
    val MenuIcon = 20.dp
    val SheetIcon = 22.dp

    /** Between a row's icon and its label. */
    val IconGap = 18.dp

    val MenuRowMinHeight = 40.dp
    val MenuRowPaddingH = 14.dp
    val MenuRowPaddingV = 8.dp

    val SheetRowPaddingH = 22.dp
    val SheetRowPaddingV = 15.dp
}

/** A menu panel's flat colours; the glass, where it is on, is drawn over none of them. */
@Immutable
internal data class MenuColors(
    /** The panel's fill when it is not glass. */
    val plate: Color,
    /** The edge every floating surface in the app ends in. */
    val hairline: Color,
    /** The page behind an open menu, pushed back. */
    val scrim: Color,
)

internal fun menuColors(dark: Boolean, reduceDynamicBlur: Boolean): MenuColors = MenuColors(
    plate = (if (dark) Color.Black else Color.White).copy(
        alpha = if (reduceDynamicBlur) 0.85f else if (dark) 0.78f else 0.85f,
    ),
    hairline = if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.06f),
    scrim = Color.Black.copy(alpha = if (dark) 0.30f else 0.10f),
)

/**
 * How every menu moves. Driven through `graphicsLayer` only, so a running
 * animation never recomposes or relayouts what it moves.
 */
internal object MenuMotion {
    /** The panel grows out of its button and settles, without the bounce a lift has. */
    fun open(reduceMotion: Boolean): AnimationSpec<Float> =
        if (reduceMotion) {
            tween(durationMillis = 140, easing = LinearEasing)
        } else {
            spring(dampingRatio = 0.84f, stiffness = 340f, visibilityThreshold = 0.002f)
        }

    /** Back into the button, critically damped and quicker than the way out. */
    fun close(reduceMotion: Boolean): AnimationSpec<Float> =
        if (reduceMotion) {
            tween(durationMillis = 100, easing = LinearEasing)
        } else {
            spring(dampingRatio = 1f, stiffness = 480f, visibilityThreshold = 0.002f)
        }

    /** How far below full size the panel starts. */
    const val START_SCALE = 0.90f

    /** How far, in px, the panel travels towards its button as it grows. */
    const val TRAVEL_PX = 10f
}

/**
 * Whether menus should skip their springs: the app's "reduce animation", or
 * animations switched off in the system's developer options.
 */
@Composable
internal fun rememberReduceMenuMotion(): Boolean {
    val context = LocalContext.current
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val systemAnimationsOff = remember(context) {
        runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }
    return reduceAnimation || systemAnimationsOff
}
