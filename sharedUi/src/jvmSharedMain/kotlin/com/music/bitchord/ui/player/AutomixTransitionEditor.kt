package com.music.bitchord.ui.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.settings.AutomixPlanDigest
import com.music.bitchord.data.settings.TrackAnalysisDigest
import com.music.bitchord.playback.smart.AutomixOverride
import com.music.bitchord.playback.smart.BEATS_PER_BAR
import com.music.bitchord.playback.smart.FilterTransitionVariant
import com.music.bitchord.playback.smart.MAX_TRANSITION_BARS
import com.music.bitchord.playback.smart.MIN_TRANSITION_BARS
import com.music.bitchord.playback.smart.TransitionEqShape
import com.music.bitchord.playback.smart.TransitionVolumeShape
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// The window
// ---------------------------------------------------------------------------

/**
 * The slice of the outgoing track the listener has asked the transition to
 * happen in.
 *
 * Two edges, not one, because they answer two different questions: [start] is
 * the handoff — the moment the arriving track becomes audible — and [end] is
 * where the outgoing track is retired. A blend that ends before the outgoing
 * track does cuts its tail off, which is a thing a listener may well want and
 * which the planner will not do on its own.
 */
internal data class AutomixWindow(
    val start: Double,
    val end: Double,
) {
    /** How long the window is. Never negative, however the two edges arrive. */
    val length: Double get() = (end - start).coerceAtLeast(0.0)
}

/** One bar of the outgoing track, in seconds; 0 when it has no usable grid. */
internal fun automixBarSeconds(digest: TrackAnalysisDigest): Double {
    val beat = digest.beatInterval.takeIf { it > 0.0 && it.isFinite() }
        ?: digest.bpm.takeIf { it > 0.0 }?.let { 60.0 / it }
        ?: return 0.0
    return beat * BEATS_PER_BAR
}

/** Whole bars between two points, never negative; 0 when there is no bar length. */
internal fun automixBarsBetween(start: Double, end: Double, barSeconds: Double): Int {
    if (barSeconds <= 0.0 || !barSeconds.isFinite()) return 0
    return ((end - start) / barSeconds).roundToInt().coerceAtLeast(0)
}

/**
 * The window the panel is showing for this pair.
 *
 * Built from the listener's own choice where they have made one, and from the
 * plan where they have not. The two explicit edges are read together because
 * that is what they are: a box the listener drew. A start on its own is still
 * read — the handoff may be placed without touching the exit, which is how
 * the panel has always worked — and an end on its own is filled in backwards
 * from the size.
 */
internal fun automixWindow(
    draft: AutomixOverride,
    current: TrackAnalysisDigest,
    plan: AutomixPlanDigest,
): AutomixWindow {
    val bar = automixBarSeconds(current)
    val contentEnd = current.contentEnd.takeIf { it > 0.0 } ?: 0.0
    // Whether the planner has a window of its own.
    //
    // It has none in two cases: nothing has been planned yet, and the planner
    // refused this pair outright — a spoken-word track, one too short to
    // blend, a pair whose tempi are too far apart to match. The digest says so
    // with zero at both ends.
    val hasPlan = plan.transitionStart > 0.0 && plan.transitionEnd > plan.transitionStart
    // Where the outgoing track is retired: the plan's own exit, and the end of
    // the content when there is no plan.
    val exit = if (hasPlan) plan.transitionEnd else contentEnd
    // Where the overlap opens.
    //
    // With a plan, the plan's own start. Without one, the last few bars of
    // the content — *not* the start of the track.
    //
    // This used to fall back to zero, and the box was then drawn from the
    // start of the song to the end of its content: the whole song, marked as
    // the automix window, for every pair the planner had not (yet) decided.
    // A listener who opened the panel on a track with no plan saw their entire
    // song inside the blend, which is what "putting the entire song in
    // automix" was.
    val plannedStart = when {
        hasPlan -> plan.transitionStart
        contentEnd <= 0.0 -> 0.0
        else -> {
            val length = if (bar > 0.0) bar * MIN_TRANSITION_BARS else DEFAULT_WINDOW_SECONDS
            (contentEnd - length).coerceAtLeast(0.0)
        }
    }
    val plannedEnd = max(plannedStart, exit)
    val chosenStart = draft.transitionStart.takeIf { it > 0.0 }
    val chosenEnd = draft.transitionEnd.takeIf { it > 0.0 }
    return when {
        chosenStart != null && chosenEnd != null -> AutomixWindow(chosenStart, chosenEnd)
        chosenStart != null -> AutomixWindow(chosenStart, max(chosenStart, plannedEnd))
        chosenEnd != null && bar > 0.0 -> {
            val bars = draft.bars.takeIf { it > 0 } ?: plan.bars.takeIf { it > 0 } ?: MIN_TRANSITION_BARS
            AutomixWindow(max(0.0, chosenEnd - bars * bar), chosenEnd)
        }
        else -> AutomixWindow(plannedStart, plannedEnd)
    }
}

/**
 * How long the box is when there is no plan to take its size from and the
 * track has no bar grid to measure one in.
 *
 * The plain crossfade's own length — six seconds — which is what the app
 * falls back to when the planner has nothing to say.
 */
private const val DEFAULT_WINDOW_SECONDS = 6.0

// ---------------------------------------------------------------------------
// Geometry
// ---------------------------------------------------------------------------

/**
 * The slice of the track the drawing shows, opened around a window.
 *
 * Wider than the window itself, always: a box that filled the graph would
 * leave nothing to move it against, and a listener deciding where a blend
 * goes needs to see the music either side of it.
 */
private fun automixFitViewport(window: AutomixWindow, duration: Double): AutomixWindow {
    val length = window.length
    val span = max(MIN_VIEW_SECONDS, length * VIEW_SURROUND)
    var from = window.start - (span - length) / 2.0
    var to = from + span
    if (duration > 0.0) {
        if (span >= duration) {
            from = 0.0
            to = duration
        } else {
            if (from < 0.0) {
                from = 0.0
                to = span
            }
            if (to > duration) {
                to = duration
                from = max(0.0, to - span)
            }
        }
    }
    return AutomixWindow(from, to)
}

/** Whether [window] is entirely inside the view. */
private fun AutomixWindow.contains(inner: AutomixWindow): Boolean {
    if (inner.length <= 0.0) {
        return inner.start >= start - VIEW_SLACK && inner.start <= end + VIEW_SLACK
    }
    return inner.start >= start - VIEW_SLACK && inner.end <= end + VIEW_SLACK
}

/**
 * The bar line nearest [time] on [digest]'s grid, or null when the track has
 * no grid at all.
 *
 * A bar line rather than a beat, because a bar is what a transition is made of:
 * the planner works in whole bars, the product range is whole bars, and the
 * beats between the bar lines are there to be *seen*, not to be landed on.
 * Snapping to every beat, at any ordinary zoom, meant snapping to a few pixels —
 * a drag quantised to distances too small to see, re-lighting its indicator
 * several times a second, which reads as the box being stuck to the dots rather
 * than as a placement being honoured.
 */
internal fun automixSnapToBar(time: Double, digest: TrackAnalysisDigest): Double? {
    val bars = digest.downbeatTimes
    if (bars.isEmpty()) return null
    return bars.minByOrNull { abs(it - time) }
}

/**
 * Where the arriving track's own zero sits on the outgoing track's clock.
 *
 * The arriving track is cued so that the point of it the listener hears first
 * — its cue — lands exactly where the transition begins: the window's start.
 * So the lane's origin is the window's start less the cue, and the lane is
 * drawn from the arriving track's own beginning to its own end.
 *
 * ## Why the cue and not the handoff
 *
 * The handoff is the same fact seen from the other end — the point of the
 * arriving track where the blend ends — and anchoring to it meant the lane's
 * place depended on the window's *length*: move one edge and the lane's
 * position moved with it, which is what "the lower waveform is still stuck to
 * one of the markers" was. The cue says the same thing without the
 * dependency: it is where the arriving track enters, and the window's start
 * is where the transition begins, so the two meet at one point and the lane
 * is free to slide along it.
 *
 * @param windowStart the outgoing moment the transition begins at.
 * @param cue the arriving track's cue, in its own seconds.
 */
internal fun automixIncomingOrigin(windowStart: Double, cue: Double): Double =
    windowStart - max(0.0, cue)

/**
 * A moment of the arriving track, on the outgoing track's clock.
 *
 * [origin] is where its zero sits (see [automixIncomingOrigin]) and [time] is
 * the moment on the arriving track's own clock. Two lanes, one axis.
 */
internal fun automixIncomingAxis(time: Double, origin: Double): Double = origin + time

/**
 * The cue the arriving track is left with after the listener has dragged its
 * lane by [moved] seconds.
 *
 * Dragging the lane right moves the arriving track later on the outgoing
 * clock, which means the material heard at the transition is *earlier* in the
 * arriving track's own timeline: the cue comes down by exactly what the lane
 * went up. It cannot go below zero — a track cannot start before its own
 * beginning — so a drag that would take it there stops at the beginning.
 *
 * @param startedAtCue the cue in force when the gesture began.
 * @param moved how far the lane went, in outgoing seconds; positive is right.
 */
internal fun automixIncomingCue(startedAtCue: Double, moved: Double): Double =
    (startedAtCue - moved).coerceAtLeast(0.0)

/**
 * The shift the planner wants after the listener has dragged the arriving lane
 * by [moved] seconds on top of the [committed] shift already in force.
 *
 * The planner's shift is a *delta*, not an absolute cue: it moves the arriving
 * side — its cue and its handoff together — and leaves the rest of the plan
 * alone. So what is handed up is the total, and a second drag counts the
 * first one once rather than twice.
 */
internal fun automixIncomingShift(committed: Double, moved: Double): Double = committed + moved

// ---------------------------------------------------------------------------
// The editor
// ---------------------------------------------------------------------------

/**
 * The transition editor: the picture the transition is placed with.
 *
 * ## One clock, two lanes
 *
 * Both lanes share the outgoing track's clock. The upper lane is the track
 * that is playing; the lower is the one queued after it, laid on the same
 * axis so that the two can be read against each other. What the listener moves
 * is either a *marker* — the two edges of the orange box, which are the window
 * the transition will happen in — or a *waveform*: dragging the arriving lane
 * moves that track, and dragging the playing lane moves the timeline.
 *
 * ## Nothing is attached to anything
 *
 * A waveform does not follow a marker and a marker does not move a waveform.
 * The arriving lane is anchored once, where the planner put it, and after that
 * only the listener's own drag moves it — which is the whole of the fix for
 * "the lower waveform is still stuck to one of the markers". The window then
 * slides over the two waveforms, and the green handoff line moves with the
 * window because the handoff *is* a moment in the window.
 *
 * ## The size is what the edges measure
 *
 * The bar count is a consequence of where the two edges are, not a property the
 * box carries: pulling an edge out lengthens the transition and pushing it in
 * shortens it, exactly as far as the finger went. Both edges are kept on the
 * planner's own terms — the size inside the four-to-thirty-two-bar range the
 * product works in — and the edges are magnetically drawn to the bar lines the
 * ruler shows, so a placement lands on a bar when it is near one and follows the
 * finger when it is not. The waveforms are deliberately *not* magnetic: lining
 * two tracks up by ear is the listener's business.
 *
 * ## The drawing is the listener's, the hairline is the planner's
 *
 * Every line is drawn from the listener's own choice — the tone shape, the
 * filter ride, the volume pair — so a change of mind is visible at once
 * rather than after the next plan. The plan's own placement is a thin grey
 * hairline next to the box, and it is only drawn when the two disagree: a
 * request the planner could not execute is still shown, beside what it did
 * instead, instead of being quietly rewritten.
 *
 * ## Why the drag state lives here
 *
 * A drag produces a value on every frame. Kept in the panel's own state, each
 * of those frames would recompose the whole sheet and rebuild the plan behind
 * it — which is what made the old drawing lag behind the finger on a cheap
 * phone. Here the drag is local: the drawing reads it directly, nothing above
 * this composable is recomposed while a finger is down, and the finished
 * window is handed up once, on release.
 */
@Composable
internal fun AutomixTransitionEditor(
    current: TrackAnalysisDigest,
    next: TrackAnalysisDigest,
    plan: AutomixPlanDigest,
    draft: AutomixOverride,
    window: AutomixWindow,
    hasNext: Boolean,
    enabled: Boolean,
    resetKey: String,
    onWindow: (AutomixWindow) -> Unit,
    /**
     * The arriving track's shift as the listener moved it, in outgoing seconds;
     * 0 to leave it to the planner.
     *
     * Handed up on release, like the window, and for the same reason: what the
     * listener moved has to reach the engine or the picture is a lie. It is the
     * *arriving waveform* the listener dragged, so what it changes is where
     * that track plays — the cue and the handoff together — and never the
     * window, which is why this is a callback of its own rather than a field on
     * the window.
     *
     * It is the **total** shift, not this drag's own: the planner's field is a
     * delta on its own plan (see [AutomixOverride.incomingCueShift]), so a
     * second drag has to carry the first one with it. Handing up an absolute
     * cue instead is what made the lane snap back to the beginning every time
     * it was moved.
     */
    onCue: (Double) -> Unit = {},
    onSnap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val duration = current.duration.takeIf { it > 0.0 && it.isFinite() } ?: 0.0
    val barSeconds = automixBarSeconds(current)

    // The window being drawn. The panel owns the answer, but a drag is the
    // drawing's own business until the finger comes off: the box follows the
    // finger, not the plan.
    var live by remember(resetKey) { mutableStateOf(window) }
    var pending by remember(resetKey) { mutableStateOf<AutomixWindow?>(null) }
    // The beat the last placement landed on, so the drawing can light it up.
    var snapped by remember(resetKey) { mutableStateOf<Double?>(null) }
    /**
     * Where the arriving track is cued in its own timeline, once the listener
     * has moved it.
     *
     * Null until they do. Until then the lane is the planner's own picture
     * and follows its cue (see [automixIncomingOrigin]); once they have moved
     * it, this is the number the drawing shows *and* the number the engine is
     * given, and it is not re-derived from the plan: the planner re-plans
     * while the analyser refines, and a lane that followed those ticks would
     * undo the move the moment one landed.
     */
    var incomingCue by remember(resetKey) { mutableStateOf<Double?>(null) }
    /**
     * Whether a finger is on the drawing.
     *
     * Read by the sync below, and the reason that sync exists at all: the
     * window the panel hands down is derived from the *plan* whenever the
     * listener has not chosen anything yet, and the planner re-plans while the
     * analyser refines and the playhead moves. Without this flag the plan's
     * own window was adopted on every one of those ticks — including the ones
     * in the middle of a drag — and the box was dragged back out from under
     * the finger several times a second. Which is what "it is so hard to move
     * the box" was.
     */
    var dragging by remember(resetKey) { mutableStateOf(false) }

    // The view. The editor's own, not the panel's: a viewport kept above this
    // composable would change identity on every plan, and a window that moves
    // under a finger is unusable.
    var viewStart by remember(resetKey) { mutableStateOf(0.0) }
    var viewEnd by remember(resetKey) { mutableStateOf(0.0) }
    var viewReady by remember(resetKey) { mutableStateOf(false) }
    var lastLength by remember(resetKey) { mutableStateOf(window.length) }

    val latestDraft by rememberUpdatedState(draft)
    val latestCurrent by rememberUpdatedState(current)
    val latestPlan by rememberUpdatedState(plan)
    val latestWindow by rememberUpdatedState(window)
    val latestOnWindow by rememberUpdatedState(onWindow)
    val latestOnCue by rememberUpdatedState(onCue)
    val latestOnSnap by rememberUpdatedState(onSnap)

    // The panel's window is adopted whenever it says something new. While a
    // commit is in flight the dragged window stays on screen, so the box
    // cannot flicker back to the plan's placement for the frame between the
    // finger coming off and the panel answering.
    //
    // Nothing at all is adopted while a finger is down. That is the whole
    // point: the plan moves on its own, and a box that followed it would be
    // one the listener could not hold on to.
    LaunchedEffect(window.start, window.end) {
        if (dragging) return@LaunchedEffect
        // A plan with no placement yet is not a window, it is the absence of
        // one — the analyser is still refining, or the pair has nothing to mix
        // on this tick. Adopting it emptied the drawing: the box vanished from
        // a panel the listener was working in, and came back on the next open
        // once the planner had something to place. Holding what is already
        // drawn costs a few frames of a stale window, which is a great deal
        // better than a box that is not there.
        if (window.length <= 0.0 && live.length > 0.0) return@LaunchedEffect
        val waiting = pending
        if (waiting == null || waiting.start != window.start || waiting.end != window.end) {
            live = window
        }
        pending = null
    }

    // The listener's own move of the arriving lane, put back when they take it
    // back.
    //
    // The override's shift is the record that the lane is theirs: while it is
    // zero the lane is the planner's picture and follows the plan's cue, and
    // when the listener resets their changes the shift goes to zero and the
    // lane goes back to being the plan's again. Without this the drawing kept
    // the listener's cue after a reset and showed a track entering somewhere
    // the engine was not going to enter it.
    LaunchedEffect(resetKey, draft.incomingCueShift) {
        if (draft.incomingCueShift == 0.0) incomingCue = null
    }

    // The view opens on the window, zooms out when the window grows, and comes
    // back when the window has been moved out of sight. It never moves while a
    // finger is down: the placement is measured against a frozen mapping, and
    // a view that shifted would put the box somewhere the finger is not.
    LaunchedEffect(window.start, window.end, duration, resetKey) {
        // Never while a finger is down, for the same reason the window itself
        // is not adopted then: a view that glided mid-drag would carry the box
        // out from under the finger even though the mapping is frozen.
        if (dragging) return@LaunchedEffect
        val length = window.length
        val resized = abs(length - lastLength) > 0.05
        lastLength = length
        val target = automixFitViewport(window, duration)
        if (!viewReady) {
            viewStart = target.start
            viewEnd = target.end
            viewReady = true
            return@LaunchedEffect
        }
        val visible = AutomixWindow(viewStart, viewEnd).contains(window)
        if (!resized && visible) return@LaunchedEffect
        val fromStart = viewStart
        val fromEnd = viewEnd
        val startedAt = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val progress = ((now - startedAt) / VIEW_GLIDE_MS.toDouble()).coerceIn(0.0, 1.0)
            val eased = FastOutSlowInEasing.transform(progress.toFloat())
            viewStart = fromStart + (target.start - fromStart) * eased
            viewEnd = fromEnd + (target.end - fromEnd) * eased
            if (progress >= 1.0) break
        }
    }

    val liveBar by rememberUpdatedState(barSeconds)
    val liveHasNext by rememberUpdatedState(hasNext)
    val liveDuration by rememberUpdatedState(duration)
    val liveViewStart by rememberUpdatedState(viewStart)
    val liveViewEnd by rememberUpdatedState(viewEnd)

    val graphModifier = if (!enabled || barSeconds <= 0.0) {
        modifier
    } else {
        modifier.pointerInput(resetKey) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val width = size.width.toFloat().coerceAtLeast(1f)
                val height = size.height.toFloat().coerceAtLeast(1f)
                // Frozen for the whole gesture. The view must not move under
                // the finger, or the box would jump away from it.
                val frozenFrom = liveViewStart
                val frozenSpan = (liveViewEnd - liveViewStart).coerceAtLeast(0.001)
                val gap = LANE_GAP.toPx()
                val laneHeight = if (liveHasNext) (height - gap) / 2f else height
                val lowerTop = if (liveHasNext) laneHeight + gap else height
                val bar = liveBar
                val track = latestCurrent
                val opened = live

                fun xOf(time: Double): Float = ((time - frozenFrom) / frozenSpan * width).toFloat()
                fun timeOf(x: Float): Double = frozenFrom + (x / width).coerceIn(0f, 1f) * frozenSpan

                /**
                 * How far the finger has gone from where it went down, in the
                 * timeline's own seconds. Positive is to the right, which is
                 * to the right on the outgoing clock as well.
                 */
                fun movedTo(x: Float): Double = (x - down.position.x) / width * frozenSpan

                val startX = xOf(opened.start)
                val endX = xOf(opened.end)
                val boxLeft = min(startX, endX)
                val boxRight = max(startX, endX)
                val boxWidth = boxRight - boxLeft
                // The handle zone scales down with the box. A whole-bar window
                // at a fast tempo is a narrow rectangle on screen, and a fixed
                // zone ate most of it: on a short transition every touch landed
                // on an edge, so the body of the box — the part that moves it —
                // could not be reached at all. A quarter of the box either side
                // leaves the middle half for moving it, whatever size it is.
                //
                // Outwards the zone is the full width, so a thumb that lands a
                // little past an edge takes hold of that edge rather than
                // jumping the whole box to the touch.
                val handleOut = EDGE_HIT_DP.toPx()
                val handleIn = min(handleOut, boxWidth * 0.25f)
                val inBox = down.position.x >= boxLeft && down.position.x <= boxRight
                // ## What a touch means
                //
                // Three things, and nothing else — no mode, no second finger,
                // no long press:
                //
                //  - **A marker** (the two vertical handles, which run the full
                //    height of the drawing, so either of them is reachable from
                //    either lane) moves that edge of the window. And only that
                //    edge: no waveform follows a marker any more.
                //  - **The arriving lane** — the lower one, which is the track
                //    that is coming — moves *that waveform*, by hand, with no
                //    snapping to anything. Lining two tracks up is the thing a
                //    listener does by ear and by eye, and a magnet that took the
                //    decision away was the "attached to a marker" complaint
                //    wearing a different hat.
                //  - **The playing lane** moves the view: it is the outgoing
                //    track's own clock, so dragging it drags the timeline.
                //
                // The box's body is the one gesture kept from the old model and
                // it now lives on the upper lane only, so that every touch on
                // the arriving lane means "this waveform" and never "the
                // window".
                val grabbed = when {
                    abs(down.position.x - startX) <= if (down.position.x < startX) handleOut else handleIn ->
                        Grab.START_EDGE
                    abs(down.position.x - endX) <= if (down.position.x > endX) handleOut else handleIn ->
                        Grab.END_EDGE
                    // The arriving lane, in the box or out of it.
                    liveHasNext && down.position.y >= lowerTop -> Grab.ARRIVING
                    // Inside the box on the outgoing lane: moving it, at the
                    // size it already has.
                    inBox -> Grab.BODY
                    // Outside it on the outgoing lane: pan the view. That is the
                    // plainest way to look somewhere else on the timeline, and
                    // the one gesture that can move a long way in a single
                    // touch.
                    else -> Grab.VIEW
                }

                var decided = false
                var horizontal = false
                var lastSnap: Double? = null
                // The shift the planner already holds, and the cue in force when
                // this gesture began. The commit is the *total* shift — the one
                // already in force plus this drag's own — so a second drag of
                // the same lane does not count the first one twice.
                val startedAtShift = latestDraft.incomingCueShift
                val startedAtCue = incomingCue ?: max(0.0, latestPlan.cueSeconds)
                // Where the finger was last seen horizontally. Tracked by hand
                // rather than read from [PointerInputChange.positionChanged],
                // which answers false for an event another consumer has already
                // taken: the sheet's own scroll is one, and a drag that had been
                // half-claimed by it would silently stop moving the box.
                var lastX = down.position.x

                // ## The snap is a magnet, not a grid
                //
                // This used to be "wherever the finger is, take the nearest
                // beat". At any ordinary zoom a beat is a handful of pixels
                // apart, so that quantised the whole gesture to distances too
                // small to see, fired a tick on every one of them, and lit a
                // different dot several times a second: the box looked pinned
                // to the dots and the drag rattled.
                //
                // What it is now: the edge follows the finger exactly, and is
                // pulled onto a bar line only when the finger is already close
                // to one. The pull is felt once, when it takes hold, which is
                // what makes a snap readable rather than a rattle.
                val lineSpacingPx = if (bar > 0.0) (bar / frozenSpan * width).toFloat() else 0f
                val magnetPx = min(lineSpacingPx * SNAP_MAGNET_SHARE, SNAP_MAX_DP.toPx())

                fun snap(raw: Double, x: Float): Double {
                    val line = automixSnapToBar(raw, track)
                    if (line != null && magnetPx > 0f && abs(xOf(line) - x) <= magnetPx) {
                        if (lastSnap == null || abs(line - lastSnap!!) > 0.001) {
                            lastSnap = line
                            snapped = line
                            latestOnSnap()
                        }
                        return line
                    }
                    // Left the magnet: the drawing stops claiming a snap at
                    // once, and nothing is announced until the next one takes.
                    lastSnap = null
                    snapped = null
                    return raw
                }

                /** The track's own limits, so nothing can be placed off it. */
                fun ceilingOf(fallback: Double): Double =
                    if (liveDuration > 0.0) liveDuration else fallback

                /**
                 * The window with its start moved and its exit left where it
                 * was: what an edge drag does now.
                 *
                 * The size is a consequence of where the two edges are, so
                 * pulling one edge out lengthens the transition and pushing it
                 * in shortens it. It is kept inside the product range in whole
                 * bars — a size below four bars cannot be executed and one
                 * above thirty-two is not what anyone asked for.
                 */
                fun resizedFrom(to: Double, raw: Double): AutomixWindow {
                    val lower = max(0.0, to - MAX_TRANSITION_BARS * bar)
                    val upper = max(lower, to - MIN_TRANSITION_BARS * bar)
                    return AutomixWindow(raw.coerceIn(lower, upper), to)
                }

                /** The same for the exit edge, with the start held still. */
                fun resizedTo(from: Double, raw: Double): AutomixWindow {
                    val ceiling = ceilingOf(raw)
                    val lower = min(ceiling, from + MIN_TRANSITION_BARS * bar)
                    val upper = max(lower, min(ceiling, from + MAX_TRANSITION_BARS * bar))
                    return AutomixWindow(from, raw.coerceIn(lower, upper))
                }

                /** The whole box moved as one, at the size it already had. */
                fun place(from: Double, to: Double) {
                    val ceiling = ceilingOf(to)
                    val shortest = bar * MIN_TRANSITION_BARS
                    val longest = bar * MAX_TRANSITION_BARS
                    val length = (to - from).coerceIn(shortest, max(shortest, longest))
                    val newFrom = from.coerceIn(0.0, max(0.0, ceiling - length))
                    live = AutomixWindow(newFrom, min(ceiling, newFrom + length))
                }

                fun apply(x: Float) {
                    lastX = x
                    val raw = timeOf(x)
                    when (grabbed) {
                        Grab.START_EDGE ->
                            // The handoff edge follows the finger and the exit
                            // stays put, so pulling this edge back lengthens the
                            // transition and pushing it forward shortens it.
                            live = resizedFrom(to = opened.end, raw = snap(raw, x))
                        Grab.END_EDGE ->
                            // The exit edge, the same way round: outwards is
                            // longer, inwards retires the outgoing track earlier
                            // and cuts its tail off — which the planner will not
                            // do by itself and a listener may well want.
                            live = resizedTo(from = opened.start, raw = snap(raw, x))
                        Grab.BODY -> {
                            // The box travels with the finger, keeping the reach
                            // it was taken hold with, so grabbing it anywhere
                            // moves it by the same amount the finger moved
                            // rather than jumping its edge to the touch.
                            val offset = down.position.x - boxLeft
                            val placed = snap(timeOf(x - offset), x)
                            place(placed, placed + opened.length)
                        }
                        Grab.ARRIVING -> {
                            // The arriving waveform follows the finger, exactly,
                            // in the timeline's own seconds. No magnet: this is
                            // the one gesture whose whole point is to put the
                            // track where the listener wants it rather than
                            // where a grid says.
                            //
                            // The cue comes down by what the lane went up: the
                            // track enters later, so it starts earlier in its
                            // own timeline. That is the same arithmetic the
                            // planner does with the shift it is given, so the
                            // picture and the engine agree.
                            incomingCue = automixIncomingCue(startedAtCue, movedTo(x))
                        }
                        Grab.VIEW -> Unit
                    }
                }

                fun panTo(x: Float) {
                    lastX = x
                    val travel = x - down.position.x
                    var from = frozenFrom - (travel / width) * frozenSpan
                    val span = frozenSpan
                    val length = liveDuration
                    val maxFrom = max(0.0, length - span)
                    if (length > 0.0) from = from.coerceIn(0.0, maxFrom)
                    // Keep the box in sight: a panel about a thing the listener
                    // cannot see any more is not a panel about that thing.
                    val box = live
                    if (length > span && (from > box.start || from + span < box.end)) {
                        from = (if (from > box.start) box.start else box.end - span)
                            .coerceIn(0.0, maxFrom)
                    }
                    viewStart = from
                    viewEnd = from + span
                }

                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        val x = change.position.x
                        if (!decided) {
                            val dx = x - down.position.x
                            val dy = change.position.y - down.position.y
                            if (abs(dx) > viewConfiguration.touchSlop || abs(dy) > viewConfiguration.touchSlop) {
                                decided = true
                                horizontal = abs(dx) >= abs(dy)
                                // Only a horizontal gesture is claimed. A vertical
                                // one belongs to the sheet, which scrolls.
                                if (horizontal) {
                                    change.consume()
                                    dragging = true
                                    if (grabbed == Grab.VIEW) panTo(x) else apply(x)
                                }
                            }
                            continue
                        }
                        if (horizontal && x != lastX) {
                            change.consume()
                            if (grabbed == Grab.VIEW) panTo(x) else apply(x)
                        }
                    }
                } finally {
                    // Nothing at all for a tap: the box moves when it is moved,
                    // and a stray finger on the drawing must not silently put
                    // the handoff somewhere the listener did not choose.
                    if (decided && horizontal) {
                        val finished = live
                        if (finished.start != latestWindow.start || finished.end != latestWindow.end) {
                            pending = finished
                            latestOnWindow(finished)
                        }
                        // The arriving track was moved, so the engine is told
                        // where it was put. Without this the drag was drawing
                        // and nothing else: the waveform sat where the listener
                        // left it and the preview played the planner's own cue.
                        //
                        // What is handed up is the *total* shift — the one the
                        // planner already holds plus this drag's own — because
                        // that is what the planner's field means: a delta on
                        // its own plan, not an absolute cue. Handing it the cue
                        // instead is what made the lane snap back to the
                        // beginning every single time it was moved.
                        if (grabbed == Grab.ARRIVING) {
                            val moved = movedTo(lastX)
                            if (abs(moved) > 0.02) {
                                latestOnCue(automixIncomingShift(startedAtShift, moved))
                            }
                        }
                    }
                    snapped = null
                    // Last, so that the panel's window is allowed to be adopted
                    // again from the next frame on — see the sync above.
                    dragging = false
                }
            }
        }
    }

    Canvas(graphModifier) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas

        val box = live
        val from = viewStart
        val span = (viewEnd - viewStart).coerceAtLeast(0.001)
        fun xOf(time: Double): Float = ((time - from) / span * width).toFloat()

        val gap = LANE_GAP.toPx()
        val laneHeight = if (hasNext) (height - gap) / 2f else height
        val upperTop = 0f
        val lowerTop = if (hasNext) laneHeight + gap else height

        drawLaneGround(upperTop, laneHeight)
        if (hasNext) drawLaneGround(lowerTop, laneHeight)

        val inWindow = box.length > 0.0
        val left = xOf(box.start)
        val right = xOf(box.end)

        // The arriving track's own clock, laid on the outgoing one's. Its cue
        // decides where it sits: the point of the arriving track the listener
        // hears first lands on the moment the transition begins, so the lane's
        // origin is the window's start less the cue — see
        // [automixIncomingOrigin].
        //
        // The cue is the planner's until the listener moves the lane, and then
        // it is theirs: the plan moves on its own while the analyser refines,
        // and a lane that followed it would undo the move the moment a plan
        // tick landed.
        val cue = incomingCue ?: max(0.0, plan.cueSeconds)
        val origin = automixIncomingOrigin(box.start, cue)
        val incomingMap: (Double) -> Double = { t ->
            automixIncomingAxis(time = t, origin = origin)
        }

        drawWaveform(
            digest = current,
            laneTop = upperTop,
            laneHeight = laneHeight,
            from = from,
            span = span,
            mapToAxis = { it },
        )
        if (hasNext) {
            drawWaveform(
                digest = next,
                laneTop = lowerTop,
                laneHeight = laneHeight,
                from = from,
                span = span,
                mapToAxis = incomingMap,
            )
        }

        // The grid the edges land on, as a ruler along the drawing's own edges:
        // see [drawBeatRuler] for why it is there and not down the lane.

        if (inWindow && enabled) {
            // The window itself: a tint over both lanes, so the two waveforms
            // are read through it rather than around it.
            drawRect(
                color = AutomixOrange.copy(alpha = 0.14f),
                topLeft = Offset(left, 0f),
                size = Size(right - left, height),
            )
        }

        // The measured grid, as a ruler along the two outer edges of the
        // drawing: the outgoing track's bars along the top, the arriving
        // track's along the bottom. Drawn over the window tint so the scale
        // stays readable through it.
        drawBeatRuler(current, edgeY = 0f, downward = true, from = from, span = span, mapToAxis = { it }, lit = snapped)
        if (hasNext) {
            drawBeatRuler(next, edgeY = height, downward = false, from = from, span = span, mapToAxis = incomingMap, lit = null)
        }

        // The lines. Every one of them is the listener's own choice, drawn
        // from the draft rather than the plan, so a change of mind shows at
        // once instead of after the next plan comes back.
        if (hasNext && enabled) {
            val shape = effectiveEq(latestDraft, latestPlan)
            if (shape != null && !shape.isFlat) {
                drawToneCurve(shape, lowerTop, laneHeight)
            }
            val filter = effectiveFilter(latestDraft, latestPlan)
            if (filter != null && inWindow) {
                drawFilterRide(filter, left, right, lowerTop, laneHeight)
            }
            val volume = effectiveVolume(latestDraft, latestPlan)
            if (!volume.isPlain && inWindow) {
                drawVolumeCurve(volume, left, right, upperTop, laneHeight, outgoing = true)
                drawVolumeCurve(volume, left, right, lowerTop, laneHeight, outgoing = false)
            }
        }

        // The plan's own placement, when it is not the box that was drawn: a
        // request the planner could not execute is still worth seeing next to
        // what it did instead.
        val planHair = plan.transitionStart
        if (enabled && planHair > 0.0 && inWindow && abs(planHair - box.start) > 0.05) {
            val x = xOf(planHair)
            if (x in 0f..width) {
                drawLine(
                    color = Color.White.copy(alpha = 0.28f),
                    start = Offset(x, 0f),
                    end = Offset(x, height),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }

        if (inWindow && enabled) {
            // The two edges, as handles: drawn last over the lanes so they stay
            // the brightest thing in the drawing, because they are the thing a
            // finger is aimed at.
            drawWindowEdges(left, right, height)
            // The handoff: green, at the window's left edge, because that is the
            // moment the arriving track is heard.
            val handoff = box.start
            val handoffX = xOf(handoff)
            if (handoffX in 0f..width) {
                drawLine(
                    color = AutomixGreen.copy(alpha = 0.22f),
                    start = Offset(handoffX, 0f),
                    end = Offset(handoffX, height),
                    strokeWidth = 7.dp.toPx(),
                )
                drawLine(
                    color = AutomixGreen,
                    start = Offset(handoffX, 0f),
                    end = Offset(handoffX, height),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// What the curves are drawn from
// ---------------------------------------------------------------------------

/** The tone shape in force: the listener's own, or the plan's when they have none. */
private fun effectiveEq(draft: AutomixOverride, plan: AutomixPlanDigest): TransitionEqShape? =
    draft.eq ?: plan.eq.takeIf { it.isNotBlank() }
        ?.let { name -> TransitionEqShape.entries.firstOrNull { it.name == name } }

/** The filter ride in force: the listener's own, or the plan's when they have none. */
private fun effectiveFilter(draft: AutomixOverride, plan: AutomixPlanDigest): FilterTransitionVariant? =
    draft.filter ?: plan.filter.takeIf { it.isNotBlank() }
        ?.let { name -> FilterTransitionVariant.entries.firstOrNull { it.name == name } }

/** The volume pair in force: the listener's own, or the plan's when they have none. */
private fun effectiveVolume(draft: AutomixOverride, plan: AutomixPlanDigest): TransitionVolumeShape =
    draft.volume
        ?: plan.volume.takeIf { it.isNotBlank() }
            ?.let { name -> TransitionVolumeShape.entries.firstOrNull { it.name == name } }
        ?: TransitionVolumeShape.AUTO

// ---------------------------------------------------------------------------
// Drawing
// ---------------------------------------------------------------------------

private fun DrawScope.drawLaneGround(top: Float, height: Float) {
    drawRoundRect(
        color = Color.White.copy(alpha = 0.035f),
        topLeft = Offset(0f, top),
        size = Size(size.width, height),
        cornerRadius = CornerRadius(12.dp.toPx()),
    )
}

/**
 * One track's envelope, as a filled shape with a bright core.
 *
 * ## What it is drawn from
 *
 * The measured *peaks* of the decoded audio: the loudest instant in each
 * short window of the sound. The analyser's energy curve — an RMS average
 * over a quarter of a second — is what the policy reads, and it is the wrong
 * shape for a picture: drawn as it stands it reads as a bar chart, flat-topped
 * blocks on the analyser's own grid, with the transients nowhere in it. That
 * was the "ugly bits".
 *
 * ## Why it is a shape and not a row of strokes
 *
 * One column of strokes per pixel reads as a bar chart however fine the
 * data is, because the gaps between the strokes are the drawing's own. A
 * filled envelope has no gaps in it, and the bright core — the column's
 * average rather than its extreme — is what makes it read as sound rather
 * than as a block.
 *
 * ## Why a gap in the measurement is a gap in the drawing
 *
 * A streamed track is measured in pieces, and the middle of it may be
 * unmeasured for a long time. Drawing the unmeasured stretch as a line down
 * to nothing invents a silence that is not there; so each run of measured
 * columns is drawn as its own shape, and the space between them is left
 * alone.
 */
private fun DrawScope.drawWaveform(
    digest: TrackAnalysisDigest,
    laneTop: Float,
    laneHeight: Float,
    from: Double,
    span: Double,
    mapToAxis: (Double) -> Double,
) {
    val peaks = digest.waveform
    val times = digest.waveformTimes
    if (peaks.isEmpty() || times.isEmpty()) return
    val mid = laneTop + laneHeight / 2f
    val amplitude = laneHeight * 0.46f
    val columns = size.width.toInt().coerceAtLeast(1)
    val tops = FloatArray(columns)
    val bodies = FloatArray(columns)
    val sums = DoubleArray(columns)
    val counts = IntArray(columns)
    for (index in peaks.indices) {
        val time = times.getOrNull(index) ?: continue
        val axis = mapToAxis(time)
        if (axis < from || axis > from + span) continue
        val column = (((axis - from) / span) * columns).toInt().coerceIn(0, columns - 1)
        val value = peaks[index].coerceIn(0f, 1f)
        if (value > tops[column]) tops[column] = value
        sums[column] += value
        counts[column] += 1
    }
    val step = size.width / columns
    var runStart = -1
    for (column in 0..columns) {
        val measured = column < columns && counts[column] > 0
        if (measured && runStart < 0) runStart = column
        if (!measured && runStart >= 0) {
            for (inside in runStart until column) {
                bodies[inside] = (sums[inside] / counts[inside]).toFloat()
            }
            drawWaveformRun(runStart, column - 1, tops, bodies, mid, amplitude, step)
            runStart = -1
        }
    }
}

/**
 * One run of measured columns, as an envelope with a bright core.
 *
 * The outer shape is the column's peak; the inner one is its average. The
 * two together are what makes a waveform look like sound rather than like a
 * bar chart: the outer line is the transient, the inner one is the weight.
 */
private fun DrawScope.drawWaveformRun(
    from: Int,
    to: Int,
    tops: FloatArray,
    bodies: FloatArray,
    mid: Float,
    amplitude: Float,
    step: Float,
) {
    if (to < from) return
    fun xOf(column: Int): Float = column * step + step / 2f

    val envelope = Path()
    for (column in from..to) {
        val x = xOf(column)
        val half = tops[column] * amplitude
        if (column == from) envelope.moveTo(x, mid - half) else envelope.lineTo(x, mid - half)
    }
    for (column in to downTo from) {
        val x = xOf(column)
        envelope.lineTo(x, mid + tops[column] * amplitude)
    }
    envelope.close()
    drawPath(envelope, color = AutomixBlue.copy(alpha = 0.55f))

    val core = Path()
    for (column in from..to) {
        val x = xOf(column)
        val half = bodies[column] * amplitude
        if (column == from) core.moveTo(x, mid - half) else core.lineTo(x, mid - half)
    }
    for (column in to downTo from) {
        val x = xOf(column)
        core.lineTo(x, mid + bodies[column] * amplitude)
    }
    core.close()
    drawPath(core, color = AutomixCream.copy(alpha = 0.92f))
}

/**
 * The measured grid, as a ruler along a lane's own edge.
 *
 * Green, because the grid is what a placement snaps to and the handoff that
 * ends up on it is green too: the two are the same idea seen twice. Downbeats
 * are longer and brighter — they are the bar lines a transition is measured in,
 * and the only ones an edge is pulled onto — and the bar line the last placement
 * landed on is lit outright, so a snap can be confirmed by eye as well as by the
 * tick under the finger.
 *
 * Along the edge rather than down the middle of the lane. Drawn at the centre it
 * sat on top of the waveform and inside the box, where it read as a row of
 * handles — something to be grabbed — rather than as a scale to be read against.
 */
private fun DrawScope.drawBeatRuler(
    digest: TrackAnalysisDigest,
    edgeY: Float,
    downward: Boolean,
    from: Double,
    span: Double,
    mapToAxis: (Double) -> Double,
    lit: Double?,
) {
    val downbeats = digest.downbeatTimes
    if (downbeats.isEmpty()) return
    val beat = digest.beatInterval.takeIf { it > 0.0 && it.isFinite() }
        ?: digest.bpm.takeIf { it > 0.0 }?.let { 60.0 / it }
        ?: return
    val width = size.width
    val last = from + span
    val direction = if (downward) 1f else -1f
    val beatLength = 4.dp.toPx() * direction
    val barLength = 9.dp.toPx() * direction
    for (downbeat in downbeats) {
        for (step in 0 until BEATS_PER_BAR) {
            val time = downbeat + step * beat
            val axis = mapToAxis(time)
            if (axis < from) continue
            if (axis > last) return
            val x = ((axis - from) / span * width).toFloat()
            if (x < 0f || x > width) continue
            val isBar = step == 0
            when {
                lit != null && abs(time - lit) < 0.001 -> {
                    val end = edgeY + barLength * 1.5f
                    drawLine(
                        color = AutomixGreen.copy(alpha = 0.30f),
                        start = Offset(x, edgeY),
                        end = Offset(x, end),
                        strokeWidth = 5.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = Color.White,
                        start = Offset(x, edgeY),
                        end = Offset(x, end),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
                isBar -> drawLine(
                    color = AutomixGreen.copy(alpha = 0.85f),
                    start = Offset(x, edgeY),
                    end = Offset(x, edgeY + barLength),
                    strokeWidth = 1.6.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                else -> drawLine(
                    color = AutomixGreen.copy(alpha = 0.34f),
                    start = Offset(x, edgeY),
                    end = Offset(x, edgeY + beatLength),
                    strokeWidth = 1.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

/** The window's two edges, as the handles a finger is aimed at. */
private fun DrawScope.drawWindowEdges(left: Float, right: Float, height: Float) {
    val stroke = 1.4.dp.toPx()
    drawLine(
        color = AutomixOrange.copy(alpha = 0.9f),
        start = Offset(left, 0f),
        end = Offset(left, height),
        strokeWidth = stroke,
    )
    drawLine(
        color = AutomixOrange.copy(alpha = 0.9f),
        start = Offset(right, 0f),
        end = Offset(right, height),
        strokeWidth = stroke,
    )
    // A grip at the top of each edge: the thing to aim at, and a shape that
    // says "this edge moves on its own".
    val gripWidth = 4.dp.toPx()
    val gripHeight = 16.dp.toPx()
    listOf(left, right).forEach { x ->
        drawRoundRect(
            color = AutomixOrange,
            topLeft = Offset(x - gripWidth / 2f, 0f),
            size = Size(gripWidth, gripHeight),
            cornerRadius = CornerRadius(gripWidth / 2f),
        )
        drawRoundRect(
            color = AutomixOrange,
            topLeft = Offset(x - gripWidth / 2f, height - gripHeight),
            size = Size(gripWidth, gripHeight),
            cornerRadius = CornerRadius(gripWidth / 2f),
        )
    }
}

/**
 * The tone shape on the arriving track, as a frequency response.
 *
 * Drawn the way an equalizer draws one: logarithmic frequency across the
 * lane, gain up and down. It is a shape rather than a placement, so it is not
 * tied to the window's edges — the thing it describes is the arriving track's
 * colour, not a moment in the outgoing one.
 */
private fun DrawScope.drawToneCurve(
    shape: TransitionEqShape,
    laneTop: Float,
    laneHeight: Float,
) {
    val mid = laneTop + laneHeight / 2f
    val amplitude = laneHeight * 0.40f
    val response = shape.responseDb(points = EQ_CURVE_POINTS)
    if (response.size < 2) return
    val path = Path()
    response.forEachIndexed { index, db ->
        val x = index.toFloat() / (response.size - 1) * size.width
        val y = mid - (db / EQ_FULL_SCALE_DB).coerceIn(-1f, 1f) * amplitude
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    // The response sits over the waveform; a soft under-stroke keeps the line
    // legible where it crosses a loud passage.
    drawPath(path, color = AutomixViolet.copy(alpha = 0.22f), style = Stroke(width = 6.dp.toPx()))
    drawPath(path, color = AutomixViolet, style = Stroke(width = 2.dp.toPx()))
}

/**
 * The filter ride, as the ramp it is: closed at the handoff, open by the exit.
 *
 * Drawn across the window rather than across the lane, because unlike the tone
 * shape this *is* a moment in the outgoing track: the sweep is the transition.
 */
private fun DrawScope.drawFilterRide(
    variant: FilterTransitionVariant,
    left: Float,
    right: Float,
    laneTop: Float,
    laneHeight: Float,
) {
    if (right - left < 2f) return
    val mid = laneTop + laneHeight / 2f
    val amplitude = laneHeight * 0.42f
    val path = Path()
    when (variant) {
        FilterTransitionVariant.BASS_HANDOFF -> {
            // A step rather than a ramp: the low end changes hands at a beat.
            val knee = left + (right - left) * FILTER_HANDOFF_AT
            path.moveTo(left, mid + amplitude)
            path.lineTo(knee, mid + amplitude)
            path.lineTo(right, mid - amplitude)
        }
        else -> {
            path.moveTo(left, mid + amplitude)
            path.lineTo(right, mid - amplitude)
        }
    }
    drawPath(path, color = AutomixAmber.copy(alpha = 0.20f), style = Stroke(width = 6.dp.toPx()))
    drawPath(path, color = AutomixAmber, style = Stroke(width = 2.dp.toPx()))
}

/**
 * One side of the volume pair, across the window.
 *
 * The gain is a fader position: full at the top of the lane, silent at the
 * bottom. The outgoing track's curve is drawn over the track that is leaving
 * and the arriving track's over the one that is coming, so the picture reads
 * left to right as the handoff itself does.
 */
private fun DrawScope.drawVolumeCurve(
    shape: TransitionVolumeShape,
    left: Float,
    right: Float,
    laneTop: Float,
    laneHeight: Float,
    outgoing: Boolean,
) {
    val span = right - left
    if (span < 2f) return
    val top = laneTop + laneHeight * 0.14f
    val travel = laneHeight * 0.72f
    val steps = min(VOLUME_CURVE_POINTS, max(2, (span / 3f).toInt()))
    val path = Path()
    for (index in 0 until steps) {
        val fraction = index.toFloat() / (steps - 1)
        val gain = if (outgoing) shape.outgoingGain(fraction) else shape.incomingGain(fraction)
        val x = left + fraction * span
        val y = top + (1f - gain) * travel
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color = AutomixCoral.copy(alpha = 0.20f), style = Stroke(width = 5.dp.toPx()))
    drawPath(path, color = AutomixCoral, style = Stroke(width = 1.8.dp.toPx()))
}

// ---------------------------------------------------------------------------
// Where a drag was taken hold of
// ---------------------------------------------------------------------------

private enum class Grab { START_EDGE, END_EDGE, BODY, ARRIVING, VIEW }

// ---------------------------------------------------------------------------
// Colours and constants
// ---------------------------------------------------------------------------

internal val AutomixInk = Color.White
internal val AutomixInkSecondary = Color.White.copy(alpha = 0.68f)
internal val AutomixInkTertiary = Color.White.copy(alpha = 0.48f)
internal val AutomixInkFaint = Color.White.copy(alpha = 0.30f)

/** Type sitting on the white preset card rather than on the sheet. */
internal val AutomixSheetInk = Color(0xFF141414)

internal val AutomixAmber = Color(0xFFF5A524)

/** The listener's window: the one box in the panel that is a request. */
internal val AutomixOrange = Color(0xFFFF8A3D)
internal val AutomixViolet = Color(0xFFC084FC)
internal val AutomixGreen = Color(0xFF2ECC71)
internal val AutomixBlue = Color(0xFF4C8DFF)
internal val AutomixCream = Color(0xFFF2E8D5)
internal val AutomixCoral = Color(0xFFFF7A6B)

/** The gap between the two lanes. */
private val LANE_GAP = 12.dp

/** The narrowest useful slice of a track to draw and drag in. */
private const val MIN_VIEW_SECONDS = 44.0

/** How much room the view leaves around the window, as a multiple of it. */
private const val VIEW_SURROUND = 2.4

/** How far the view glides when it has to follow the window. */
private const val VIEW_GLIDE_MS = 320

/** Slack on the "is the window visible" test, so a glide does not chase a pixel. */
private const val VIEW_SLACK = 0.01

/**
 * How close to an edge a finger has to land to take hold of it.
 *
 * Wide enough to be a target rather than a pixel hunt: the edge is a hairline,
 * and a listener reaching for it with a thumb should not have to be exact. It is
 * capped against the box's own width in the gesture, so a short window still has
 * a body to grab.
 */
private val EDGE_HIT_DP = 26.dp

/**
 * How far along the gap between two bar lines the magnet reaches, as a share of
 * that gap.
 *
 * Under a half, so there is always travel between one snap and the next: a
 * magnet that reached half way would be a grid again, and a grid is what this
 * replaced.
 */
private const val SNAP_MAGNET_SHARE = 0.34f

/** A ceiling on the magnet, so a heavily zoomed-in view cannot snap from far off. */
private val SNAP_MAX_DP = 14.dp

/** Points across the tone curve; enough for a bell, few enough to stay smooth. */
private const val EQ_CURVE_POINTS = 64

/** The gain that spans half a lane, so a musical shape is not a flat line. */
private const val EQ_FULL_SCALE_DB = 8f

/** Points along a volume curve; more than the eye can tell apart on a phone. */
private const val VOLUME_CURVE_POINTS = 96

/** Where a bass handoff's low end changes hands, as a share of the window. */
private const val FILTER_HANDOFF_AT = 0.5f
