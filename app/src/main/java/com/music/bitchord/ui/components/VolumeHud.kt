package com.music.bitchord.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Job
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.player.PlayerIcons
import com.music.bitchord.ui.player.PlayerPlatform
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * What the volume keys tell [VolumeHudOverlay]. Kept apart from the level
 * itself, so a change from the player's bar or the settings slider moves the
 * level without announcing itself on screen.
 */
object VolumeHud {
    /** +1 / -1 for a key press; ±2 when it was already at that end. */
    private val _pulses = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    internal val pulses: SharedFlow<Int> = _pulses.asSharedFlow()

    /**
     * True while the full player is up: its own volume bar shows the level,
     * unless the listener hid it (see the collector).
     */
    @Volatile var suppressed = false

    fun pulse(direction: Int, atLimit: Boolean) {
        // An Int, not an object: nothing allocated per key press.
        _pulses.tryEmit(if (atLimit) direction * 2 else direction)
    }
}

private const val HIDE_AFTER_MS = 1500L
private val HUD_HEIGHT = 156.dp
private val COMPACT_WIDTH = 14.dp
private val EXPANDED_WIDTH = 50.dp
private val EDGE_GAP = 10.dp
private val CAPSULE = Color(0xFF1C1C1E).copy(alpha = 0.82f)
private val EDGE = Color.White.copy(alpha = 0.12f)
private val ICON_ON_FILL = Color(0xFF3A3A3C)

/**
 * The app's volume, shown beside the volume keys while the app (not Android's
 * mixer) holds the level: a slim capsule that springs out, fills from the
 * bottom, stretches when pushed past either end, widens to a draggable slider
 * under a finger, and tucks away 1.5 s after the last touch.
 *
 * Adapted from upstream BitChord's DacVolumeHud onto v2's level, which is
 * [AppSettings.playerVolume] whichever way it is applied (the DAC's own control
 * or a sample gain).
 *
 * Performance: one fixed-size node. Slide, stretch, width and fill are
 * [Animatable]s read only inside graphicsLayer and drawWithCache, so a running
 * animation recomposes and relayouts nothing; the widening is drawn, not laid
 * out. Colours and the stroke are cached; drag deltas go through one conflated
 * channel instead of a coroutine per pointer event. Nothing is composed at all
 * until a key press asks for it.
 */
@Composable
fun VolumeHudOverlay() {
    var present by remember { mutableStateOf(false) }
    val slide = remember { Animatable(1f) }
    val stretch = remember { Animatable(1f) }
    val widen = remember { Animatable(0f) }
    val fill = remember { Animatable(AppSettings.playerVolume.value) }
    var stretchFromTop by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val drags = remember { Channel<Float>(Channel.CONFLATED) }
    val scope = rememberCoroutineScope()
    // One pending hide at a time, restarted by every key press and every
    // finger lift: nothing runs at all while the capsule is away.
    val hide = remember { arrayOfNulls<Job>(1) }
    fun scheduleHide() {
        hide[0]?.cancel()
        hide[0] = scope.launch {
            delay(HIDE_AFTER_MS)
            if (dragging) return@launch
            slide.animateTo(1f, spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow))
            widen.snapTo(0f)
            present = false
        }
    }

    LaunchedEffect(Unit) {
        VolumeHud.pulses.collect { pulse ->
            // Read per press, not composed: the player only draws its own
            // volume row while the bar isn't hidden, so with it hidden the HUD
            // is the only feedback there is.
            if (VolumeHud.suppressed && !AppSettings.hideVolumeBar.value) return@collect
            present = true
            launch { slide.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = 420f)) }
            scheduleHide()
            if (pulse == 2 || pulse == -2) {
                stretchFromTop = pulse < 0
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                launch {
                    stretch.animateTo(1.07f, tween(durationMillis = 90))
                    stretch.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 380f))
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        AppSettings.playerVolume.collect { level ->
            // Animated only while on screen: an animation asks for frames, and
            // a level moved from the player's bar must not run any unseen.
            if (present) {
                launch { fill.animateTo(level, spring(dampingRatio = 0.82f, stiffness = 520f)) }
            } else {
                fill.snapTo(level)
            }
        }
    }
    LaunchedEffect(Unit) {
        for (over in drags) stretch.snapTo(1f + minOf(0.12f, over * 0.35f))
    }

    if (!present) return

    val density = LocalDensity.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val heightPx = with(density) { HUD_HEIGHT.toPx() }
    val offscreenPx = with(density) { (EXPANDED_WIDTH + EDGE_GAP * 2).toPx() }
    val compactPx = with(density) { COMPACT_WIDTH.toPx() }
    val edgePx = with(density) { 0.5.dp.toPx() }
    // The level is only read inside these derived states, so the HUD
    // recomposes when the icon or its tint actually changes, not per step.
    val levelState = AppSettings.playerVolume.collectAsState()
    val icon by remember {
        derivedStateOf {
            val level = levelState.value
            when {
                level <= 0f -> PlayerIcons.VolumeMuted
                level < 0.5f -> PlayerIcons.VolumeLow
                else -> PlayerIcons.VolumeHigh
            }
        }
    }
    val iconOnFill by remember { derivedStateOf { levelState.value > 0.12f } }

    Box(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                // Level with a phone's volume keys, about a fifth of the way down.
                .padding(top = screenHeight * 0.2f, end = EDGE_GAP)
                .size(EXPANDED_WIDTH, HUD_HEIGHT)
                .graphicsLayer {
                    translationX = slide.value * offscreenPx
                    alpha = 1f - slide.value
                    scaleY = stretch.value
                    // Thinner as it stretches, as rubber does.
                    scaleX = 1f - (stretch.value - 1f) * 1.2f
                    transformOrigin = TransformOrigin(0.5f, if (stretchFromTop) 0f else 1f)
                }
                .drawWithCache {
                    val stroke = Stroke(width = edgePx)
                    onDrawBehind {
                        // The capsule's width is drawn from [widen], centred on
                        // the right edge of the slot, so widening never relayouts.
                        val width = compactPx + (size.width - compactPx) * widen.value
                        val left = size.width - width
                        val radius = CornerRadius(width / 2f, width / 2f)
                        val capsuleSize = Size(width, size.height)
                        drawRoundRect(CAPSULE, topLeft = Offset(left, 0f), size = capsuleSize, cornerRadius = radius)
                        val fillTop = size.height * (1f - fill.value.coerceIn(0f, 1f))
                        clipRect(left = left, top = fillTop, right = size.width, bottom = size.height) {
                            drawRoundRect(Color.White, topLeft = Offset(left, 0f), size = capsuleSize, cornerRadius = radius)
                        }
                        drawRoundRect(EDGE, topLeft = Offset(left, 0f), size = capsuleSize, cornerRadius = radius, style = stroke)
                    }
                }
                .pointerInput(Unit) {
                    var start = 0f
                    var travelled = 0f
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragging = true
                            hide[0]?.cancel()
                            start = AppSettings.playerVolume.value
                            travelled = 0f
                            scope.launch { widen.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)) }
                        },
                        onDragEnd = {
                            dragging = false
                            scheduleHide()
                            scope.launch { stretch.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 380f)) }
                            scope.launch { widen.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)) }
                        },
                        onDragCancel = {
                            dragging = false
                            scheduleHide()
                            scope.launch { stretch.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 380f)) }
                            scope.launch { widen.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)) }
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        travelled -= dragAmount
                        val wanted = start + travelled / heightPx
                        // Through the same door as the keys and the player's bar.
                        PlayerPlatform.host.volume.set(wanted.coerceIn(0f, 1f))
                        stretchFromTop = wanted < 0f
                        val over = when {
                            wanted > 1f -> wanted - 1f
                            wanted < 0f -> -wanted
                            else -> 0f
                        }
                        drags.trySend(over)
                    }
                },
            contentAlignment = Alignment.BottomEnd,
        ) {
            // Shown only when wide; faded by the same value that widens it.
            Image(
                painter = icon.painter(),
                contentDescription = null,
                colorFilter = ColorFilter.tint(if (iconOnFill) ICON_ON_FILL else Color.White),
                modifier = Modifier
                    .padding(end = 15.dp, bottom = 12.dp)
                    .size(20.dp)
                    .graphicsLayer { alpha = ((widen.value - 0.6f) / 0.4f).coerceIn(0f, 1f) },
            )
        }
    }
}
