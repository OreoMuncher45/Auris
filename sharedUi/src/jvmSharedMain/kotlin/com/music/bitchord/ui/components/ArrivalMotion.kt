package com.music.bitchord.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** How long a picture takes to settle into the place it was drawn in. */
private const val ARRIVAL_MS = 260

/**
 * How much bigger than its box a picture starts. Small on purpose: this is a
 * settle, not a zoom, and anything larger reads as the page being pushed at the
 * listener rather than as the artwork arriving.
 */
const val ARRIVAL_OVERSHOOT = 0.03f

/**
 * A picture's arrival, as a number from 0 to 1 that the caller draws with.
 *
 * ## What it is for
 *
 * An album's sleeve, a playlist's collage: a page that opens on artwork used to
 * put the whole picture on screen in a single frame. Nothing was wrong with it,
 * and it read as a hard cut — the list was there before the eye had anywhere to
 * start. A quarter of a second of scale and opacity gives the eye that place,
 * and it costs one float: no layout, no re-measure, no second composition.
 *
 * ## Why it is a call and not an overlay
 *
 * Each caller draws with the number however it wants — the detail header scales
 * its photograph, and a page that wants a fade and nothing else passes the
 * overshoot back in as zero. There is no animation composable to nest, so a
 * picture inside a `drawWithContent`, inside a scroll offset, or inside a
 * canvas keeps whatever structure it already had.
 *
 * ## Why it never fights the image loader
 *
 * Coil already fades a bitmap in as it decodes — see `BitChordApplication`'s
 * loader — and that fade is about the *bytes* arriving. This is about the
 * *page* arriving, and it is keyed on the page rather than on the picture, so
 * the two run together rather than one restarting the other: a sleeve that has
 * to be fetched still gets its own crossfade, and a sleeve already in the
 * memory cache still gets its arrival.
 *
 * @param key the page or item the arrival belongs to. A new key is a new
 *   arrival; the same key is not animated a second time.
 * @param enabled false leaves the picture in place with no animation at all —
 *   the caller's own "no motion" answer, whether that is the setting or a
 *   device the app has decided not to spend frames on.
 * @return 0 at the first drawn frame, 1 once the picture has settled.
 */
@Composable
fun rememberArrivalProgress(key: Any?, enabled: Boolean = true): Float {
    // Starts settled whenever there is nothing to animate, so the very first
    // composition draws the picture in its final place rather than flashing
    // from a corner of the box.
    var arrived by remember(key) { mutableStateOf(!enabled) }
    LaunchedEffect(key, enabled) {
        if (enabled) arrived = true
    }
    val progress by animateFloatAsState(
        targetValue = if (arrived) 1f else 0f,
        animationSpec = tween(durationMillis = ARRIVAL_MS, easing = FastOutSlowInEasing),
        label = "picture-arrival",
    )
    return if (enabled) progress else 1f
}
