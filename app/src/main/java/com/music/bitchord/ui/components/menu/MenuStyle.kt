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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings

// How every menu is coloured and moves on Android. The sizes are MenuTokens,
// in the shared module; these read the app's settings, so they stay here.

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
