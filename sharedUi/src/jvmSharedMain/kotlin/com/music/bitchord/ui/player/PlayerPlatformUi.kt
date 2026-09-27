package com.music.bitchord.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.drawscope.DrawScope

/** Milliseconds on the clock `SystemClock.uptimeMillis` reads on the phone. */
internal expect fun uptimeMillis(): Long

/** Milliseconds on a monotonic clock that keeps counting through sleep. */
internal expect fun elapsedRealtimeMillis(): Long

/** Whether `Modifier.blur` actually blurs here, rather than being a no-op. */
internal expect val renderEffectBlurSupported: Boolean

/** Holds off the display's idle timeout for as long as [enabled]. */
@Composable
internal expect fun KeepScreenOn(enabled: Boolean)

/** The app's language, as a BCP 47 tag. */
@Composable
internal expect fun appLanguageTag(): String

/**
 * Back, for one of the player's own layers. Call order is priority order: a
 * later call is the one back reaches first while both are enabled. On the
 * desktop, back is Escape.
 */
@Composable
internal expect fun PlayerBackHandler(enabled: Boolean, onBack: () -> Unit)

/**
 * Draws [block] clipped to the rectangle given, moved down by [dy] pixels.
 *
 * On the phone this is a plain clip and translate. Skia on the desktop places
 * glyphs at sub-pixel precision across a line but snaps them to whole pixels
 * down it, so a lyric lifted by a fraction of a pixel stays put and then jumps
 * a whole one — a two-pixel rise becomes three steps. The desktop's version
 * moves the fractional part through a filtered layer instead.
 */
internal expect fun DrawScope.clipShiftedDown(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    dy: Float,
    block: DrawScope.() -> Unit,
)
