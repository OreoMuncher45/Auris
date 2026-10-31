package com.music.bitchord.ui.components

import androidx.compose.ui.Modifier
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeEffectScope
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect

/**
 * Keeps Haze's visual style while allowing it to reduce its sampling resolution.
 * Haze 1.x otherwise processes every effect at full resolution, even when a
 * large blur makes those extra source pixels invisible.
 *
 * A third rather than [HazeInputScale.Auto]. Auto only steps the resolution down
 * once the blur radius is large enough for it to be confident, which left the
 * mini player and the nav bar sampling at full resolution on every frame of
 * every scroll. A third is what the liquid glass surfaces already sample at, and
 * what [com.music.bitchord.ui.components.TopFadeBlur] was moved to for the same
 * reason: the blur is what hides the upscale, so the pixels that were being paid
 * for could not be seen either way. [block] can still override it per call site.
 */
@OptIn(ExperimentalHazeApi::class)
fun Modifier.optimizedHazeEffect(
    state: HazeState,
    style: HazeStyle = HazeStyle.Unspecified,
    block: (HazeEffectScope.() -> Unit)? = null,
): Modifier = hazeEffect(state, style) {
    inputScale = HazeInputScale.Fixed(0.33f)
    block?.invoke(this)
}

/**
 * The frost of a menu over something that keeps moving under it: the player's
 * full-screen Spotify Canvas, or a live menu that opens over it. Sampled at a
 * tenth: it is re-blurred on every frame of the video, and under a blur this
 * wide and a slab this opaque, more pixels than that are not there to be seen.
 *
 * The style is the caller's, so this changes what is sampled and not what the
 * menu looks like.
 */
@OptIn(ExperimentalHazeApi::class)
fun Modifier.liveMenuFrost(
    state: HazeState,
    style: HazeStyle = HazeStyle.Unspecified,
): Modifier = optimizedHazeEffect(state = state, style = style) {
    inputScale = HazeInputScale.Fixed(LIVE_FROST_INPUT_SCALE)
}

private const val LIVE_FROST_INPUT_SCALE = 0.10f
