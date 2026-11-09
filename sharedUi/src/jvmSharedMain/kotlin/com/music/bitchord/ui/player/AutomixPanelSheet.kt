package com.music.bitchord.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.music.bitchord.playback.smart.AutomixPreviewState
import com.music.bitchord.playback.smart.FilterTransitionVariant
import com.music.bitchord.playback.smart.MAX_TRANSITION_BARS
import com.music.bitchord.playback.smart.MIN_TRANSITION_BARS
import com.music.bitchord.playback.smart.TransitionEqShape
import com.music.bitchord.playback.smart.TransitionStyle
import com.music.bitchord.playback.smart.TransitionVolumeShape
import com.music.bitchord.sharedui.resources.*
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import dev.chrisbanes.haze.HazeState
import java.util.Locale
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
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
 * that its cue point lands on the left edge of the box. The orange box is the
 * window the listener has asked the transition to happen in — the green line
 * at its left edge is the handoff itself — and the two edges are dragged
 * separately, so pulling one out lengthens the transition and pushing it in
 * shortens it. A blend can begin and end anywhere in the outgoing song.
 *
 * Green rules along the top and bottom edges of the drawing are the measured
 * grid: every beat a short tick, every bar line a longer bright one. An edge is
 * pulled onto a bar line when the finger is already near one, and the line it
 * took is lit, so a snap can be confirmed by eye as well as by the tick under
 * the finger. The plan's own placement is a grey hairline beside the box, drawn
 * only when the two disagree — a request the planner cannot execute is still
 * shown, next to what it did instead, rather than being quietly rewritten.
 *
 * The coloured curves are the effects in flight: violet for the tone shape's
 * frequency response, amber for a filter ride, coral for the volume pair the
 * two faders ride. They are drawn from the listener's own choice rather than
 * from the plan, so a change of mind is visible at once instead of after the
 * next plan comes back.
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

    // The panel's own demand for the analysis it is about to draw.
    //
    // The engine already analyses the pair in the background, on its own
    // schedule, and that is the right shape for work nobody is waiting on.
    // But this panel opens on two lanes that the listener is *looking* at,
    // and the background schedule is not theirs: on a slow connection the
    // lanes stayed empty for long enough that the panel looked broken.
    //
    // So the panel asks, once, when it opens. The engine fetches the bytes
    // again if it has to — the lane the listener is looking at is worth a
    // second fetch — and measures whatever has arrived. Asking again while
    // the panel is open would only repeat that; the bytes are already on
    // their way by then.
    LaunchedEffect(panel.trackId) {
        PlayerSettings.forceAutomixAnalysis()
    }

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
            tick = { haptics.play(Haptic.Tick) },
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
    tick: () -> Unit,
) {
    val current = panel.current
    val next = panel.next
    val plan = panel.plan
    val hasNext = panel.nextTitle.isNotBlank()
    val barSeconds = automixBarSeconds(current)
    val window = automixWindow(draft = draft, current = current, plan = plan)
    val previewing by AutomixPreviewState.playing.collectAsStateWithLifecycle()

    AutomixTrackHeading(panel = panel, draft = draft)

    Spacer(Modifier.height(12.dp))

    AutomixTransitionEditor(
        current = current,
        next = next,
        plan = plan,
        draft = draft,
        window = window,
        hasNext = hasNext,
        enabled = draft.enabled,
        resetKey = panel.trackId,
        onWindow = { placed ->
            // The box and the size are one decision. The window is placed
            // where the listener put it and the size is what the two edges
            // measure, so the row below the drawing and the drawing itself can
            // never disagree about either.
            val bars = automixBarsBetween(placed.start, placed.end, barSeconds)
                .coerceIn(MIN_TRANSITION_BARS, MAX_TRANSITION_BARS)
            onDraft(
                draft.copy(
                    transitionStart = placed.start,
                    transitionEnd = placed.end,
                    bars = bars,
                ),
            )
        },
        onSnap = tick,
        modifier = Modifier
            .fillMaxWidth()
            .height(210.dp),
    )

    AutomixLegend(plan = plan, draft = draft)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The size of the window, right under the drawing that shows it. A
        // chosen size grows the box from its own right edge, so the exit the
        // listener placed stays where they placed it.
        AutomixBarsChip(
            bars = draft.bars,
            planned = plan.bars,
            onBars = { choice ->
                haptic()
                onDraft(barsChoice(draft, choice, window, barSeconds))
            },
        )
        if (PlayerSettings.automixPreviewAvailable && hasNext && draft.enabled) {
            Spacer(Modifier.width(10.dp))
            AutomixPreviewButton(
                playing = previewing,
                onToggle = {
                    haptic()
                    PlayerSettings.previewAutomix(!previewing)
                },
            )
        }
    }

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
            options = barsOptions(draft = draft, window = window, barSeconds = barSeconds, onDraft = onDraft),
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
        // How the two tracks leave and arrive. A shape about the outgoing
        // track and a shape about the incoming one are both here, because
        // they are the same question asked from two ends.
        AutomixSettingRow(
            label = stringResource(Res.string.automix_row_volume),
            value = volumeLabel(draft.volume),
            accent = AutomixCream,
            options = volumeOptions(draft = draft, onDraft = onDraft),
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

/**
 * What the lines in the drawing are.
 *
 * Drawn from the same choice the drawing is, not from the plan: the panel's
 * legend and its picture have to agree about what is on screen, and the
 * picture is the listener's answer until the planner has answered back.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AutomixLegend(plan: AutomixPlanDigest, draft: AutomixOverride) {
    val shape = draft.eq ?: plan.eq.takeIf { it.isNotBlank() }
        ?.let { name -> TransitionEqShape.entries.firstOrNull { it.name == name } }
    val filter = draft.filter ?: plan.filter.takeIf { it.isNotBlank() }
        ?.let { name -> FilterTransitionVariant.entries.firstOrNull { it.name == name } }
    val volume = draft.volume ?: plan.volume.takeIf { it.isNotBlank() }
        ?.let { name -> TransitionVolumeShape.entries.firstOrNull { it.name == name } }
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AutomixLegendItem(color = AutomixGreen, text = stringResource(Res.string.automix_legend_handoff))
        AutomixLegendItem(color = AutomixOrange, text = stringResource(Res.string.automix_row_overlap))
        when {
            shape != null && !shape.isFlat ->
                AutomixLegendItem(color = AutomixViolet, text = eqLabel(shape))
            filter != null ->
                AutomixLegendItem(color = AutomixAmber, text = filterLabel(filter))
        }
        if (volume != null && !volume.isPlain) {
            AutomixLegendItem(color = AutomixCoral, text = volumeLabel(volume))
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

/**
 * The preview: hear the transition the panel is about.
 *
 * The button plays the real handoff — the same plan, the same renderer, the
 * same two players — from a moment before the blend, and stops after it. What
 * it is not is a commitment: the song goes back to where it was when the
 * blend is over, paused, so listening to the transition has not consumed it.
 *
 * Two faces, one button, because there is only ever one thing to do: play it,
 * or stop playing it. Which face is showing is the engine's answer rather
 * than the panel's guess — the preview ends by itself when the blend is done.
 */
@Composable
private fun AutomixPreviewButton(playing: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (playing) AutomixGreen.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Icon(
            imageVector = if (playing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
            contentDescription = null,
            tint = if (playing) AutomixGreen else AutomixInk,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = stringResource(
                if (playing) Res.string.automix_preview_stop else Res.string.automix_preview,
            ),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = if (playing) AutomixGreen else AutomixInk,
            maxLines = 1,
            modifier = Modifier.padding(start = 6.dp),
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
    window: AutomixWindow,
    barSeconds: Double,
    onDraft: (AutomixOverride) -> Unit,
): List<AutomixOption> = BAR_CHOICES.map { choice ->
    AutomixOption(
        label = if (choice == 0) {
            stringResource(Res.string.automix_bars_auto)
        } else {
            stringResource(Res.string.automix_planned_bars, choice)
        },
        selected = draft.bars == choice,
        action = { onDraft(barsChoice(draft, choice, window, barSeconds)) },
    )
}

/**
 * A size choice, applied to the box rather than to the whole track.
 *
 * Measured backwards from the box's own right edge, because that edge is the
 * exit the listener placed: a longer blend should reach further back into the
 * outgoing song, not walk the exit along the track. Auto hands the size back
 * to the planner, and with it the placement, so the two edges go to zero
 * together — a size nobody asked for is not a box anybody drew.
 */
private fun barsChoice(
    draft: AutomixOverride,
    choice: Int,
    window: AutomixWindow,
    barSeconds: Double,
): AutomixOverride {
    if (choice <= 0) return draft.copy(bars = 0, transitionStart = 0.0, transitionEnd = 0.0)
    if (barSeconds <= 0.0 || window.end <= 0.0) return draft.copy(bars = choice)
    val wanted = choice.coerceIn(MIN_TRANSITION_BARS, MAX_TRANSITION_BARS)
    val end = window.end
    val start = max(0.0, end - wanted * barSeconds)
    val bars = automixBarsBetween(start, end, barSeconds).coerceIn(MIN_TRANSITION_BARS, wanted)
    return draft.copy(bars = bars, transitionStart = start, transitionEnd = end)
}

@Composable
private fun volumeOptions(
    draft: AutomixOverride,
    onDraft: (AutomixOverride) -> Unit,
): List<AutomixOption> {
    // Auto and None are both "leave the faders alone", and both are here
    // because the listener may mean either: one is a refusal to choose, the
    // other is a choice to do nothing.
    val choices = listOf<TransitionVolumeShape?>(
        null,
        TransitionVolumeShape.NONE,
        TransitionVolumeShape.SLAM,
        TransitionVolumeShape.FADE_OUT,
        TransitionVolumeShape.FAST_OUT,
        TransitionVolumeShape.FADE_IN,
        TransitionVolumeShape.FAST_IN,
    )
    return choices.map { choice ->
        AutomixOption(
            label = volumeLabel(choice),
            selected = (draft.volume ?: TransitionVolumeShape.AUTO) ==
                (choice ?: TransitionVolumeShape.AUTO),
            action = { onDraft(draft.copy(volume = choice)) },
        )
    }
}

@Composable
private fun volumeLabel(shape: TransitionVolumeShape?): String = when (shape ?: TransitionVolumeShape.AUTO) {
    TransitionVolumeShape.AUTO -> stringResource(Res.string.automix_choice_auto)
    TransitionVolumeShape.NONE -> stringResource(Res.string.automix_choice_none)
    TransitionVolumeShape.SLAM -> stringResource(Res.string.automix_volume_slam)
    TransitionVolumeShape.FADE_OUT -> stringResource(Res.string.automix_volume_fade_out)
    TransitionVolumeShape.FAST_OUT -> stringResource(Res.string.automix_volume_fast_out)
    TransitionVolumeShape.FADE_IN -> stringResource(Res.string.automix_volume_fade_in)
    TransitionVolumeShape.FAST_IN -> stringResource(Res.string.automix_volume_fast_in)
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
        // "Waiting for analysis" and "fetching the audio to analyse" are
        // different situations and the listener deserves to know which one
        // they are in: the first means the app is not doing anything, and the
        // second means it is working and how far along it is.
        val fetching = !digest.hasTempo && digest.progress > 0f && digest.progress < 1f
        Text(
            text = when {
                digest.hasTempo -> stringResource(Res.string.automix_bpm, digest.bpm.roundToInt())
                fetching -> stringResource(Res.string.automix_fetching)
                else -> stateLabel(digest)
            },
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = AutomixInk,
            modifier = Modifier.padding(top = 4.dp),
            maxLines = 1,
        )
        if (fetching) {
            // The bar itself, under the label: a number alone is read once and
            // forgotten, and a bar that moves says "still working" without
            // saying anything.
            val fraction = digest.progress.coerceIn(0.02f, 0.99f)
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.08f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(AutomixBlue),
                )
            }
            Text(
                text = stringResource(
                    Res.string.automix_fetching_percent,
                    (digest.progress * 100f).roundToInt().coerceIn(0, 99),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = AutomixInkFaint,
                modifier = Modifier.padding(top = 4.dp),
                maxLines = 1,
            )
        }
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
// Badges and formatting
// ---------------------------------------------------------------------------

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
