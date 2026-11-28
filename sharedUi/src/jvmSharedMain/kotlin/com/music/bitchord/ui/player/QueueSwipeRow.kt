package com.music.bitchord.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.Haptics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class QueueSwipeAction { REMOVE, QUEUE }

/** How far a row has to travel, as a share of its width, before letting go commits. */
private const val COMMIT_FRACTION = 0.45f

/** Below this, a row that had armed disarms again — hysteresis, so it doesn't buzz twice. */
private const val DISARM_FRACTION = 0.36f

/**
 * What every swiping row shares: its labels, the haptics handle and whether
 * a swipe the other way queues or plays next. Built once by the queue, so a
 * row composing during a fling looks up no strings and collects no flows.
 */
@Stable
internal class QueueSwipeEnv(
    val removeLabel: String,
    val queueLabel: String,
    val playNext: Boolean,
    val haptics: Haptics,
    val reduceAnimation: Boolean,
)

/**
 * A row in motion. Only rows being swiped or settling have one; a row at rest
 * carries nothing but the empty slot for it.
 *
 * [offset], [collapse] and [alpha] are read in the draw and layout phases
 * only, so a drag moves a layer — it never recomposes the row.
 */
private class SwipeMotion(val track: QueueTrack) {
    var offset by mutableFloatStateOf(0f)
    var collapse by mutableFloatStateOf(1f)
    var alpha by mutableFloatStateOf(1f)
    var committing by mutableStateOf(false)
    var dragging = false
    var armed = false
}

/** Per row: the plain fields a gesture reads, refreshed as the row composes. */
private class SwipeHolder {
    var motion by mutableStateOf<SwipeMotion?>(null)
    var width = 0f
    var track: QueueTrack? = null
    var enabled = false
    var env: QueueSwipeEnv? = null
    var canCommit: (QueueTrack, QueueSwipeAction) -> Boolean = { _, _ -> false }
    var onCommit: (QueueTrack, QueueSwipeAction) -> Boolean = { _, _ -> false }
    var job: Job? = null
    var generation = 0
}

/**
 * A queue row that swipes: towards the start removes it, towards the end
 * moves it into Next in queue. The finger moves the row directly; only the
 * release animates. The track is captured when the drag starts, and the
 * caller resolves it again against the live queue before acting on it.
 *
 * Ported from BitChord's QueueSwipeRow (bcc743d49 and its fixes), reworked so
 * that a row at rest allocates no motion state and a drag recomposes nothing
 * but the reveal behind it, and that only when the direction flips.
 */
@Composable
internal fun QueueSwipeRow(
    track: QueueTrack,
    enabled: Boolean,
    env: QueueSwipeEnv,
    canCommit: (QueueTrack, QueueSwipeAction) -> Boolean,
    onCommit: (QueueTrack, QueueSwipeAction) -> Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (interactive: Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val holder = remember { SwipeHolder() }
    holder.track = track
    holder.enabled = enabled
    // Read through the holder: the gesture outlives the composition that
    // started it, and a changed reduced-motion setting must reach its release.
    holder.env = env
    holder.canCommit = canCommit
    holder.onCommit = onCommit
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr

    fun actionAt(offset: Float): QueueSwipeAction =
        if ((offset < 0f) == ltr) QueueSwipeAction.REMOVE else QueueSwipeAction.QUEUE

    fun settle(motion: SwipeMotion, cancelled: Boolean) {
        if (!motion.dragging) return
        motion.dragging = false
        motion.armed = false
        val width = holder.width
        val action = actionAt(motion.offset)
        val commit = !cancelled && holder.enabled && width > 0f &&
            abs(motion.offset) >= width * COMMIT_FRACTION && holder.canCommit(motion.track, action)
        val generation = ++holder.generation
        val quick = (holder.env ?: env).reduceAnimation
        holder.job?.cancel()
        holder.job = scope.launch {
            try {
                val slide = Animatable(motion.offset)
                if (commit) {
                    motion.committing = true
                    val destination = if (motion.offset < 0f) -width else width
                    slide.animateTo(destination, tween(if (quick) 0 else 180, easing = FastOutSlowInEasing)) {
                        motion.offset = value
                    }
                    if (action == QueueSwipeAction.REMOVE) {
                        // Closed up before the row goes, so the rows below
                        // slide up with it instead of jumping when it does.
                        Animatable(1f).animateTo(0f, tween(if (quick) 0 else 180, easing = FastOutSlowInEasing)) {
                            motion.collapse = value
                        }
                    }
                    val accepted = holder.enabled && holder.canCommit(motion.track, action) &&
                        try {
                            holder.onCommit(motion.track, action)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            false
                        }
                    if (accepted && action == QueueSwipeAction.REMOVE) {
                        // The row is on its way out; the list disposes it, and
                        // this scope with it. Only if that never happens does
                        // it come back.
                        delay(2_000)
                    }
                    if (accepted && action == QueueSwipeAction.QUEUE) {
                        // Moved within Next in queue the row keeps its key and
                        // this slot: put it back unseen, then fade it in.
                        motion.alpha = 0f
                        motion.offset = 0f
                        withFrameNanos { }
                        Animatable(0f).animateTo(1f, tween(if (quick) 0 else 160)) { motion.alpha = value }
                        return@launch
                    }
                    motion.alpha = 1f
                    Animatable(motion.collapse).animateTo(1f, tween(if (quick) 0 else 180)) {
                        motion.collapse = value
                    }
                    slide.snapTo(motion.offset)
                }
                slide.animateTo(0f, tween(if (quick) 0 else 220, easing = FastOutSlowInEasing)) {
                    motion.offset = value
                }
            } finally {
                // A newer drag owns the row now; leave its motion alone.
                if (holder.generation == generation) holder.motion = null
            }
        }
    }

    val motion = holder.motion
    Box(
        modifier = modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val fraction = holder.motion?.collapse ?: 1f
                // One pixel kept while collapsing, so a first-visible row stays
                // alive in the lazy list until the removal lands.
                val height = if (fraction < 1f) {
                    (placeable.height * fraction).roundToInt().coerceAtLeast(1)
                } else {
                    placeable.height
                }
                layout(placeable.width, height) { placeable.place(0, 0) }
            }
            .onSizeChanged { holder.width = it.width.toFloat() }
            // The swipes, offered to TalkBack and Switch Access as actions: with
            // the row's remove button gone, a swipe is the way to take a song out.
            .semantics {
                if (enabled) {
                    customActions = listOf(
                        CustomAccessibilityAction(env.removeLabel) {
                            holder.canCommit(track, QueueSwipeAction.REMOVE) &&
                                holder.onCommit(track, QueueSwipeAction.REMOVE)
                        },
                        CustomAccessibilityAction(env.queueLabel) {
                            holder.canCommit(track, QueueSwipeAction.QUEUE) &&
                                holder.onCommit(track, QueueSwipeAction.QUEUE)
                        },
                    )
                }
            }
            .pointerInput(enabled, ltr) {
                if (!enabled) return@pointerInput
                var active: SwipeMotion? = null
                try {
                    // Main-pass slop, so the handle's drag, the list's scroll
                    // and the long press all claim the pointer first.
                    detectHorizontalDragGestures(
                        onDragStart = {
                            val current = holder.track ?: return@detectHorizontalDragGestures
                            holder.generation++
                            holder.job?.cancel()
                            val started = holder.motion?.takeIf { !it.committing && it.track.key == current.key }
                                ?: SwipeMotion(current)
                            started.dragging = true
                            started.armed = false
                            holder.motion = started
                            active = started
                        },
                        onDragEnd = { active?.let { settle(it, cancelled = false) }; active = null },
                        onDragCancel = { active?.let { settle(it, cancelled = true) }; active = null },
                        onHorizontalDrag = { change, amount ->
                            val m = active ?: return@detectHorizontalDragGestures
                            change.consume()
                            val width = holder.width
                            m.offset = (m.offset + amount).coerceIn(-width, width)
                            val eligible = holder.canCommit(m.track, actionAt(m.offset))
                            val travelled = abs(m.offset)
                            if (eligible && width > 0f && travelled >= width * COMMIT_FRACTION) {
                                if (!m.armed) {
                                    (holder.env ?: env).haptics.play(Haptic.Tick)
                                    m.armed = true
                                }
                            } else if (!eligible || travelled < width * DISARM_FRACTION) {
                                m.armed = false
                            }
                        },
                    )
                } finally {
                    // The detector was torn down mid-drag (swipes switched off,
                    // the row's key changed): put the row back.
                    active?.let { settle(it, cancelled = true) }
                }
            },
    ) {
        if (motion != null) SwipeReveal(motion, ltr, env)
        Box(
            Modifier.graphicsLayer {
                val m = holder.motion
                translationX = m?.offset ?: 0f
                alpha = m?.alpha ?: 1f
            },
        ) {
            content(motion == null)
        }
    }
}

/**
 * What the row uncovers as it slides: red and a bin towards the start, the
 * accent and the queue glyph towards the end. Composed only while a row is in
 * motion, and recomposed only when the direction flips.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.SwipeReveal(
    motion: SwipeMotion,
    ltr: Boolean,
    env: QueueSwipeEnv,
) {
    val remove by remember(motion, ltr) { derivedStateOf { (motion.offset < 0f) == ltr } }
    val tint = if (remove) Color(0xFFE35B67) else MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .matchParentSize()
            .drawWithContent {
                val offset = motion.offset
                if (offset > 0f) {
                    clipRect(right = offset) { this@drawWithContent.drawContent() }
                } else if (offset < 0f) {
                    clipRect(left = size.width + offset) { this@drawWithContent.drawContent() }
                }
            }
            .background(tint.copy(alpha = 0.22f), RoundedCornerShape(8.dp))
            .padding(horizontal = 20.dp),
        contentAlignment = if (remove) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Icon(
            imageVector = when {
                remove -> Icons.Rounded.Delete
                env.playNext -> Icons.Rounded.PlaylistPlay
                else -> Icons.Rounded.PlaylistAdd
            },
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
    }
}
