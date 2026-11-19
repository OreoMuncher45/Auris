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

/** Light ink needs the dark glow, and the reverse. */
internal fun Color.isLightInk(): Boolean =
    ColorUtils.calculateLuminance(toArgb()) >= 0.5f
