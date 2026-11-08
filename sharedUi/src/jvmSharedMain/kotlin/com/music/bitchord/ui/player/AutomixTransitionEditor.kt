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
import androidx.compose.ui.input.pointer.positionChanged
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
    val plannedStart = plan.transitionStart.takeIf { it > 0.0 } ?: 0.0
    val plannedEnd = plan.transitionEnd.takeIf { it > plannedStart }
        ?: current.contentEnd.takeIf { it > plannedStart }
        ?: plannedStart
    val bar = automixBarSeconds(current)
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
 * The beat nearest [time] on [digest]'s grid, or null when the track has no
 * grid to snap to.
 *
 * The grid is the downbeat list the analysis produced, with the three beats
 * between each pair of downbeats filled in from the measured beat interval.
 * Those are the same beats the drawing shows as dots, so what lights up under
 * the finger is exactly what the edge landed on.
 */
internal fun automixSnapToBeat(time: Double, digest: TrackAnalysisDigest): Double? {
    val downbeats = digest.downbeatTimes
    if (downbeats.isEmpty()) return null
    val beat = digest.beatInterval.takeIf { it > 0.0 && it.isFinite() }
        ?: digest.bpm.takeIf { it > 0.0 }?.let { 60.0 / it }
        ?: return null
    var low = 0
    var high = downbeats.size
    while (low < high) {
        val mid = (low + high) / 2
        if (downbeats[mid] < time) low = mid + 1 else high = mid
    }
    var best = downbeats[min(low, downbeats.lastIndex)]
    var bestDistance = abs(best - time)
    for (index in max(0, low - 1)..min(downbeats.lastIndex, low)) {
        val downbeat = downbeats[index]
        for (step in 0..(BEATS_PER_BAR - 1)) {
            val candidate = downbeat + step * beat
            val distance = abs(candidate - time)
            if (distance < bestDistance) {
                bestDistance = distance
                best = candidate
            }
        }
    }
    return best
}

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
 * axis so that its cue point lands on the left edge of the window. What the
 * listener moves — the orange box — is the window the transition will happen
 * in, and the green line at its left edge is the handoff itself: the moment
 * the arriving track becomes audible.
 *
 * ## The size is whole bars, the place is anywhere
 *
 * The planner works in whole bars, so the box does too: dragging an edge
 * takes the edge to the beat under the finger and re-derives the other edge
 * so the length stays a whole number of bars. That is what makes the box a
 * musical object rather than a freehand rectangle, and it is why a box can be
 * dropped anywhere in the song and still come back from the planner exactly
 * as it was drawn.
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
    val latestOnSnap by rememberUpdatedState(onSnap)

    // The panel's window is adopted whenever it says something new. While a
    // commit is in flight the dragged window stays on screen, so the box
    // cannot flicker back to the plan's placement for the frame between the
    // finger coming off and the panel answering.
    LaunchedEffect(window.start, window.end) {
        val waiting = pending
        if (waiting == null || waiting.start != window.start || waiting.end != window.end) {
            live = window
        }
        pending = null
    }

    // The view opens on the window, zooms out when the window grows, and comes
    // back when the window has been moved out of sight. It never moves while a
    // finger is down: the placement is measured against a frozen mapping, and
    // a view that shifted would put the box somewhere the finger is not.
    LaunchedEffect(window.start, window.end, duration, resetKey) {
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
        modifier.pointerInput(Unit) {
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
                val edgeHit = EDGE_HIT_DP.toPx()
                val bar = liveBar
                val track = latestCurrent
                val opened = live

                fun xOf(time: Double): Float = ((time - frozenFrom) / frozenSpan * width).toFloat()
                fun timeOf(x: Float): Double = frozenFrom + (x / width).coerceIn(0f, 1f) * frozenSpan

                val startX = xOf(opened.start)
                val endX = xOf(opened.end)
                val grabbed = when {
                    abs(down.position.x - startX) <= edgeHit -> Grab.START_EDGE
                    abs(down.position.x - endX) <= edgeHit -> Grab.END_EDGE
                    down.position.x >= min(startX, endX) && down.position.x <= max(startX, endX) -> Grab.BODY
                    // The upper lane is the outgoing track, which is the thing
                    // the box is placed against: a drag there moves the box.
                    down.position.y < lowerTop -> Grab.BODY
                    else -> Grab.VIEW
                }

                var decided = false
                var horizontal = false
                var lastSnap: Double? = null

                fun snap(raw: Double): Double {
                    val placed = automixSnapToBeat(raw, track) ?: raw
                    if (lastSnap == null || abs(placed - lastSnap!!) > 0.001) {
                        lastSnap = placed
                        snapped = placed
                        latestOnSnap()
                    }
                    return placed
                }

                /** Both edges on the track, in order, and the length untouched. */
                fun place(from: Double, to: Double) {
                    val ceiling = if (liveDuration > 0.0) liveDuration else Double.MAX_VALUE
                    val length = (to - from).coerceAtLeast(bar * MIN_TRANSITION_BARS)
                    var newFrom = from.coerceIn(0.0, max(0.0, ceiling - length))
                    if (newFrom + length > ceiling) newFrom = max(0.0, ceiling - length)
                    live = AutomixWindow(newFrom, newFrom + length)
                }

                /** The bars a raw span is worth, inside the range the planner works in. */
                fun barsIn(span: Double): Int = ((span / bar).roundToInt())
                    .coerceIn(MIN_TRANSITION_BARS, MAX_TRANSITION_BARS)

                fun apply(x: Float) {
                    val raw = timeOf(x)
                    when (grabbed) {
                        Grab.START_EDGE -> {
                            // The handoff goes where the finger is; the size is
                            // kept as the whole number of bars it already was, so
                            // the exit edge follows rather than the request
                            // becoming something the planner cannot execute.
                            val placed = snap(raw)
                            val bars = barsIn(opened.length)
                            val length = bars * bar
                            val ceiling = if (liveDuration > 0.0) liveDuration else Double.MAX_VALUE
                            val from = placed.coerceIn(0.0, max(0.0, ceiling - length))
                            live = AutomixWindow(from, min(ceiling, from + length))
                        }
                        Grab.END_EDGE -> {
                            // Where the outgoing track is retired. Ending early
                            // cuts its tail off, which the planner will not do
                            // by itself and the listener may well want.
                            val placed = snap(raw)
                            val bars = barsIn(opened.length)
                            val length = bars * bar
                            val to = placed.coerceIn(0.0, if (liveDuration > 0.0) liveDuration else placed)
                            live = AutomixWindow(max(0.0, to - length), to)
                        }
                        Grab.BODY -> {
                            // Two ways to take hold of the same box: on its body
                            // it travels with the finger, keeping the reach it
                            // was grabbed with; on the upper lane the handoff is
                            // put under the finger, which is the plainest way to
                            // say where the transition should start.
                            val placed = if (down.position.y < lowerTop) {
                                snap(raw)
                            } else {
                                val offset = down.position.x - startX
                                snap(timeOf(x - offset))
                            }
                            place(placed, placed + opened.length)
                        }
                        Grab.VIEW -> Unit
                    }
                }

                fun panTo(x: Float) {
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
                        if (!decided) {
                            val dx = change.position.x - down.position.x
                            val dy = change.position.y - down.position.y
                            if (abs(dx) > viewConfiguration.touchSlop || abs(dy) > viewConfiguration.touchSlop) {
                                decided = true
                                horizontal = abs(dx) >= abs(dy)
                                // Only a horizontal gesture is claimed. A vertical
                                // one belongs to the sheet, which scrolls.
                                if (horizontal) {
                                    change.consume()
                                    if (grabbed == Grab.VIEW) panTo(change.position.x) else apply(change.position.x)
                                }
                            }
                            continue
                        }
                        if (horizontal && change.positionChanged()) {
                            change.consume()
                            if (grabbed == Grab.VIEW) panTo(change.position.x) else apply(change.position.x)
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
                    }
                    snapped = null
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

        // The arriving track's own clock, laid on the outgoing one's: its cue
        // point is what plays when the window opens, so that is where it is
        // pinned. Everything before it is preroll that will not be heard, and
        // is drawn dimmer rather than hidden.
        val cue = max(0.0, plan.cueSeconds)
        val incomingMap: (Double) -> Double = { box.start + (it - cue) }

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

        // The grid the edges land on, as dots: downbeats longer and brighter,
        // the beats between them dimmer. The one the last placement snapped to
        // is lit, so a snap can be seen as well as felt.
        drawBeatDots(current, upperTop + laneHeight / 2f, from, span, { it }, snapped)
        if (hasNext) {
            drawBeatDots(next, lowerTop + laneHeight / 2f, from, span, incomingMap, null)
        }

        if (inWindow && enabled) {
            // The window itself: a tint over both lanes, so the two waveforms
            // are read through it rather than around it.
            drawRect(
                color = AutomixOrange.copy(alpha = 0.14f),
                topLeft = Offset(left, 0f),
                size = Size(right - left, height),
            )
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
 * One track's envelope, as a mirrored pair of strokes per column.
 *
 * Two tones because a single flat colour reads as a bar chart: the outer
 * stroke carries the peaks and the inner one the body, which is what makes a
 * waveform look like sound rather than like a histogram.
 *
 * Folded into one column per pixel before it is drawn. The analyser's own
 * resolution is thousands of samples over a track, and drawing every one of
 * them on every frame is a great deal of work for a picture no wider than the
 * screen — on a cheap phone that is the difference between a panel that opens
 * and one that stutters. The peak of each column is kept, so the transients
 * survive the fold.
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
    val amplitude = laneHeight * 0.44f
    val columns = size.width.toInt().coerceAtLeast(1)
    val tops = FloatArray(columns)
    var seen = false
    for (index in peaks.indices) {
        val time = times.getOrNull(index) ?: continue
        val axis = mapToAxis(time)
        if (axis < from || axis > from + span) continue
        val column = (((axis - from) / span) * columns).toInt().coerceIn(0, columns - 1)
        val value = peaks[index].coerceIn(0f, 1f)
        if (value > tops[column]) {
            tops[column] = value
            seen = true
        }
    }
    if (!seen) return
    val step = size.width / columns
    for (column in 0 until columns) {
        val value = tops[column]
        if (value <= 0.004f) continue
        val x = column * step + step / 2f
        val half = value * amplitude
        drawLine(
            color = AutomixBlue,
            start = Offset(x, mid - half),
            end = Offset(x, mid + half),
            strokeWidth = 1.6f,
        )
        val inner = half * 0.42f
        drawLine(
            color = AutomixCream,
            start = Offset(x, mid - inner),
            end = Offset(x, mid + inner),
            strokeWidth = 1.6f,
        )
    }
}

/**
 * The measured grid, as dots along the lane's centre line.
 *
 * Green, because the grid is what a placement snaps to and the handoff that
 * ends up on it is green too: the two are the same idea seen twice. Downbeats
 * are larger and brighter — they are the ones a bar count is counted in — and
 * the beat the last placement landed on is lit outright, so a snap can be
 * confirmed by eye as well as by the tick under the finger.
 */
private fun DrawScope.drawBeatDots(
    digest: TrackAnalysisDigest,
    mid: Float,
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
    for (downbeat in downbeats) {
        for (step in 0 until BEATS_PER_BAR) {
            val time = downbeat + step * beat
            val axis = mapToAxis(time)
            if (axis < from) continue
            if (axis > last) return
            val x = ((axis - from) / span * width).toFloat()
            if (x < 0f || x > width) continue
            val down = step == 0
            val snappedHere = lit != null && abs(time - lit) < 0.001
            when {
                snappedHere -> {
                    drawCircle(
                        color = AutomixGreen.copy(alpha = 0.30f),
                        radius = 7.dp.toPx(),
                        center = Offset(x, mid),
                    )
                    drawCircle(color = Color.White, radius = 3.dp.toPx(), center = Offset(x, mid))
                }
                down -> drawCircle(
                    color = AutomixGreen.copy(alpha = 0.85f),
                    radius = 2.4.dp.toPx(),
                    center = Offset(x, mid),
                )
                else -> drawCircle(
                    color = AutomixGreen.copy(alpha = 0.42f),
                    radius = 1.5.dp.toPx(),
                    center = Offset(x, mid),
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

private enum class Grab { START_EDGE, END_EDGE, BODY, VIEW }

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
 * and a listener reaching for it with a thumb should not have to be exact.
 */
private val EDGE_HIT_DP = 26.dp

/** Points across the tone curve; enough for a bell, few enough to stay smooth. */
private const val EQ_CURVE_POINTS = 64

/** The gain that spans half a lane, so a musical shape is not a flat line. */
private const val EQ_FULL_SCALE_DB = 8f

/** Points along a volume curve; more than the eye can tell apart on a phone. */
private const val VOLUME_CURVE_POINTS = 96

/** Where a bass handoff's low end changes hands, as a share of the window. */
private const val FILTER_HANDOFF_AT = 0.5f
