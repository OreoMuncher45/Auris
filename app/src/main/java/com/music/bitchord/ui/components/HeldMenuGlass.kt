package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// The held menu's material.
//
// It is here rather than in [HeldContextMenu] so that file stays about where
// the menu is placed and this one is about how it looks. Nothing in it knows
// what the menu offers.

/**
 * Whether the held menu gets liquid glass, or the flat plate that stands in for
 * it.
 *
 * The order of the two questions is the same one the app's other floating
 * surfaces ask in, and it is not arbitrary — see [Modifier.liquidGlass]'s
 * note on not reading the toggle itself, and MainActivity's
 * `glassSamplesBackdrop`. "Reduce dynamic blur" is asked *first* because it
 * switches off the recording of the app backdrop as well as the blur: a glass
 * surface composed while nothing is being recorded samples an empty layer, so
 * the menu would read as a hole punched in the page rather than as glass over
 * it. Asking the other way round — glass whenever the toggle is on, plate only
 * where it is off — is what [FloatingBottomBar] and [FrostedTopBar] do, and it
 * is what this follows.
 *
 * The menu is gated on the toggle rather than always glass, unlike
 * [Modifier.liquidGlass] itself: like the nav bar and the floating top
 * controls it is an app surface floating over a page, not chrome that is glass
 * by definition, so the switch has to mean something for it too.
 */
@Composable
internal fun heldMenuUsesGlass(reduceDynamicBlur: Boolean): Boolean =
    LocalLiquidGlassEnabled.current && isGlassSupported() && !reduceDynamicBlur

/**
 * The menu's surface for this frame: liquid glass where the app is already
 * using it, the flat plate everywhere else.
 *
 * Both branches cut to [shape] and so does the hairline, which keeps the
 * edge one curve.
 *
 * Glass is sampled through [Modifier.liquidGlass], so the menu shares the
 * app's pooled backdrop rather than capturing one of its own; a menu drawing
 * its own backdrop would pay a second full-page pass every frame it moved.
 * The hairline is kept on top of the glass deliberately — the glass draws its
 * own refractive rim, but the navbar keeps this edge too so that every floating
 * surface in the app ends in the same line.
 */
@Composable
internal fun Modifier.heldMenuSurface(
    shape: CornerBasedShape,
    plate: Color,
    hairline: Color,
    reduceDynamicBlur: Boolean,
    /** A frost of the surface's own — see [Modifier.liquidGlass]. Null keeps the house blur. */
    blurRadiusDp: Float? = null,
): Modifier {
    val glass = heldMenuUsesGlass(reduceDynamicBlur)
    return this
        .clip(shape)
        .then(if (glass) Modifier.liquidGlass(shape, blurRadiusDp) else Modifier.background(plate, shape))
        .border(0.5.dp, hairline, shape)
}
