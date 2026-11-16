package com.music.bitchord.ui.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.WindowInfo
import com.music.bitchord.ui.components.GlassMotion
import com.music.bitchord.ui.components.MiniPlayerPull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * The spring every open and close runs on: critically damped, so it never
 * bounces, with about 0.45s of response — the way Apple Music's player sheet
 * moves. Unlike a timed ease it has no fast end: it leaves briskly and then
 * spends most of its time settling, slower and slower, into place, which is
 * where the eye is following the artwork home.
 */
private const val SLIDE_STIFFNESS = 195f

/** Close enough to stop, in pixels — rather than a spring's whole long tail. */
private const val SLIDE_SETTLED_PX = 0.5f

/** How long a panel waits to be laid out before an open starts regardless. */
private const val LAYOUT_WAIT_MS = 300L

/**
 * Where the player panel stands, between fully open and docked on the mini
 * player's pill.
 *
 * The player is one panel in the app's own window (see [PlayerPanel]), grown
 * out of the mini player's pill; nothing else moves it. [held] says how far
 * below fully open it is, in pixels, and NaN means simply open. The
 * [PlayerDock] reads [position], so the artwork flies the same way however the
 * player is being moved.
 *
 * Opened with [open] from a tap, pulled up from the pill with [drag] /
 * [release], pulled down from inside with [pullBy] / [releasePull], and closed
 * with [close] — a back press, a page opened from inside the player.
 *
 * Every point where this class moves the panel also stamps [GlassMotion]'s
 * sheet clock, and every point where the motion ends clears it — so the
 * app's glass holds its last backdrop capture for the whole of a swipe
 * instead of re-recording it on every frame of the spring. The stamping is
 * per frame, not per gesture, so however long a motion runs the freeze
 * follows it; see [GlassMotion] for why the clock's own expiry is the
 * guarantee that a cancelled slide can never leave the glass frozen.
 *
 * The panel and the pull down are ported from Zenith
 * (rezapranata089-bit/Zenith, GPL-3.0), a sibling fork of BitChord.
 */
@Stable
class PlayerSheetMotion(
    private val scope: CoroutineScope,
    private val dock: PlayerDock,
    private val shown: () -> Boolean,
    private val setShown: (Boolean) -> Unit,
) : MiniPlayerPull {
    /** NaN while the player simply stands open. */
    var held by mutableFloatStateOf(Float.NaN)
        private set

    /** Whether the player is somewhere other than fully open. */
    val holding: Boolean get() = !held.isNaN()

    // When the current pull started and how far the finger has taken it — for
    // a flick whose own release speed came through as nothing: a short, fast
    // swipe can lift with no speed on its last event at all.
    private var pullStartNanos = 0L
    private var pullStartAt = 0f

    /** Set by the host once the panel has been laid out at all. */
    var laidOut by mutableStateOf(false)

    /**
     * The window, for its height before the sheet has ever been measured.
     * Held rather than its height read off it: read in composition, that
     * height subscribed the app's whole root to window resizes.
     */
    var windowInfo: WindowInfo? = null

    /** How fast a release has to be, in pixels a second, to be a flick. */
    var flingVelocity = 0f

    private var settle: Job? = null

    /**
     * Whether the player is already on its way down. A second close — back
     * pressed twice, say — restarted the slide from a standstill where it
     * stood, a visible stall halfway home.
     */
    private var closing = false

    private fun travel(): Float =
        dock.travel().takeIf { it > 0f } ?: (windowInfo?.containerSize?.height ?: 0).toFloat()

    /** How far below fully open the player is: what [PlayerDock] is told. */
    fun position(): Float = when {
        holding -> held
        shown() && laidOut -> 0f
        else -> Float.NaN
    }

    /** A tap on the mini player. */
    fun open() {
        if (shown()) {
            // Tapped while still on its way down: back up from where it is.
            if (closing && holding) {
                settle?.cancel()
                reopening()
                val from = held
                settle = scope.launch {
                    slide(from = from, to = 0f)
                    held = Float.NaN
                    GlassMotion.sheet.settled()
                }
            }
            return
        }
        settle?.cancel()
        closing = false
        val travel = travel()
        held = travel
        // The raise is motion from its first frame: the docked artwork is
        // placed at the bottom of its travel and the sheet's window comes
        // up over the app's content. Stamped once here because the slide
        // below cannot stamp for its first [LAYOUT_WAIT_MS] — the clock's
        // window is what carries the freeze across that wait.
        GlassMotion.sheet.moved()
        // Raised already open: [held] is what slides it up.
        setShown(true)
        settle = scope.launch {
            // The panel takes a frame or two to be laid out. Started before
            // then, the first stretch of the slide would happen to nothing.
            withTimeoutOrNull(LAYOUT_WAIT_MS) { snapshotFlow { laidOut }.first { it } }
            slide(from = travel, to = 0f)
            held = Float.NaN
            // Open, and still — the glass may capture live again.
            GlassMotion.sheet.settled()
        }
    }

    /** A close caught before it finished: the player is staying up after all. */
    private fun reopening() {
        closing = false
    }

    /** Whatever asked for the player to go — back, a page opened from it — this is how it goes. */
    fun close() {
        if (!shown() || closing) return
        closing = true
        settle?.cancel()
        dismissPull = false
        val from = position().takeUnless { it.isNaN() } ?: run {
            dismissQuietly()
            return
        }
        held = from
        // The slide stamps per frame; this covers the handoff into it.
        GlassMotion.sheet.moved()
        settle = scope.launch {
            slide(from = from, to = travel())
            dismissQuietly()
        }
    }
    override fun drag(delta: Float) {
        if (!holding) {
            // Only upwards starts one, and only from a closed player: a
            // downward drag on the bar has nothing to pull.
            if (delta >= 0f || shown()) return
            settle?.cancel()
            closing = false
            held = travel()
            pullStartAt = held
            pullStartNanos = System.nanoTime()
            setShown(true)
        }
        settle?.cancel()
        held = (held + delta).coerceIn(0f, travel())
        // Every finger movement is motion, stamped as it happens — the
        // glass holds its capture from the first pixel of the pull until
        // [release] hands the sheet to the spring.
        GlassMotion.sheet.moved()
    }

    override fun release(velocity: Float) {
        if (!holding) return
        val travel = travel()
        // The finger's speed as it lifted, or across the whole pull if that
        // was faster — see [pullStartNanos].
        val seconds = (System.nanoTime() - pullStartNanos) / 1e9f
        val average = if (seconds > 0f) (held - pullStartAt) / seconds else 0f
        val speed = if (abs(average) > abs(velocity)) average else velocity
        val flick = abs(speed) >= flingVelocity
        // A flick decides on its own, either way; otherwise it is where the
        // finger left it.
        val open = if (flick) speed < 0f else held < travel / 2f
        if (!open) {
            closing = true
        } else if (closing) {
            // Caught by the mini player on its way down and pulled back up.
            reopening()
        }
        val from = held
        // The spring stamps per frame; this covers the handoff into it.
        GlassMotion.sheet.moved()
        settle = scope.launch {
            // The finger's own speed goes into the spring, so the player
            // carries on as it was moving and then settles — a flick fast,
            // a slow pull gently.
            slide(from = from, to = if (open) 0f else travel, velocity = speed)
            if (open) {
                held = Float.NaN
                // Released open, and still — the glass may capture live
                // again.
                GlassMotion.sheet.settled()
            } else {
                dismissQuietly()
            }
        }
    }

    private var dismissPull = false

    /** Whether a finger is pulling the open player down. */
    val pulling: Boolean get() = dismissPull

    /**
     * A finger pulling the open player down by [delta] pixels (negative: back
     * up). Returns how much of it the player took, for a nested scroll that
     * has to hand the rest on. Only a downward pull starts one.
     */
    fun pullBy(delta: Float): Float {
        if (!shown() || closing) return 0f
        if (!dismissPull) {
            if (delta <= 0f) return 0f
            settle?.cancel()
            dismissPull = true
            if (!holding) held = 0f
            pullStartAt = held
            pullStartNanos = System.nanoTime()
        }
        val before = held
        held = (held + delta).coerceIn(0f, travel())
        GlassMotion.sheet.moved()
        return held - before
    }

    /** The finger lifted off a pull down: it goes home or it goes, as any other pull does. */
    fun releasePull(velocity: Float) {
        if (!dismissPull) return
        dismissPull = false
        release(velocity)
    }

    /** From the host, once the panel has left composition. */
    fun onSheetGone() {
        closing = false
        dismissPull = false
        settle?.cancel()
        held = Float.NaN
        laidOut = false
        // The net under every other clear: whatever ended here — the close
        // finishing, a slide cancelled by the host tearing the panel down
        // mid-gesture, a configuration change — the motion is over and the
        // glass must be allowed to capture again. The clock would lift on
        // its own; this makes the end immediate, and certain even if some
        // future path cancels a settle without starting another motion.
        GlassMotion.sheet.settled()
    }

    private suspend fun slide(from: Float, to: Float, velocity: Float = 0f) {
        val travel = travel()
        animate(
            initialValue = from,
            targetValue = to,
            initialVelocity = velocity,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = SLIDE_STIFFNESS,
                visibilityThreshold = SLIDE_SETTLED_PX,
            ),
        ) { value, _ ->
            // Critically damped never bounces from rest, but thrown hard
            // enough at its target it can pass it once: never past open or
            // past the bar.
            held = value.coerceIn(0f, travel)
            // The spring's own heartbeat: stamped every frame, so the
            // glass freeze lasts exactly as long as the animation runs —
            // however long a hard flick makes it.
            GlassMotion.sheet.moved()
        }
    }

    /** Gone. */
    private fun dismissQuietly() {
        // The pill comes back while the panel is still there to cover for it.
        dock.releasePanel()
        setShown(false)
        // However the close went, the motion is over with it.
        GlassMotion.sheet.settled()
    }
}
