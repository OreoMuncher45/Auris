package com.music.bitchord.ui.components

import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * The run the fade needs below the bar to get from full blur to none without
 * the eye finding where it got there.
 */
private val FADE_RUN = 120.dp

/**
 * How much blur the fade reaches at its outer edge — short of all of it.
 *
 * The last quarter buys almost nothing visually and costs the most: a blur has
 * nothing to sample past the edge of its own layer, so the harder it is pushed
 * there the more of the layer is flat material colour rather than blurred
 * content, and the more that edge reads as a band of colour laid over the page.
 * Stopping at three quarters keeps the ramp and loses the band.
 */
private const val PEAK = 0.75f

/**
 * [BottomFadeBlur] the other way up: full blur along the top edge, ramping to
 * nothing on the way down.
 *
 * For pages whose own artwork runs up under the status bar. A bar that carries
 * a uniform pane of glass is a rectangle sitting on the picture, and its bottom
 * edge is a line across it — the same line this page spends its whole effort
 * removing further down. Fading the glass out instead leaves the back arrow
 * something to be legible against and the artwork nothing to be interrupted by.
 *
 * The bar itself paints no glass of its own on those pages; this is the whole
 * of it. See `ownBackdrop` on [FrostedTopBar].
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun TopFadeBlur(
    hazeState: HazeState,
    /**
     * The colour of the page behind this — a detail page's artwork wash. See
     * the effect below for why it cannot just be the theme's background.
     */
    pageColor: Color,
    modifier: Modifier = Modifier,
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    // The bar fills itself solid instead when blur is reduced, so this has
    // nothing left to do.
    if (reduceDynamicBlur) return

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(topBarHeight() + FADE_RUN)
            .hazeEffect(
                state = hazeState,
                // Keyed to the colour of the page underneath, not the theme's
                // — for the reason set out at length in [BottomFadeBlur], and
                // more sharply here. A blur has nothing to sample past the top
                // of its own layer, so the first blur-radius of this strip is
                // barely covered by blurred content and shows mostly the flat
                // colour of the material instead. Given the theme's near-black
                // background, that is a black bar spreading unevenly down into
                // the artwork: the exact artefact this was added to remove.
                style = HazeMaterials.ultraThin(pageColor),
            ) {
                // Cubic rather than haze's quadratic, and eased out rather than
                // in: the ramp falls away quickly under the bar and then spends
                // the rest of its run near nothing, which is what hides where
                // the layer ends. The mirror of the bottom fade's arrival.
                progressive = HazeProgressive.verticalGradient(
                    easing = EaseOutCubic,
                    startIntensity = PEAK,
                    endIntensity = 0f,
                )
                // Uniform across the layer, so it would show as texture over
                // the untouched foot of the ramp — the edge being hidden.
                noiseFactor = 0f
            },
    )
}
