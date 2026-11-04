package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.theme.stableStatusBarInsets

/**
 * The bar's own height, above whatever inset it is sitting under.
 *
 * The single source of truth for it: the bar lays itself out to this, and
 * everything that has to clear the bar — page content padding, either top
 * backdrop, fixed headers that sit directly beneath it — measures from here
 * rather than from a copy of the number.
 */
val TopBarContentHeight = 52.dp

/**
 * The breathing room between the bar's bottom edge and the first thing under
 * it, so content rests below the glass instead of against it.
 */
val TopBarContentGap = 12.dp

/**
 * How far down the window the bar actually ends: the status bar inset it is
 * pinned under, plus its own height.
 *
 * This has to be read at composition rather than baked in as a constant — the
 * inset is a property of the device and of the window, not of the app. A phone
 * with a cutout, one without, and a freeform window with no status bar at all
 * are all different numbers, and a fixed guess is wrong on all but one of them:
 * too tight and content is clipped under the bar, too loose and every page
 * opens on a band of empty space.
 */
@Composable
fun topBarHeight(): Dp =
    topWindowInset() + TopBarContentHeight

/**
 * The inset the top of the window has to clear.
 *
 * The status bar's own inset, whether it is showing or not.
 *
 * `WindowInsets.statusBars` drops to zero the moment the bar is hidden — see
 * [com.music.bitchord.ui.theme.HideStatusBar] — and a bar pinned to the top of
 * the window would then jump up into the punch-hole camera, and jump back down
 * when the bar came back. [stableStatusBarInsets] reports the same space
 * either way, so the page under it keeps its place.
 */
@Composable
fun topWindowInset(): Dp =
    stableStatusBarInsets().asPaddingValues().calculateTopPadding()

/**
 * Where page content should start: clear of the bar, plus [TopBarContentGap].
 */
@Composable
fun topBarContentPadding(): Dp = topBarHeight() + TopBarContentGap
