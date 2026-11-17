package com.music.bitchord.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/*
 * The artwork's part in a track change: it follows a sideways drag, leaves in
 * the direction the queue moved, and the next track's cover comes in from the
 * other side. One transition for every change — a swipe, the transport, the
 * queue running on by itself — so the listener sees the same thing however the
 * song moved on.
 *
 * Direction is in queue terms throughout: +1 is the next track, -1 the
 * previous one. The art moves the opposite way on screen, as a page does: to
 * the left for the next track.
 */

/** Which way a released drag skips: +1 next, -1 previous, 0 not at all. */
internal fun trackSwipeDirection(total: Float, velocity: Float, threshold: Float, flingVelocity: Float): Int = when {
    total <= -threshold -> 1
    total >= threshold -> -1
    // A quick flick that never got as far: the same skip, as long as it was
    // still travelling the way it had been dragged when the finger came up.
    total != 0f && abs(velocity) >= flingVelocity && sign(velocity) == sign(total) -> if (total < 0f) 1 else -1
    else -> 0
}

/**
 * The release's speed, or none if the finger had stopped before it lifted.
 *
 * The tracker only hears sideways moves, and with none after a pause it
 * still reports the speed of the last one: a quick drag held still and then
 * let go would read as a flick. [STOPPED_AFTER_MS] matches Compose's own
 * cut-off for a pointer that has stopped.
 */
internal fun releaseVelocity(velocity: Float, sinceLastMoveMs: Long): Float =
    if (sinceLastMoveMs > STOPPED_AFTER_MS) 0f else velocity

/**
 * Where the card is drawn for a finger [total] pixels along: with it, one to
 * one, up to [knee], and then giving way, so the card still answers past the
 * point where a release would skip without running off with the finger.
 */
internal fun cardSwipeOffset(total: Float, knee: Float): Float {
    val distance = abs(total)
    if (distance <= knee) return total
    return sign(total) * (knee + (distance - knee) * CARD_PAST_KNEE)
}

/**
 * Where the full-bleed picture is drawn for a finger [total] pixels along: a
 * fraction of the way, and never further than the banner's zoom leaves room
 * for. The banner is the width of the screen, so anything more would open a
 * strip of bare backdrop down one edge.
 */
internal fun bannerSwipeOffset(total: Float, width: Float): Float {
    val slack = bannerSlack(width)
    return (total * BANNER_FOLLOW).coerceIn(-slack, slack)
}

/** How far the banner's picture can move while its zoom still covers both edges. */
internal fun bannerSlack(width: Float): Float = width * (BANNER_ZOOM - 1f) / 2f

/**
 * The zoom that keeps a banner picture moved by [offset] covering its frame —
 * one at rest, never more than [BANNER_ZOOM].
 */
internal fun bannerZoomFor(offset: Float, width: Float): Float {
    if (width <= 0f) return 1f
    return 1f + 2f * min(abs(offset), bannerSlack(width)) / width
}

/**
 * How much of the banner's frame a picture moved by [offset] still covers, as
 * the opacity it may be drawn at: whole within [bannerSlack], falling to
 * nothing at the farthest a track change takes it.
 */
internal fun bannerCoverage(offset: Float, width: Float): Float {
    if (width <= 0f) return 1f
    val slack = bannerSlack(width)
    val farthest = width * BANNER_EXIT_FRACTION
    return ((farthest - abs(offset)) / (farthest - slack)).coerceIn(0f, 1f)
}

/**
 * Which way a change the listener didn't ask for here moved: what a swipe or a
 * transport button said, if it said so within [TRACK_HINT_TTL_MS]; otherwise
 * where the new track sits in the queue against the old one. A change that
 * can't be placed — a new queue — reads as moving on.
 */
internal fun trackChangeDirection(
    hint: Int,
    hintAgeMs: Long,
    oldIndex: Int,
    newIndex: Int,
): Int = when {
    hint != 0 && hintAgeMs in 0..TRACK_HINT_TTL_MS -> hint
    oldIndex >= 0 && newIndex >= 0 && newIndex < oldIndex -> -1
    else -> 1
}

/**
 * The artwork's track-change motion, and the cover it is allowed to show.
 *
 * Every value the sleeve draws with is read only inside graphics layers, so
 * none of this recomposes anything frame to frame: a drag writes a float, an
 * animation steps an [Animatable], and the layers re-run their blocks. The one
 * composition-visible value is [shownKey], written once per track change.
 */
@Stable
internal class TrackChangeMotion(initialKey: String, private val scope: CoroutineScope) {
    // ---- What the screen tells it, refreshed every composition. Plain fields:
    // nothing here is drawn from, only read when something starts.
    /** The window's width: how far "off screen" is, and the banner's width. */
    var widthPx = 0f
    /** Whether the sleeve is the full-bleed banner rather than the card. */
    var banner = false
    /** Whether the listener has asked for less motion. */
    var reduced = false
    /** Whether the player is open and still enough for the art to move at all. */
    var canAnimate: () -> Boolean = { true }
    /** Whether the new track's cover has decoded. */
    var coverLoaded: () -> Boolean = { true }
    /** Whether there is a next track for a swipe to go to. */
    var hasNext = true

    // ---- What the layers draw with.
    private var dragging by mutableStateOf(false)
    private val dragOffset = mutableFloatStateOf(0f)
    private val dragHint = mutableFloatStateOf(0f)
    private val settle = Animatable(0f)
    private val fade = Animatable(1f)
    private val hintSettle = Animatable(0f)

    /** Sideways offset of the art, in pixels. */
    fun offset(): Float = if (dragging) dragOffset.floatValue else settle.value

    /**
     * The art's opacity. In the banner, never whole while the picture is
     * further out than its zoom covers: past that the frame's edge is bare,
     * and the backdrop may show there only through a picture still fading.
     */
    fun alpha(): Float {
        val alpha = fade.value
        return if (banner) alpha * bannerCoverage(offset(), widthPx) else alpha
    }

    /** How far along a skip the drag is, signed: past ±1 a release skips. */
    fun hint(): Float = if (dragging) dragHint.floatValue else hintSettle.value

    // ---- The cover on screen.
    /**
     * The track whose cover the sleeve may show. Behind the playing track
     * while the old cover is still on its way out, so the outgoing art is the
     * one leaving rather than the new one blinking in and leaving in its place.
     */
    var shownKey by mutableStateOf(initialKey)
        private set
    private var lastShown: PlayerArtwork? = null

    /**
     * The cover the sleeve draws for [key]: the live one once [shownKey] has
     * caught up with it, and the last one drawn until then.
     */
    fun sleeveArt(key: String, live: PlayerArtwork): PlayerArtwork {
        if (key == shownKey || lastShown == null) {
            lastShown = live
            return live
        }
        return lastShown ?: live
    }

    // ---- Bookkeeping.
    private var lastKey = initialKey
    private var lastIndex = -1
    private var hintDir = 0
    private var hintAtMs = 0L
    private var exitDir = 0
    private var exitJob: Job? = null
    private var waitJob: Job? = null
    private var job: Job? = null

    /** A transport button asked for [direction]; the change that follows goes that way. */
    fun expect(direction: Int) {
        hintDir = direction
        hintAtMs = nowMs()
    }

    // ---- The drag.
    private var grabbedAt = 0f

    fun dragStart() {
        cancelAll()
        // Caught on its way back: from where it is. Caught off screen, on its
        // way out or in: from the middle, where the finger expects it.
        grabbedAt = settle.value.takeIf { abs(it) <= widthPx * CARD_KNEE } ?: 0f
        dragOffset.floatValue = grabbedAt
        dragHint.floatValue = 0f
        dragging = true
    }

    /** The finger is [total] pixels from where it went down; [threshold] is a skip. */
    fun drag(total: Float, threshold: Float) {
        dragOffset.floatValue = when {
            banner -> if (reduced) 0f else bannerSwipeOffset(total, widthPx)
            reduced -> grabbedAt + total * REDUCED_FOLLOW
            else -> grabbedAt + cardSwipeOffset(total, widthPx * CARD_KNEE)
        }
        if (threshold > 0f) dragHint.floatValue = total / threshold
    }

    /** Released short of a skip, or cancelled: back to the middle, track unchanged. */
    fun springBack() {
        val from = endDrag()
        job = scope.launch {
            settle.snapTo(from)
            if (reduced) {
                settle.animateTo(0f, tween(REDUCED_RETURN_MS))
            } else {
                settle.animateTo(0f, spring(SPRING_BACK_DAMPING, ART_STIFFNESS))
            }
        }
    }

    /**
     * Released past a skip towards [direction]. The art keeps going and leaves;
     * the track change that follows brings the next cover in. If none comes —
     * previous restarted the song instead — the art comes back the way it left.
     */
    fun commit(direction: Int) {
        val from = endDrag()
        expect(direction)
        exitDir = direction
        val exit = scope.launch {
            settle.snapTo(from)
            if (reduced) {
                settle.animateTo(0f, tween(REDUCED_RETURN_MS))
            } else {
                exitTo(direction)
            }
        }
        exitJob = exit
        waitJob = scope.launch {
            exit.join()
            delay(COMMIT_WAIT_MS)
            // No change came: the same song, back from the side it left by.
            exitDir = 0
            if (!reduced) enter(fromDirection = -direction)
        }
    }

    private fun endDrag(): Float {
        val from = dragOffset.floatValue
        val hintFrom = dragHint.floatValue
        dragging = false
        scope.launch {
            hintSettle.snapTo(hintFrom)
            hintSettle.animateTo(0f, tween(HINT_FADE_MS))
        }
        return from
    }

    // ---- The change itself.
    /** The playing track is now [key], at [index] in the queue. */
    fun onTrackChanged(key: String, index: Int) {
        if (key == lastKey) {
            lastIndex = index
            return
        }
        val direction = if (exitDir != 0) {
            exitDir
        } else {
            trackChangeDirection(hintDir, nowMs() - hintAtMs, lastIndex, index)
        }
        lastKey = key
        lastIndex = index
        hintDir = 0
        waitJob?.cancel()
        job?.cancel()

        if (!canAnimate() || dragging) {
            // Nothing to watch: closed, collapsed, or under the finger. The
            // cover just changes; a drag in progress carries on with it.
            exitJob?.cancel()
            exitDir = 0
            shownKey = key
            if (!dragging) scope.launch { snapRest() }
            return
        }

        val exiting = exitJob?.takeIf { exitDir != 0 }
        exitDir = 0
        job = scope.launch {
            if (reduced) {
                // A dip through the backdrop rather than a slide: one picture
                // at a time, nothing moving sideways.
                exiting?.join()
                settle.snapTo(0f)
                fade.animateTo(0f, tween(REDUCED_FADE_MS, easing = LinearEasing))
                shownKey = key
                awaitCover()
                fade.animateTo(1f, tween(REDUCED_FADE_MS, easing = LinearEasing))
                return@launch
            }
            if (exiting != null) exiting.join() else exitTo(direction)
            shownKey = key
            awaitCover()
            enter(fromDirection = direction)
        }
    }

    private suspend fun exitTo(direction: Int) {
        if (banner) {
            val target = -direction * widthPx * BANNER_EXIT_FRACTION
            // Children of the caller, so cancelling the change stops both.
            coroutineScope {
                launch { fade.animateTo(0f, tween(EXIT_MS, easing = FastOutLinearInEasing)) }
                settle.animateTo(target, tween(EXIT_MS, easing = FastOutLinearInEasing))
            }
        } else {
            settle.animateTo(-direction * widthPx, tween(EXIT_MS, easing = FastOutLinearInEasing))
        }
    }

    /** In from the side [fromDirection] points away from: the next cover from the right. */
    private suspend fun enter(fromDirection: Int) {
        val start = fromDirection * widthPx * (if (banner) BANNER_EXIT_FRACTION else ENTER_FRACTION)
        settle.snapTo(start)
        fade.snapTo(0f)
        coroutineScope {
            launch { fade.animateTo(1f, tween(ENTER_FADE_MS, easing = LinearEasing)) }
            settle.animateTo(0f, spring(ENTER_DAMPING, ART_STIFFNESS))
        }
    }

    /** Until the new cover is ready to be seen, or long enough that the placeholder will do. */
    private suspend fun awaitCover() {
        withTimeoutOrNull(COVER_WAIT_MS) { snapshotFlow { coverLoaded() }.first { it } }
    }

    private suspend fun snapRest() {
        settle.snapTo(0f)
        fade.snapTo(1f)
    }

    private fun cancelAll() {
        job?.cancel()
        waitJob?.cancel()
        exitJob?.cancel()
        exitDir = 0
        if (fade.value < 1f || shownKey != lastKey) {
            shownKey = lastKey
            scope.launch { fade.snapTo(1f) }
        }
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L
}

@Composable
internal fun rememberTrackChangeMotion(initialKey: String): TrackChangeMotion {
    val scope = rememberCoroutineScope()
    return remember { TrackChangeMotion(initialKey, scope) }
}

/** Past this, a drag on the card gives way: a little under half the card. */
internal const val CARD_KNEE = 0.4f
/** How much of the finger's travel past [CARD_KNEE] the card still takes. */
internal const val CARD_PAST_KNEE = 0.3f
/** How much of the finger's travel the drag takes with Reduce animation on. */
internal const val REDUCED_FOLLOW = 0.35f
/** The banner's zoom while its picture moves, which is what gives it room to. */
internal const val BANNER_ZOOM = 1.04f
/** How much of the finger's travel the banner's picture takes. */
internal const val BANNER_FOLLOW = 0.15f
/** How far the banner's picture travels as it fades out, and comes in from. */
internal const val BANNER_EXIT_FRACTION = 0.12f
/** Where the card comes in from, as a share of the window: about 0.4 of the card. */
internal const val ENTER_FRACTION = 0.35f

/** A release this fast, in dp per second, skips however short the drag. */
internal const val SWIPE_FLING_DP_PER_S = 1000f
/** A finger still this long before it lifts had stopped: no flick. */
internal const val STOPPED_AFTER_MS = 40L
/** How long a swipe or button's word on which way the change went is trusted. */
internal const val TRACK_HINT_TTL_MS = 1_000L
/** How long a swipe that left waits for the change before coming back. */
internal const val COMMIT_WAIT_MS = 500L
/** How long the new cover is waited for before coming in on the placeholder. */
internal const val COVER_WAIT_MS = 300L

private const val EXIT_MS = 180
private const val ENTER_FADE_MS = 160
private const val HINT_FADE_MS = 150
private const val REDUCED_RETURN_MS = 150
/** Each half of Reduce animation's dip: out, then the new cover in. */
private const val REDUCED_FADE_MS = 110
private const val ENTER_DAMPING = 0.85f
private const val SPRING_BACK_DAMPING = 0.75f
private const val ART_STIFFNESS = 400f
