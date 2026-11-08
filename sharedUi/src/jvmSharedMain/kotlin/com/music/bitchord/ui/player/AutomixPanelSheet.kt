package com.music.bitchord.ui.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AutomixPanelState
import com.music.bitchord.data.settings.AutomixPlanDigest
import com.music.bitchord.data.settings.TrackAnalysisDigest
import com.music.bitchord.data.settings.TrackAnalysisState
import com.music.bitchord.playback.smart.AutomixEffect
import com.music.bitchord.playback.smart.AutomixOverride
import com.music.bitchord.playback.smart.FilterTransitionVariant
import com.music.bitchord.playback.smart.MAX_TRANSITION_BARS
import com.music.bitchord.playback.smart.MIN_TRANSITION_BARS
import com.music.bitchord.playback.smart.TransitionEqShape
import com.music.bitchord.playback.smart.TransitionStyle
import com.music.bitchord.sharedui.resources.*
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import dev.chrisbanes.haze.HazeState
import java.util.Locale
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The Automix panel: what the analyser found, what it planned, and the
 * listener's own answer for this song.
 *
 * ## What it is for
 *
 * Automix has always worked out the next transition on its own. That is a
 * good default and a bad end of the story: a listener who hears a handoff
 * they do not like had nowhere to look, and nothing to change but a global
 * switch. This panel is the look and the change. It shows the two waveforms
 * the planner is actually working from, the grid it measured, the overlap it
 * chose — and it lets the listener put their own handoff where they want it,
 * kept with the song so it means the same thing next time.
 *
 * ## What the drawing means
 *
 * Both lanes share one axis: the *outgoing* track's clock. The upper lane is
 * the track that is playing; the lower is the one queued after it, placed so
 * that its cue point lands on the start of the overlap. The amber box is the
 * overlap the planner actually resolved, and the green line is where the
 * listener wants it to begin. Those two are deliberately separate marks: a
 * request the planner cannot execute is still shown, next to what it did
 * instead, rather than being quietly rewritten.
 *
 * The one coloured curve over the lower lane is the effect on the arriving
 * track — violet for the tone shape's frequency response, amber for a filter
 * ride. One curve, not two, because a second line over a waveform is where a
 * drawing stops being readable.
 *
 * ## Where the choice goes
 *
 * Everything the listener changes here is a per-song [AutomixOverride]. It is
 * held locally while they drag so the drawing keeps up with the finger, and
 * written through after a short pause — a drag produces a value every frame
 * and a profile is a file on disk.
 */
@Composable
fun AutomixPanelSheet(
    hazeState: HazeState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val panel by PlayerSettings.automixPanel.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()

    // The listener's working copy. Kept apart from the published profile so a
    // drag can move the line before anything has been planned for it, and so
    // a plan that lands mid-gesture cannot pull the line out from under it.
    var draft by remember(panel.trackId) { mutableStateOf(panel.profile) }
    var edited by remember(panel.trackId) { mutableStateOf(false) }
    val latestDraft by rememberUpdatedState(draft)
    val trackId = panel.trackId

    LaunchedEffect(draft, edited, trackId) {
        if (!edited || trackId.isBlank()) return@LaunchedEffect
        delay(WRITE_DEBOUNCE_MS)
        PlayerSettings.setAutomixProfile(trackId, latestDraft)
    }
    // A pending change is not lost because the sheet went away first. The
    // write itself is off the main thread, so this costs nothing to leave.
    DisposableEffect(trackId) {
        onDispose {
            if (trackId.isNotBlank() && latestDraft != panel.profile) {
                PlayerSettings.setAutomixProfile(trackId, latestDraft)
            }
        }
    }

    PlayerDrawer(
        hazeState = hazeState,
        title = stringResource(Res.string.automix_analysis_menu),
        onDismiss = onDismiss,
        modifier = modifier,
        maxHeight = 720.dp,
    ) {
        AutomixPanelBody(
            panel = panel,
            draft = draft,
            onDraft = {
                draft = it
                edited = true
            },
            onReset = {
                haptics.play(Haptic.Tap)
                draft = AutomixOverride.NONE
                edited = true
            },
            haptic = { haptics.play(Haptic.Select) },
        )
    }
}

@Composable
private fun ColumnScope.AutomixPanelBody(
    panel: AutomixPanelState,
    draft: AutomixOverride,
    onDraft: (AutomixOverride) -> Unit,
    onReset: () -> Unit,
    haptic: () -> Unit,
) {
    val current = panel.current
    val next = panel.next
    val plan = panel.plan
    val hasNext = panel.nextTitle.isNotBlank()
    val scrubTime = draft.transitionStart.takeIf { it > 0.0 } ?: plan.transitionStart

    // The two numbers the line and the size are built from: where the
    // overlap ends, and how long one bar of the outgoing track is. The
    // planner uses the same two, so what the listener drags and what the
    // planner renders are measured against the same grid.
    val mixPoint = plan.transitionEnd.takeIf { it > 0.0 } ?: current.contentEnd
    val barSeconds = (
        current.beatInterval.takeIf { it > 0.0 }
            ?: current.bpm.takeIf { it > 0.0 }?.let { 60.0 / it }
            ?: 0.0
        ) * 4.0
    val ceiling = barCeiling(plan)

    AutomixTrackHeading(panel = panel, draft = draft)

    Spacer(Modifier.height(12.dp))

    AutomixTransitionGraph(
        current = current,
        next = next,
        plan = plan,
        scrubTime = scrubTime,
        hasNext = hasNext,
        enabled = draft.enabled,
        onScrub = { time ->
            // The line and the size are one decision: the overlap ends at the
            // mix point, so a place on the track is a number of bars before
            // it. Writing both keeps the panel and the planner talking about
            // the same overlap instead of two that disagree.
            val bars = barCountAt(time, mixPoint, barSeconds, ceiling)
            onDraft(draft.copy(transitionStart = time, bars = bars))
        },
        haptic = haptic,
        modifier = Modifier
            .fillMaxWidth()
            .height(196.dp),
    )

    AutomixLegend(plan = plan)

    // The size of the overlap, right under the drawing that shows it.
    AutomixBarsChip(
        bars = draft.bars,
        planned = plan.bars,
        onBars = { haptic(); onDraft(draft.copy(bars = it, transitionStart = handoffTime(it, mixPoint, barSeconds))) },
        modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .padding(top = 6.dp),
    )

    Text(
        text = stringResource(Res.string.automix_scrub_hint),
        style = MaterialTheme.typography.labelSmall,
        color = AutomixInkTertiary,
        modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .padding(top = 8.dp),
    )

    AutomixPresetCards(
        draft = draft,
        onDraft = onDraft,
        haptic = haptic,
    )

    Spacer(Modifier.height(16.dp))

    Column(Modifier.fillMaxWidth()) {
        AutomixSettingRow(
            label = stringResource(Res.string.automix_row_blend),
            value = blendValue(draft = draft, plan = plan),
            accent = AutomixGreen,
            options = blendOptions(draft = draft, onDraft = onDraft),
            haptic = haptic,
        )
        AutomixSettingRow(
            label = stringResource(Res.string.automix_row_overlap),
            value = if (draft.bars > 0) {
                stringResource(Res.string.automix_planned_bars, draft.bars)
            } else {
                stringResource(Res.string.automix_bars_auto)
            },
            accent = AutomixBlue,
            options = barsOptions(draft = draft, onDraft = onDraft),
            haptic = haptic,
        )
        AutomixSettingRow(
            label = stringResource(Res.string.automix_row_eq),
            value = eqLabel(draft.eq),
            accent = AutomixViolet,
            options = eqOptions(draft = draft, onDraft = onDraft),
            haptic = haptic,
        )
        AutomixSettingRow(
            label = stringResource(Res.string.automix_row_filter),
            value = filterLabel(draft.filter),
            accent = AutomixAmber,
            options = filterOptions(draft = draft, onDraft = onDraft),
            haptic = haptic,
        )
        AutomixSettingRow(
            label = stringResource(Res.string.automix_row_effects),
            value = effectsValue(draft = draft, plan = plan),
            accent = AutomixCoral,
            options = effectsOptions(draft = draft, onDraft = onDraft),
            haptic = haptic,
        )
        AutomixSwitchRow(
            label = stringResource(Res.string.automix_row_beatmatch),
            checked = draft.style == TransitionStyle.DJ_BLEND ||
                (draft.style == null && plan.style == TransitionStyle.DJ_BLEND.name),
            accent = Color.White,
            onChecked = { on ->
                haptic()
                onDraft(draft.copy(style = if (on) TransitionStyle.DJ_BLEND else TransitionStyle.DJ_FILTER))
            },
        )
    }

    Spacer(Modifier.height(16.dp))

    AutomixFactsCard(current = current, next = next, plan = plan, hasNext = hasNext)

    Spacer(Modifier.height(16.dp))

    AutomixSwitchRow(
        label = stringResource(Res.string.automix_row_automix),
        checked = draft.enabled,
        accent = AutomixGreen,
        onChecked = { on ->
            haptic()
            onDraft(draft.copy(enabled = on))
        },
    )

    Text(
        text = stringResource(Res.string.automix_reset),
        style = MaterialTheme.typography.labelLarge,
        color = if (draft.isEmpty) AutomixInkFaint else AutomixInkSecondary,
        modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .padding(top = 10.dp)
            .clip(CircleShape)
            .clickable(enabled = !draft.isEmpty, onClick = onReset)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    )
}

// ---------------------------------------------------------------------------
// Heading
// ---------------------------------------------------------------------------

@Composable
private fun AutomixTrackHeading(panel: AutomixPanelState, draft: AutomixOverride) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = panel.title.ifBlank { stringResource(Res.string.now_playing) },
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = AutomixInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (panel.nextTitle.isBlank()) {
                    stringResource(Res.string.automix_panel_no_next)
                } else {
                    stringResource(Res.string.automix_panel_next, panel.nextTitle)
                },
                style = MaterialTheme.typography.bodySmall,
                color = AutomixInkTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (!draft.enabled) {
            AutomixTag(text = stringResource(Res.string.automix_choice_none), color = AutomixInkTertiary)
        } else {
            panel.plan.style.takeIf { it.isNotBlank() }?.let { style ->
                AutomixTag(text = styleLabel(style), color = AutomixAmber)
            }
        }
    }
}

@Composable
private fun AutomixTag(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        color = color,
        maxLines = 1,
        modifier = Modifier
            .padding(start = 10.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(color.copy(alpha = 0.13f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

// ---------------------------------------------------------------------------
// The drawing
// ---------------------------------------------------------------------------

/**
 * Two lanes, one clock, one draggable handoff.
 *
 * The viewport follows the *plan*, never the finger. That is what makes the
 * drag feel solid: the grid and the waveforms stay exactly where they were
 * while the listener decides, and the view glides to the new neighbourhood
 * only once the plan has caught up with where they put the line.
 *
 * ## Three gestures, kept apart
 *
 * The outgoing lane is the clock — the whole drawing is the outgoing track's
 * own time, with the arriving track laid on it. That gives a finger three
 * things to do, and the panel keeps them apart so none of them fights the
 * others:
 *
 * - **Drag the outgoing lane** and the view scrolls: the outgoing waveform
 *   moves under the finger, the way a timeline does. The transition itself
 *   does not change.
 * - **Drag the arriving lane, or the green line itself**, and the handoff
 *   moves: it follows the finger in whole bars, inside the range the planner
 *   can actually execute.
 * - **Tap anywhere** and the handoff jumps to that place, snapped the same
 *   way.
 *
 * ## Why the mapping is frozen
 *
 * The finger-to-time mapping is taken once, when the finger lands, and kept
 * for the whole gesture. A view that moved under the finger would put the
 * line somewhere the finger is not: the line would appear to teleport to
 * wherever the finger touched, and then refuse to travel any further because
 * the plan kept pulling it back.
 */
@Composable
private fun AutomixTransitionGraph(
    current: TrackAnalysisDigest,
    next: TrackAnalysisDigest,
    plan: AutomixPlanDigest,
    scrubTime: Double,
    hasNext: Boolean,
    enabled: Boolean,
    onScrub: (Double) -> Unit,
    haptic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val duration = current.duration.takeIf { it > 0.0 } ?: 0.0
    // Where the overlap ends. A whole-bar overlap can only end at the mix
    // point, so the line is placed backwards from it.
    val mixPoint = plan.transitionEnd.takeIf { it > 0.0 } ?: current.contentEnd
    val beatSeconds = current.beatInterval.takeIf { it > 0.0 }
        ?: current.bpm.takeIf { it > 0.0 }?.let { 60.0 / it }
        ?: 0.0
    val barSeconds = beatSeconds * 4.0
    // How far back the line may go: the planner's own ceiling, so the panel
    // never offers a placement the planner would refuse.
    val maxBars = barCeiling(plan)

    // The window on show. Plain state rather than an animatable: the drag
    // writes to it from inside the pointer gesture, and a gesture's scope is
    // not allowed to reach into an animatable's mutator.
    var viewStart by remember { mutableStateOf(0.0) }
    var viewEnd by remember { mutableStateOf(1.0) }
    // The listener's own scroll, in seconds, measured from the plan's window.
    var panOffset by remember { mutableStateOf(0.0) }
    var initialised by remember { mutableStateOf(false) }
    var gesturing by remember { mutableStateOf(false) }

    val base = graphViewport(plan.transitionStart, plan.transitionEnd, duration)
    val liveBase by rememberUpdatedState(base)

    // The window follows the plan, and the listener's scroll is kept on top
    // of it: the plan decides which neighbourhood is on show, the scroll
    // decides how far into the song the listener has walked away from it.
    //
    // Nothing moves the window while a finger is on it. That is the whole
    // reason the drag can be trusted: the grid, the waveforms and the line
    // are in the same places before and after every frame of the gesture.
    LaunchedEffect(base, duration, gesturing) {
        if (gesturing) return@LaunchedEffect
        val from = base.start + panOffset
        val to = base.end + panOffset
        if (!initialised) {
            initialised = true
            viewStart = from
            viewEnd = to
            return@LaunchedEffect
        }
        // The glide, frame by frame. The window moves to the plan's new
        // neighbourhood instead of jumping to it, and it moves from wherever
        // it happens to be — which, after a scroll, is not where the plan
        // last put it.
        val fromStart = viewStart
        val fromEnd = viewEnd
        val started = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val progress = ((now - started) / VIEW_GLIDE_MS.toDouble()).coerceIn(0.0, 1.0)
            val eased = FastOutSlowInEasing.transform(progress.toFloat())
            viewStart = fromStart + (from - fromStart) * eased
            viewEnd = fromEnd + (to - fromEnd) * eased
            if (progress >= 1.0) break
        }
    }

    val latestScrub by rememberUpdatedState(onScrub)
    val latestHaptic by rememberUpdatedState(haptic)
    // The mix point, the bar length and the plan itself all move while the
    // panel is open — the analysis refines, the plan is re-made — and the
    // gesture loop is started once, so every one of them is read through a
    // state rather than captured.
    val liveMix by rememberUpdatedState(mixPoint)
    val liveBar by rememberUpdatedState(barSeconds)
    val liveMax by rememberUpdatedState(maxBars)
    val liveScrub by rememberUpdatedState(scrubTime)
    val liveDuration by rememberUpdatedState(duration)
    val liveHasNext by rememberUpdatedState(hasNext)

    val graphModifier = if (!enabled || beatSeconds <= 0.0) {
        modifier
    } else {
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val width = size.width.toFloat().coerceAtLeast(1f)
                val height = size.height.toFloat().coerceAtLeast(1f)
                // Frozen for the whole gesture. The view must not move under
                // the finger, or the line jumps away from it.
                val viewFrom = viewStart
                val viewTo = viewEnd
                val viewSpan = (viewTo - viewFrom).coerceAtLeast(0.001)
                val gap = 12.dp.toPx()
                val laneHeight = if (liveHasNext) (height - gap) / 2f else height
                val lowerTop = if (liveHasNext) laneHeight + gap else height
                val onIncoming = liveHasNext && down.position.y >= lowerTop
                val lineX = ((liveScrub - viewFrom) / viewSpan * width).toFloat()
                val onLine = abs(down.position.x - lineX) <= LINE_HIT_DP.toPx()
                // The outgoing lane is the clock and is scrolled; the arriving
                // lane is the thing being placed and is dragged onto the grid.
                val scrubbing = onIncoming || onLine
                var decided = false
                var horizontal = false
                var lastBars = -1

                fun handoffAt(x: Float) {
                    val raw = viewFrom + (x / width).coerceIn(0f, 1f) * viewSpan
                    val bars = barCountAt(raw, liveMix, liveBar, liveMax)
                    if (bars != lastBars) {
                        lastBars = bars
                        latestHaptic()
                    }
                    latestScrub(handoffTime(bars, liveMix, liveBar))
                }

                fun panTo(dx: Float) {
                    val span = viewSpan
                    val length = liveDuration
                    var from = viewFrom - (dx / width) * span
                    val maxFrom = max(0.0, length - span)
                    if (length > 0.0) from = from.coerceIn(0.0, maxFrom)
                    // Keep the handoff in sight: scrolling away from the thing
                    // being chosen is not what anyone wants from a panel that
                    // is about that thing.
                    val line = liveScrub
                    if (length > span && (from > line || from + span < line)) {
                        from = (if (from > line) line else line - span).coerceIn(0.0, maxFrom)
                    }
                    viewStart = from
                    viewEnd = from + span
                    panOffset = from - liveBase.start
                }

                gesturing = true
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
                                // Only a horizontal gesture is claimed. A
                                // vertical one belongs to the sheet, which
                                // scrolls.
                                if (horizontal) {
                                    change.consume()
                                    if (scrubbing) {
                                        handoffAt(change.position.x)
                                    } else {
                                        panTo(change.position.x - down.position.x)
                                    }
                                }
                            }
                            continue
                        }
                        if (horizontal && change.positionChanged()) {
                            change.consume()
                            if (scrubbing) {
                                handoffAt(change.position.x)
                            } else {
                                panTo(change.position.x - down.position.x)
                            }
                        }
                    }
                } finally {
                    gesturing = false
                }
                // A tap: the handoff jumps to the place that was touched.
                if (!decided) handoffAt(down.position.x)
            }
        }
    }

    Canvas(graphModifier) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas
        val from = viewStart
        val span = (viewEnd - viewStart).coerceAtLeast(0.001)
        fun xOf(time: Double): Float = ((time - from) / span * width).toFloat().coerceIn(-width, width * 2f)

        val gap = 12.dp.toPx()
        val laneHeight = if (hasNext) (height - gap) / 2f else height
        val upperTop = 0f
        val lowerTop = if (hasNext) laneHeight + gap else height

        // The two lanes' grounds. Rounded, barely lighter than the sheet, so
        // the waveforms have something to sit on without the panel growing a
        // pair of heavy boxes.
        drawLaneGround(upperTop, laneHeight)
        if (hasNext) drawLaneGround(lowerTop, laneHeight)

        val upperMid = upperTop + laneHeight / 2f
        drawWaveform(
            digest = current,
            laneTop = upperTop,
            laneHeight = laneHeight,
            from = from,
            span = span,
            mapToAxis = { it },
            peak = AutomixBlue,
            core = AutomixCream,
        )
        drawBeatGrid(
            digest = current,
            mid = upperMid,
            from = from,
            span = span,
            anchor = mixPoint,
            mapToAxis = { it },
        )

        if (hasNext) {
            val lowerMid = lowerTop + laneHeight / 2f
            // The incoming track's own clock, laid on the outgoing one's: the
            // cue point is what plays when the overlap opens, so that is where
            // it is pinned. Everything before it is preroll that will not be
            // heard, and is drawn dimmer rather than hidden.
            //
            // While the listener is dragging, the cue is worked out from the
            // plan rather than read from it: a longer overlap starts the
            // arriving track earlier in its own timeline, and the panel shows
            // that happening at once instead of waiting for the plan to catch
            // up with the finger.
            val drift = if (plan.transitionStart > 0.0) scrubTime - plan.transitionStart else 0.0
            val cue = max(0.0, plan.cueSeconds - drift * plan.incomingRate)
            val incomingMap: (Double) -> Double = { scrubTime + (it - cue) }
            drawWaveform(
                digest = next,
                laneTop = lowerTop,
                laneHeight = laneHeight,
                from = from,
                span = span,
                mapToAxis = incomingMap,
                peak = AutomixBlue.copy(alpha = 0.85f),
                core = AutomixCream.copy(alpha = 0.85f),
            )
            drawBeatGrid(
                digest = next,
                mid = lowerMid,
                from = from,
                span = span,
                anchor = mixPoint,
                mapToAxis = incomingMap,
            )
        }

        // The overlap the planner actually resolved. Drawn after the
        // waveforms so it tints them, and before the marks so the marks stay
        // crisp on top of it.
        //
        // Faded while a finger is down: the plan has not caught up with the
        // request yet, and a box that claims to be the overlap while it is
        // demonstrably something else is worse than a box that is merely
        // waiting.
        if (enabled && plan.transitionEnd > plan.transitionStart) {
            val left = xOf(plan.transitionStart)
            val right = xOf(plan.transitionEnd)
            if (right > left) {
                val alpha = if (gesturing) 0.35f else 1f
                drawOverlapBox(left, right, upperTop, laneHeight, alpha)
                if (hasNext) drawOverlapBox(left, right, lowerTop, laneHeight, alpha)
            }
        }

        // The one effect curve, over the arriving side.
        if (hasNext && enabled) {
            drawEffectCurve(plan = plan, laneTop = lowerTop, laneHeight = laneHeight)
        }

        // The listener's handoff. Green, full height, with a handle at the top
        // so there is something to aim a finger at. Brighter while it is
        // being held, because that is the one moment it is under the finger.
        if (enabled && scrubTime > 0.0) {
            val x = xOf(scrubTime)
            if (x in 0f..width) {
                drawLine(
                    color = AutomixGreen.copy(alpha = if (gesturing) 0.30f else 0.18f),
                    start = Offset(x, 0f),
                    end = Offset(x, height),
                    strokeWidth = 7.dp.toPx(),
                )
                drawLine(
                    color = AutomixGreen,
                    start = Offset(x, 0f),
                    end = Offset(x, height),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                val handleWidth = if (gesturing) 30.dp.toPx() else 22.dp.toPx()
                val handleHeight = 5.dp.toPx()
                drawRoundRect(
                    color = AutomixGreen,
                    topLeft = Offset(x - handleWidth / 2f, 0f),
                    size = Size(handleWidth, handleHeight),
                    cornerRadius = CornerRadius(handleHeight / 2f),
                )
            }
        }
    }
}

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
    peak: Color,
    core: Color,
) {
    val peaks = digest.waveform
    val times = digest.waveformTimes
    if (peaks.isEmpty() || times.isEmpty()) return
    val mid = laneTop + laneHeight / 2f
    val amplitude = laneHeight * 0.44f
    val stroke = 1.6f
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
            color = peak,
            start = Offset(x, mid - half),
            end = Offset(x, mid + half),
            strokeWidth = stroke,
        )
        val inner = half * 0.42f
        drawLine(
            color = core,
            start = Offset(x, mid - inner),
            end = Offset(x, mid + inner),
            strokeWidth = stroke,
        )
    }
}

/**
 * The measured grid, drawn as small marks along the lane's centre line.
 *
 * Downbeats are the brighter, longer marks. They are the ones a handoff is
 * placed against, so they are the ones the eye should find first.
 */
private fun DrawScope.drawBeatGrid(
    digest: TrackAnalysisDigest,
    mid: Float,
    from: Double,
    span: Double,
    anchor: Double,
    mapToAxis: (Double) -> Double,
) {
    val beat = digest.beatInterval.takeIf { it > 0.0 }
        ?: digest.bpm.takeIf { it > 0.0 }?.let { 60.0 / it }
        ?: return
    val width = size.width
    fun xOf(axis: Double): Float = ((axis - from) / span * width).toFloat()

    // Every beat, anchored the same way the planner anchors its bars: on the
    // mix point, which is itself a downbeat. That makes the marks on screen
    // the grid the transition will actually be built from.
    if (anchor > 0.0) {
        val first = kotlin.math.ceil((from - anchor) / beat).toInt()
        val last = kotlin.math.floor((from + span - anchor) / beat).toInt()
        for (step in first..last) {
            if (step % 4 == 0) continue
            val x = xOf(anchor + step * beat)
            if (x < 0f || x > width) continue
            drawRoundRect(
                color = Color.White.copy(alpha = 0.16f),
                topLeft = Offset(x - 1.5f, mid - 1.5f),
                size = Size(3f, 3f),
                cornerRadius = CornerRadius(1.5f),
            )
        }
    }
    for (downbeat in digest.downbeatTimes) {
        val axis = mapToAxis(downbeat)
        if (axis < from || axis > from + span) continue
        val x = xOf(axis)
        if (x < 0f || x > width) continue
        drawRoundRect(
            color = Color.White.copy(alpha = 0.55f),
            topLeft = Offset(x - 2.5f, mid - 2.5f),
            size = Size(5f, 5f),
            cornerRadius = CornerRadius(2.5f),
        )
    }
}

private fun DrawScope.drawOverlapBox(
    left: Float,
    right: Float,
    top: Float,
    height: Float,
    alpha: Float = 1f,
) {
    val inset = 2.dp.toPx()
    drawRoundRect(
        color = AutomixAmber.copy(alpha = 0.11f * alpha),
        topLeft = Offset(left, top + inset),
        size = Size(right - left, height - inset * 2),
        cornerRadius = CornerRadius(10.dp.toPx()),
    )
    drawRoundRect(
        color = AutomixAmber.copy(alpha = 0.55f * alpha),
        topLeft = Offset(left, top + inset),
        size = Size(right - left, height - inset * 2),
        cornerRadius = CornerRadius(10.dp.toPx()),
        style = Stroke(width = 1.1.dp.toPx()),
    )
}

/**
 * The effect on the arriving track, as one curve.
 *
 * A tone shape is a frequency response, so it is drawn the way an equalizer
 * draws one: logarithmic frequency across the lane, gain up and down. A
 * filter ride is a sweep *through the overlap*, so it is drawn as the ramp it
 * is, from where the overlap opens to where it closes.
 */
private fun DrawScope.drawEffectCurve(plan: AutomixPlanDigest, laneTop: Float, laneHeight: Float) {
    val mid = laneTop + laneHeight / 2f
    val amplitude = laneHeight * 0.42f
    val shape = plan.eq.takeIf { it.isNotBlank() }
        ?.let { name -> TransitionEqShape.entries.firstOrNull { it.name == name } }
    if (shape != null && !shape.isFlat) {
        val response = shape.responseDb(points = EQ_CURVE_POINTS)
        if (response.size < 2) return
        val path = Path()
        response.forEachIndexed { index, db ->
            val x = index.toFloat() / (response.size - 1) * size.width
            val y = mid - (db / EQ_FULL_SCALE_DB).coerceIn(-1f, 1f) * amplitude
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        // The response sits over the waveform; a soft under-stroke keeps the
        // line legible where it crosses a loud passage.
        drawPath(path, color = AutomixViolet.copy(alpha = 0.22f), style = Stroke(width = 6.dp.toPx()))
        drawPath(path, color = AutomixViolet, style = Stroke(width = 2.dp.toPx()))
        return
    }
    if (plan.filterSweep > 0.0 && plan.transitionEnd > plan.transitionStart) {
        // A filter ride opens across the overlap: the ramp is drawn over the
        // lane's full width so it reads as a gesture, not as a measurement.
        val path = Path()
        path.moveTo(0f, mid + amplitude)
        path.lineTo(size.width, mid - amplitude)
        drawPath(path, color = AutomixAmber.copy(alpha = 0.20f), style = Stroke(width = 6.dp.toPx()))
        drawPath(path, color = AutomixAmber, style = Stroke(width = 2.dp.toPx()))
    }
}

@Composable
private fun AutomixLegend(plan: AutomixPlanDigest) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AutomixLegendItem(color = AutomixGreen, text = stringResource(Res.string.automix_legend_handoff))
        AutomixLegendItem(color = AutomixAmber, text = stringResource(Res.string.automix_row_overlap))
        val shape = plan.eq.takeIf { it.isNotBlank() }
            ?.let { name -> TransitionEqShape.entries.firstOrNull { it.name == name } }
        when {
            shape != null && !shape.isFlat ->
                AutomixLegendItem(color = AutomixViolet, text = eqLabel(shape))
            plan.filterSweep > 0.0 ->
                AutomixLegendItem(color = AutomixAmber, text = stringResource(Res.string.automix_row_filter))
        }
    }
}

@Composable
private fun AutomixLegendItem(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = AutomixInkTertiary,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// The bars chip and the presets
// ---------------------------------------------------------------------------

@Composable
private fun AutomixBarsChip(
    bars: Int,
    planned: Int,
    onBars: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val label = if (bars > 0) {
        stringResource(Res.string.automix_planned_bars, bars)
    } else if (planned > 0) {
        stringResource(Res.string.automix_planned_bars, planned)
    } else {
        stringResource(Res.string.automix_bars_auto)
    }
    Box(modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.08f))
                .clickable { open = true }
                .padding(horizontal = 16.dp, vertical = 9.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = AutomixInk,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = AutomixInkTertiary,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .size(16.dp),
            )
        }
        AutomixMenu(
            open = open,
            onDismiss = { open = false },
            options = BAR_CHOICES.map { choice ->
                AutomixOption(
                    label = if (choice == 0) {
                        stringResource(Res.string.automix_bars_auto)
                    } else {
                        stringResource(Res.string.automix_planned_bars, choice)
                    },
                    selected = bars == choice,
                    action = { onBars(choice) },
                )
            },
        )
    }
}

@Composable
private fun AutomixPresetCards(
    draft: AutomixOverride,
    onDraft: (AutomixOverride) -> Unit,
    haptic: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = 0.08f)))
        Text(
            text = stringResource(Res.string.automix_panel_presets),
            style = MaterialTheme.typography.labelMedium,
            color = AutomixInkTertiary,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Spacer(Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = 0.08f)))
    }
    val preset = presetOf(draft)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AutomixPreset.entries.forEach { choice ->
            val selected = preset == choice
            val label = when (choice) {
                AutomixPreset.AUTO -> stringResource(Res.string.automix_preset_auto)
                AutomixPreset.CUSTOM -> stringResource(Res.string.automix_preset_custom)
                AutomixPreset.FADE -> stringResource(Res.string.automix_preset_fade)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (selected) AutomixInk else Color.White.copy(alpha = 0.07f))
                    .clickable {
                        haptic()
                        onDraft(
                            when (choice) {
                                AutomixPreset.AUTO -> AutomixOverride.NONE
                                // Custom is a statement about the choice rather
                                // than a new one: the listener has already made
                                // the changes, and this keeps them.
                                AutomixPreset.CUSTOM -> draft
                                AutomixPreset.FADE -> AutomixOverride(style = TransitionStyle.EQUAL_POWER)
                            },
                        )
                    }
                    .padding(horizontal = 8.dp, vertical = 12.dp),
            ) {
                if (choice == AutomixPreset.AUTO) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = if (selected) AutomixSheetInk else AutomixInkSecondary,
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(15.dp),
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                    color = if (selected) AutomixSheetInk else AutomixInkSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

private enum class AutomixPreset { AUTO, CUSTOM, FADE }

private fun presetOf(draft: AutomixOverride): AutomixPreset = when {
    draft.isEmpty -> AutomixPreset.AUTO
    draft.style == TransitionStyle.EQUAL_POWER && draft.bars <= 0 && draft.filter == null &&
        draft.eq == null && draft.effects == null && draft.transitionStart <= 0.0 -> AutomixPreset.FADE
    else -> AutomixPreset.CUSTOM
}

// ---------------------------------------------------------------------------
// The rows
// ---------------------------------------------------------------------------

private data class AutomixOption(
    val label: String,
    val selected: Boolean,
    val action: () -> Unit,
)

@Composable
private fun AutomixSettingRow(
    label: String,
    value: String,
    accent: Color,
    options: List<AutomixOption>,
    haptic: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable {
                haptic()
                open = true
            }
            .padding(vertical = 11.dp, horizontal = 4.dp),
    ) {
        Box(
            Modifier
                .size(width = 3.dp, height = 15.dp)
                .clip(CircleShape)
                .background(accent),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = AutomixInk,
            modifier = Modifier.padding(start = 12.dp),
        )
        Spacer(Modifier.weight(1f))
        Box {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = AutomixInkTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = AutomixInkFaint,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(start = 8.dp)
                    .size(18.dp),
            )
            // Anchored to the value rather than to the row: the menu belongs
            // under the thing it changes.
            AutomixMenu(open = open, onDismiss = { open = false }, options = options)
        }
    }
}

@Composable
private fun AutomixSwitchRow(
    label: String,
    checked: Boolean,
    accent: Color,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp, horizontal = 4.dp),
    ) {
        Box(
            Modifier
                .size(width = 3.dp, height = 15.dp)
                .clip(CircleShape)
                .background(accent),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = AutomixInk,
            modifier = Modifier.padding(start = 12.dp),
        )
        Spacer(Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AutomixSheetInk,
                checkedTrackColor = AutomixGreen,
                uncheckedThumbColor = Color.White.copy(alpha = 0.75f),
                uncheckedTrackColor = Color.White.copy(alpha = 0.12f),
                uncheckedBorderColor = Color.White.copy(alpha = 0.22f),
            ),
        )
    }
}

/**
 * The row's choices, as a dark menu over the dark sheet.
 *
 * The menu's own surface is covered rather than re-themed: the player sheet
 * is always dark whatever the app theme is doing, and a light popup with the
 * sheet's white type on it would be unreadable.
 */
@Composable
private fun AutomixMenu(
    open: Boolean,
    onDismiss: () -> Unit,
    options: List<AutomixOption>,
) {
    DropdownMenu(
        expanded = open,
        onDismissRequest = onDismiss,
        modifier = Modifier
            .widthIn(min = 190.dp, max = 280.dp)
            .background(Color(0xFF1C1C1E), RoundedCornerShape(16.dp)),
    ) {
        options.forEach { option ->
            DropdownMenuItem(
                text = {
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (option.selected) AutomixInk else AutomixInkSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingIcon = if (option.selected) {
                    {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = AutomixGreen,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                } else {
                    null
                },
                onClick = {
                    option.action()
                    onDismiss()
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// What the rows offer
// ---------------------------------------------------------------------------

@Composable
private fun blendOptions(
    draft: AutomixOverride,
    onDraft: (AutomixOverride) -> Unit,
): List<AutomixOption> {
    val choices = listOf<TransitionStyle?>(
        null,
        TransitionStyle.DJ_BLEND,
        TransitionStyle.DJ_FILTER,
        TransitionStyle.EQUAL_POWER,
    )
    return choices.map { choice ->
        AutomixOption(
            label = if (choice == null) {
                stringResource(Res.string.automix_choice_auto)
            } else {
                styleLabel(choice.name)
            },
            selected = draft.style == choice,
            action = { onDraft(draft.copy(style = choice)) },
        )
    }
}

@Composable
private fun blendValue(draft: AutomixOverride, plan: AutomixPlanDigest): String {
    val chosen = draft.style
    return when {
        !draft.enabled -> stringResource(Res.string.automix_choice_none)
        chosen != null -> styleLabel(chosen.name)
        plan.style.isNotBlank() -> stringResource(Res.string.automix_choice_auto)
        else -> stringResource(Res.string.automix_choice_auto)
    }
}

@Composable
private fun barsOptions(
    draft: AutomixOverride,
    onDraft: (AutomixOverride) -> Unit,
): List<AutomixOption> = BAR_CHOICES.map { choice ->
    AutomixOption(
        label = if (choice == 0) {
            stringResource(Res.string.automix_bars_auto)
        } else {
            stringResource(Res.string.automix_planned_bars, choice)
        },
        selected = draft.bars == choice,
        action = { onDraft(draft.copy(bars = choice)) },
    )
}

@Composable
private fun eqOptions(
    draft: AutomixOverride,
    onDraft: (AutomixOverride) -> Unit,
): List<AutomixOption> = TransitionEqShape.entries.map { shape ->
    AutomixOption(
        label = eqLabel(shape),
        selected = (draft.eq ?: TransitionEqShape.FLAT) == shape,
        action = { onDraft(draft.copy(eq = shape.takeIf { !it.isFlat })) },
    )
}

@Composable
private fun eqLabel(shape: TransitionEqShape?): String = when (shape ?: TransitionEqShape.FLAT) {
    TransitionEqShape.FLAT -> stringResource(Res.string.automix_eq_flat)
    TransitionEqShape.WARM -> stringResource(Res.string.automix_eq_warm)
    TransitionEqShape.BRIGHT -> stringResource(Res.string.automix_eq_bright)
    TransitionEqShape.AIR -> stringResource(Res.string.automix_eq_air)
    TransitionEqShape.DARK -> stringResource(Res.string.automix_eq_dark)
}

@Composable
private fun filterOptions(
    draft: AutomixOverride,
    onDraft: (AutomixOverride) -> Unit,
): List<AutomixOption> {
    val choices = listOf(null, FilterTransitionVariant.SWEEP, FilterTransitionVariant.BASS_HANDOFF)
    return choices.map { choice ->
        AutomixOption(
            label = when (choice) {
                null -> stringResource(Res.string.automix_choice_auto)
                FilterTransitionVariant.SWEEP -> stringResource(Res.string.automix_filter_sweep)
                else -> stringResource(Res.string.automix_filter_bass_handoff)
            },
            selected = draft.filter == choice,
            action = { onDraft(draft.copy(filter = choice)) },
        )
    }
}

@Composable
private fun filterLabel(filter: FilterTransitionVariant?): String = when (filter) {
    null -> stringResource(Res.string.automix_choice_auto)
    FilterTransitionVariant.SWEEP -> stringResource(Res.string.automix_filter_sweep)
    FilterTransitionVariant.BASS_HANDOFF -> stringResource(Res.string.automix_filter_bass_handoff)
    FilterTransitionVariant.ECHO_RIDE -> stringResource(Res.string.automix_effect_echo_out)
}

@Composable
private fun effectsOptions(
    draft: AutomixOverride,
    onDraft: (AutomixOverride) -> Unit,
): List<AutomixOption> {
    val choices = listOf(null, AutomixEffect.NONE, AutomixEffect.ECHO_TAIL)
    return choices.map { choice ->
        AutomixOption(
            label = when (choice) {
                null -> stringResource(Res.string.automix_choice_auto)
                AutomixEffect.NONE -> stringResource(Res.string.automix_choice_none)
                AutomixEffect.ECHO_TAIL -> stringResource(Res.string.automix_effect_echo_out)
            },
            selected = draft.effects == choice,
            action = { onDraft(draft.copy(effects = choice)) },
        )
    }
}

@Composable
private fun effectsValue(draft: AutomixOverride, plan: AutomixPlanDigest): String = when {
    draft.effects == AutomixEffect.NONE -> stringResource(Res.string.automix_choice_none)
    draft.effects == AutomixEffect.ECHO_TAIL -> stringResource(Res.string.automix_effect_echo_out)
    plan.echoSeconds > 0.0 -> stringResource(Res.string.automix_effect_echo_out)
    else -> stringResource(Res.string.automix_choice_auto)
}

@Composable
private fun styleLabel(style: String): String = when (style) {
    TransitionStyle.DJ_BLEND.name -> stringResource(Res.string.automix_style_dj_blend)
    TransitionStyle.DJ_FILTER.name -> stringResource(Res.string.automix_style_dj_filter)
    TransitionStyle.GAPLESS.name -> stringResource(Res.string.automix_style_gapless)
    TransitionStyle.EQUAL_POWER.name -> stringResource(Res.string.automix_style_equal_power)
    else -> style
}

// ---------------------------------------------------------------------------
// The evidence
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AutomixFactsCard(
    current: TrackAnalysisDigest,
    next: TrackAnalysisDigest,
    plan: AutomixPlanDigest,
    hasNext: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            AutomixTrackFacts(
                digest = current,
                heading = stringResource(Res.string.now_playing),
                modifier = Modifier.weight(1f),
            )
            if (hasNext) {
                AutomixTrackFacts(
                    digest = next,
                    heading = stringResource(Res.string.up_next),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        val facts = buildList {
            if (plan.overlapSeconds > 0.0) {
                add(stringResource(Res.string.automix_overlap_seconds, formatSeconds(plan.overlapSeconds)))
            }
            if (plan.transitionStart > 0.0) {
                add(stringResource(Res.string.automix_handoff_at, formatClock(plan.transitionStart)))
            }
            if (plan.cueSeconds > 0.0) {
                add(stringResource(Res.string.automix_cue_at, formatClock(plan.cueSeconds)))
            }
            if (current.hasTempo && next.hasTempo) {
                add(
                    stringResource(
                        Res.string.automix_tempo_match,
                        current.bpm.roundToInt(),
                        next.bpm.roundToInt(),
                    ),
                )
                if (plan.tempoRatio > 0.0) {
                    add("${(plan.tempoRatio * 100).roundToInt()}%")
                }
            }
            if (current.key.isNotBlank() && next.key.isNotBlank()) {
                add(
                    stringResource(
                        if (plan.keyCompatible) Res.string.automix_keys_compatible else Res.string.automix_keys_clash,
                    ),
                )
            }
            if (plan.vocalOverlap > 0.0) {
                add(stringResource(Res.string.automix_vocal_overlap, (plan.vocalOverlap * 100).roundToInt()))
            }
        }
        if (facts.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                facts.forEach { fact ->
                    Text(
                        text = fact,
                        style = MaterialTheme.typography.labelSmall,
                        color = AutomixInkSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AutomixTrackFacts(
    digest: TrackAnalysisDigest,
    heading: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            text = heading,
            style = MaterialTheme.typography.labelSmall,
            color = AutomixInkFaint,
        )
        Text(
            text = if (digest.hasTempo) {
                stringResource(Res.string.automix_bpm, digest.bpm.roundToInt())
            } else {
                stateLabel(digest)
            },
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = AutomixInk,
            modifier = Modifier.padding(top = 4.dp),
            maxLines = 1,
        )
        val key = digest.key.trim()
        if (key.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(
                    text = key,
                    style = MaterialTheme.typography.labelSmall,
                    color = AutomixInkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                camelotBadge(key)?.let { badge ->
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = AutomixViolet,
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(AutomixViolet.copy(alpha = 0.14f))
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
            }
        } else if (digest.provisional) {
            Text(
                text = stringResource(Res.string.automix_panel_partial),
                style = MaterialTheme.typography.labelSmall,
                color = AutomixInkFaint,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun stateLabel(digest: TrackAnalysisDigest): String = when (digest.state) {
    TrackAnalysisState.ANALYSED -> stringResource(Res.string.analysis_complete)
    TrackAnalysisState.REFINING -> stringResource(Res.string.analysis_refining)
    TrackAnalysisState.ANALYSING -> stringResource(Res.string.analysis_in_progress)
    TrackAnalysisState.FAILED -> stringResource(Res.string.failed)
    TrackAnalysisState.WAITING -> stringResource(Res.string.automix_panel_waiting)
}

// ---------------------------------------------------------------------------
// Geometry and wording helpers
// ---------------------------------------------------------------------------

/** The slice of the outgoing track the drawing shows. */
private data class AutomixViewport(val start: Double, val end: Double)

/**
 * The neighbourhood of the overlap, with room on both sides to see what is
 * being mixed into what.
 *
 * A minimum span because a four-bar overlap at a fast tempo is only a few
 * seconds, and a viewport that tight turns the drag into a hair trigger.
 */
private fun graphViewport(start: Double, end: Double, duration: Double): AutomixViewport {
    val overlap = (end - start).coerceAtLeast(0.0)
    val pad = max(10.0, overlap * 0.5)
    var from = start - pad
    var to = end + pad
    if (duration > 0.0) {
        from = from.coerceAtLeast(0.0)
        to = to.coerceAtMost(duration)
    }
    if (to - from < MIN_VIEW_SECONDS) {
        to = from + MIN_VIEW_SECONDS
        if (duration > 0.0) {
            to = to.coerceAtMost(duration)
            from = (to - MIN_VIEW_SECONDS).coerceAtLeast(0.0)
        }
    }
    return AutomixViewport(from, to)
}

/**
 * How many whole bars of overlap a place on the outgoing track would mean.
 *
 * The overlap ends at the mix point, so a place on the track is a number of
 * bars before it. Rounded to the nearest whole bar, because that is the only
 * size the planner can execute: a half-bar request would come back as
 * something else, and the line would sit somewhere the plan does not.
 *
 * Clamped to the range the planner works in — at least [MIN_TRANSITION_BARS]
 * of overlap, and never more than the ceiling it reported for this pair.
 * That is what keeps the line from travelling to a place the planner would
 * refuse, which is what made it look stuck.
 */
private fun barCountAt(raw: Double, mixPoint: Double, barSeconds: Double, maxBars: Int): Int {
    if (barSeconds <= 0.0 || mixPoint <= 0.0) return MIN_TRANSITION_BARS
    val bars = ((mixPoint - raw) / barSeconds).roundToInt()
    return bars.coerceIn(MIN_TRANSITION_BARS, maxOf(MIN_TRANSITION_BARS, maxBars))
}

/**
 * Where a handoff of [bars] whole bars lands on the outgoing track.
 *
 * The mix point minus the length of the overlap: the same arithmetic the
 * planner does, so the line and the plan agree on where the overlap begins.
 */
private fun handoffTime(bars: Int, mixPoint: Double, barSeconds: Double): Double {
    if (barSeconds <= 0.0 || mixPoint <= 0.0) return 0.0
    return max(0.0, mixPoint - bars * barSeconds)
}

/**
 * How many whole bars the planner could carry for this pair, as far as the
 * panel knows: the plan's own ceiling, or the size it settled on when it has
 * no ceiling to report.
 */
private fun barCeiling(plan: AutomixPlanDigest): Int =
    plan.maxBars.takeIf { it >= MIN_TRANSITION_BARS }
        ?: plan.bars.takeIf { it >= MIN_TRANSITION_BARS }
        ?: MAX_TRANSITION_BARS

/** The key as a Camelot badge, so two keys can be compared at a glance. */
private fun camelotBadge(key: String): String? {
    val normalised = key.replace('♯', '#').replace('♭', 'b').trim()
    if (normalised.isEmpty()) return null
    val minor = normalised.contains("minor", ignoreCase = true) ||
        normalised.endsWith("m", ignoreCase = true) && !normalised.contains("maj", ignoreCase = true)
    val root = normalised.substringBefore(' ').replace("minor", "", ignoreCase = true)
        .replace("major", "", ignoreCase = true).trim().uppercase(Locale.ROOT)
    return (if (minor) CAMELOT_MINOR else CAMELOT_MAJOR)[root]
}

private val CAMELOT_MAJOR = mapOf(
    "C" to "1B", "C#" to "8B", "DB" to "8B", "D" to "3B", "D#" to "10B", "EB" to "10B",
    "E" to "5B", "FB" to "5B", "F" to "12B", "E#" to "12B", "F#" to "7B", "GB" to "7B",
    "G" to "2B", "G#" to "9B", "AB" to "9B", "A" to "4B", "A#" to "11B", "BB" to "11B",
    "B" to "6B", "CB" to "11B",
)

private val CAMELOT_MINOR = mapOf(
    "C" to "10A", "C#" to "5A", "DB" to "5A", "D" to "12A", "D#" to "7A", "EB" to "7A",
    "E" to "2A", "F" to "9A", "F#" to "4A", "GB" to "4A", "G" to "11A", "G#" to "6A",
    "AB" to "6A", "A" to "1A", "A#" to "8A", "BB" to "8A", "B" to "3A", "CB" to "10A",
)

private fun formatClock(seconds: Double): String {
    if (!seconds.isFinite() || seconds < 0.0) return "0:00"
    val total = seconds.roundToInt()
    return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60)
}

private fun formatSeconds(seconds: Double): String =
    String.format(Locale.ROOT, "%.1fs", seconds)

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

private val AutomixInk = Color.White
private val AutomixInkSecondary = Color.White.copy(alpha = 0.68f)
private val AutomixInkTertiary = Color.White.copy(alpha = 0.48f)
private val AutomixInkFaint = Color.White.copy(alpha = 0.30f)
/** Type sitting on the white preset card rather than on the sheet. */
private val AutomixSheetInk = Color(0xFF141414)

private val AutomixAmber = Color(0xFFF5A524)
private val AutomixViolet = Color(0xFFC084FC)
private val AutomixGreen = Color(0xFF2ECC71)
private val AutomixBlue = Color(0xFF4C8DFF)
private val AutomixCream = Color(0xFFF2E8D5)
private val AutomixCoral = Color(0xFFFF7A6B)

/**
 * The sizes a listener can ask for, in whole bars. Zero means Automix decides.
 *
 * Every step is four bars, which is a phrase, and the top of the range is the
 * planner's own ceiling: asking for more is not a louder request, it is one
 * the planner will quietly round down.
 */
private val BAR_CHOICES = listOf(0, 4, 8, 12, 16, 20, 24, 28, 32)

/** How long after the last change the choice is written through. */
private const val WRITE_DEBOUNCE_MS = 250L

/** The narrowest useful slice of a track to draw and drag in. */
private const val MIN_VIEW_SECONDS = 40.0

/** How far the view glides when the plan moves to a new neighbourhood. */
private const val VIEW_GLIDE_MS = 320

/**
 * How close to the green line a finger has to land to grab it, in pixels.
 *
 * Wide enough to be a target rather than a pixel hunt: the line is thin, and
 * a listener reaching for it with a thumb should not have to be exact.
 */
private val LINE_HIT_DP = 28.dp

/** Points across the tone curve; enough for a bell, few enough to stay smooth. */
private const val EQ_CURVE_POINTS = 64

/** The gain that spans half a lane, so a musical shape is not a flat line. */
private const val EQ_FULL_SCALE_DB = 8f
