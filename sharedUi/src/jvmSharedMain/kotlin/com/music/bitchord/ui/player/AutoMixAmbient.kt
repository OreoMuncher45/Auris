package com.music.bitchord.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.snapshotFlow
import coil3.compose.LocalPlatformContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first

/**
 * Automix's backdrop transition: the next track's backdrop, laid over this
 * one's at the cover reveal's own progress, so the background changes with
 * the artwork rather than on a fade of its own at the track change.
 *
 * Handed out by [AutoMixCoverState.ambientFor] for exactly as long as the
 * cover is drawing a reveal, so it obeys the same switches, the same Canvas
 * rule and the same ending — completion, cancellation, or the listener moving
 * to another song — as the cover. Each backdrop looks its own asset for
 * [incomingUrl] up in its cache; one that is not there yet (the decode lost the
 * race) leaves that backdrop on its ordinary track-change fade.
 *
 * One instance per mix, so effects keyed on it run once at each end. Public
 * only because the public backdrops take it; only the player makes one.
 */
@Stable
class AutoMixAmbientMix internal constructor(
    val generation: Long,
    val incomingUrl: String,
    /** The cover reveal's eased progress, 0 to 1. Read in draw: it moves every frame. */
    val progress: () -> Float,
)

/**
 * Waits until [mix] has the incoming backdrop half on screen, for a backdrop's
 * ink: the chrome is re-judged once, at the halfway point, and not on every
 * frame of the reveal. Reads [AutoMixAmbientMix.progress] in a snapshot flow,
 * so it costs a comparison a frame and no recomposition.
 */
internal suspend fun awaitAutoMixAmbientHalfway(mix: AutoMixAmbientMix) {
    snapshotFlow { mix.progress() >= 0.5f }.first { it }
}

/**
 * The incoming layer of a backdrop's Automix transition, and the part of it
 * that outlives the mix: once the mix lets go, the layer is eased the rest of
 * the way in (the listener is on the new track) or back out (they are not),
 * from wherever the reveal left it, instead of the backdrop starting its
 * ordinary fade from the old track as if the reveal had not happened.
 *
 * [shown] is the alpha last drawn, written by the drawing itself.
 */
internal class AutoMixAmbientLayer {
    private val settle = Animatable(0f)
    private var settling = false
    private val shown = FloatArray(1)

    /** The alpha last drawn. */
    val lastShown: Float get() = shown[0]

    /**
     * The alpha to draw: the mix's own progress while [live], then — between
     * the mix letting go and [settleTo] starting — the alpha last drawn, so
     * there is no frame of a stale value, and [settle]'s from then on.
     * Called from draw.
     */
    fun alpha(live: AutoMixAmbientMix?): Float {
        val settled = settle.value // observed, so the settle's first frame redraws
        val alpha = when {
            live != null -> live.progress()
            settling -> settled
            else -> shown[0]
        }
        shown[0] = alpha
        return alpha
    }

    /** A new mix is taking the layer. */
    fun begin() {
        settling = false
        shown[0] = 0f
    }

    /** Eases the layer to [target] from where it was last drawn, in the share of [fullMillis] left. */
    suspend fun settleTo(target: Float, fullMillis: Int, reduceAnimation: Boolean) {
        val from = shown[0]
        settle.snapTo(from)
        settling = true
        val millis = (kotlin.math.abs(target - from) * fullMillis).toInt()
        if (reduceAnimation || millis <= 0) settle.snapTo(target)
        else settle.animateTo(target, tween(millis, easing = FastOutSlowInEasing))
    }
}

/**
 * Prepares the next cover's backdrops while a mix is armed: the artwork mesh
 * (or the legacy palette) and the full-cover blur, into the caches the
 * backdrops already read, in the theme the player is drawn in. Runs once the
 * incoming cover itself has been decoded — see [AutoMixPreparedCover.ambient]
 * — so it never competes with the cover the reveal needs first. Each is a
 * thumbnail decode and a pass off the main thread, once per mix.
 */
@Composable
internal fun AutoMixAmbientPrefetch(artPx: Int, darkTheme: Boolean, legacyPalette: Boolean) {
    val context = LocalPlatformContext.current
    LaunchedEffect(context, artPx, darkTheme, legacyPalette) {
        snapshotFlow { AutoMixPreparedCover.ambient }.collectLatest { prepared ->
            val url = prepared?.second ?: return@collectLatest
            if (legacyPalette) prepareArtworkPalette(context, url)
            else prepareArtworkMesh(context, url, artPx, darkTheme)
            prepareFullArtworkBlur(context, url, artPx, darkTheme)
        }
    }
}
