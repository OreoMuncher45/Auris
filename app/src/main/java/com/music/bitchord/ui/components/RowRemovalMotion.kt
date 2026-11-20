package com.music.bitchord.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset

/**
 * How a track row leaves a list the listener removed it from — History, the
 * Cached songs folder: it fades out, and the rows below close the gap.
 *
 * Handed to `LazyItemScope.animateItem`, which applies the fade as a layer
 * alpha and the slide as a placement offset, neither of which relays the list
 * out per frame. Constants, so nothing is allocated per removal or per frame.
 */
internal val ROW_REMOVAL_FADE_OUT: FiniteAnimationSpec<Float> =
    tween(durationMillis = 150, easing = FastOutLinearInEasing)

/** The rows below a removed one sliding up into its place; emphasized-decelerate. */
internal val ROW_REMOVAL_PLACEMENT: FiniteAnimationSpec<IntOffset> =
    tween(durationMillis = 220, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
