package com.music.bitchord.ui.player

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.music.bitchord.ui.graphics.ColorUtils

/**
 * Ink for the controls laid directly over album artwork.
 *
 * White by default, so every surface outside the expanded player keeps its
 * existing look. The player supplies a light tint of the artwork's own accent
 * here — see [computeNowPlayingPalette].
 *
 * A [State], not a colour: the ink follows a playing clip, so it changes while
 * the player is open. A colour handed down through a composition local changes
 * the whole subtree under it, and this subtree is the entire player — every
 * text and every glyph on it. A state changes only the readers that ask for
 * its value, which is the handful of texts and glyphs that actually wear the
 * ink.
 */
internal val LocalPlayerInk = staticCompositionLocalOf<State<Color>> {
    mutableStateOf(Color.White)
}

/**
 * A soft text shadow, so the ink keeps its edge where it crosses a lighter
 * patch of the artwork. Transparent by default, and transparent in the player
 * whenever the listener has switched glow off.
 *
 * A state, for the reason [LocalPlayerInk] is one.
 */
internal val LocalPlayerInkGlow = staticCompositionLocalOf<State<Color>> {
    mutableStateOf(Color.Transparent)
}

/**
 * The ink as the loudest marks on the screen wear it: the title and the
 * transport.
 *
 * The ink itself is a tint of the record's own colour, and a tint cannot be
 * as bright as white without ceasing to be a tint — pushed to the top of the
 * range it turns into a pale wash with a hue nobody could name. So the two
 * things that should look like light get the ink lifted toward white, and
 * everything else keeps the colour.
 *
 * The reference player writes both of them in white: its title sits at 253 of
 * 255 against the backdrop and its transport glyphs at 255, where this one
 * sat at 228 — a difference of about a tenth of the range, which the eye
 * reads as the difference between white and grey, and the grey is what makes
 * a screen look careful rather than expensive.
 *
 * A state, for the reason [LocalPlayerInk] is one.
 */
internal val LocalPlayerPrimary = staticCompositionLocalOf<State<Color>> {
    mutableStateOf(Color.White)
}

/**
 * How much of the ink survives the lift toward white.
 *
 * Enough that the record is still in it — a sleeve's own colour, pale — and
 * not so much that it is white.
 */
internal const val PRIMARY_INK_KEEP = 0.38f

/** Light ink needs the dark glow, and the reverse. */
internal fun Color.isLightInk(): Boolean =
    ColorUtils.calculateLuminance(toArgb()) >= 0.5f
