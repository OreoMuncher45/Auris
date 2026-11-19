package com.music.bitchord.ui.components.menu

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.ui.utils.containSheetGestures
import kotlinx.coroutines.launch

/**
 * The app's bottom sheet, for the screens that are too much for a menu — a
 * list to pick from, a field to type in, rows to drag: the playlist and folder
 * pickers, the download manager, reorder, Sing, the Replay share.
 *
 * Drawn in the host's own window rather than a dialog's. Material's sheet put
 * each one in a window of its own, and that window was most of what an
 * opening cost — 40-odd ms on its first frame against a few for anything
 * drawn in place (UI 12's stage-0 baseline). It looks as those sheets did:
 * the same corners, handle and width, on the theme's own background.
 *
 * [item] is non-null while the sheet is wanted; the sheet stays up through
 * its exit after the caller has let go, and each opening composes [content]
 * afresh. Dragged down by its handle or its body — or by a list at its top,
 * pulled further — a quarter of its height, or flung, it goes; short of that
 * it springs back. [dragFromContent] false keeps a list's leftover scroll out
 * of it, for content whose own drags mean something else (reorder).
 *
 * Every moving value is read while drawing — the slide, the drag and the
 * scrim — so neither the entrance nor a drag recomposes anything.
 */
@Composable
fun <T : Any> AppSheet(
    item: T?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.background,
    dragFromContent: Boolean = true,
    content: @Composable ColumnScope.(T) -> Unit,
) {
    val expanded = item != null
    val reduceMotion = rememberReduceMenuMotion()
    val sheet = remember { SheetState<T>() }
    val opening = remember(expanded) { Any() }
    if (item != null) {
        sheet.item = item
        sheet.opening = opening
    }
    LaunchedEffect(expanded) {
        if (expanded) sheet.drag = 0f
        sheet.progress.animateTo(
            targetValue = if (expanded) 1f else 0f,
            animationSpec = if (reduceMotion) {
                tween(if (expanded) 140 else 100, easing = LinearEasing)
            } else {
                tween(if (expanded) 300 else 220, easing = FastOutSlowInEasing)
            },
        )
    }
    if (!expanded && !sheet.stillVisible) {
        sheet.item = null
        return
    }
    val current = sheet.item ?: return

    BackHandler(enabled = expanded, onBack = onDismiss)
    val scope = rememberCoroutineScope()
    val closeLabel = stringResource(R.string.close)
    val settle: (velocity: Float) -> Unit = { velocity ->
        val height = sheet.height
        if (height > 0 && (sheet.drag > height * DISMISS_FRACTION || velocity > DISMISS_VELOCITY)) {
            onDismiss()
        } else if (sheet.drag > 0f) {
            scope.launch { animate(sheet.drag, 0f, animationSpec = tween(200)) { v, _ -> sheet.drag = v } }
        }
    }
    val pull = remember(dragFromContent) {
        object : NestedScrollConnection {
            // Pushing back up returns the sheet before the list scrolls again.
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (sheet.drag <= 0f || available.y >= 0f) return Offset.Zero
                val used = available.y.coerceAtLeast(-sheet.drag)
                sheet.drag += used
                return Offset(0f, used)
            }

            // A list resting at its top, pulled further, pulls the sheet.
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    sheet.drag += available.y
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (sheet.drag <= 0f) return Velocity.Zero
                settle(available.y)
                return available
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            // Keeps a drag that runs out of sheet from moving the page under it.
            .containSheetGestures()
            .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
            .padding(top = 8.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // The page behind, dimmed with the sheet and lighter as it is dragged away.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = sheet.progress.value.coerceIn(0f, 1f)
                    val h = sheet.height
                    alpha = if (h > 0) p * (1f - sheet.drag / h).coerceIn(0f, 1f) else p
                }
                .background(SCRIM)
                .then(
                    if (expanded) {
                        Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClickLabel = closeLabel,
                                onClick = onDismiss,
                            )
                            .semantics { contentDescription = closeLabel }
                    } else {
                        Modifier
                    },
                ),
        )
        Box(
            Modifier
                .widthIn(max = MenuTokens.SheetMaxWidth)
                .fillMaxWidth()
                .onSizeChanged { sheet.height = it.height }
                .graphicsLayer {
                    val p = sheet.progress.value.coerceIn(0f, 1f)
                    val h = sheet.height
                    // Unseen until measured, or its first frame would sit
                    // fully open before the slide knows how far to travel.
                    alpha = if (h > 0) 1f else 0f
                    translationY = sheet.drag + (1f - p) * h
                }
                // The rounded top is painted rather than clipped: the handle's
                // strip keeps every row clear of the corners, and an unclipped
                // panel is hit-tested as the rectangle it is.
                .background(containerColor, SHAPE)
                // Taps between rows land on the sheet, not the scrim under it.
                .pointerInput(Unit) {}
                .then(if (dragFromContent) Modifier.nestedScroll(pull) else Modifier)
                .pointerInput(Unit) {
                    val tracker = VelocityTracker()
                    detectVerticalDragGestures(
                        onDragStart = { tracker.resetTracking() },
                        onDragEnd = { settle(tracker.calculateVelocity().y) },
                        onDragCancel = { settle(0f) },
                    ) { change, delta ->
                        sheet.drag = (sheet.drag + delta).coerceAtLeast(0f)
                        // The panel moves with the finger, so its own
                        // coordinates hardly move at all: track the finger
                        // where it is on the screen instead.
                        tracker.addPosition(change.uptimeMillis, change.position + Offset(0f, sheet.drag))
                    }
                },
        ) {
            CompositionLocalProvider(LocalMenuInteractive provides expanded) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .then(if (expanded) Modifier else Modifier.clearAndSetSemantics { })
                        .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
                ) {
                    // The grab handle, and the thing that says the sheet can be pulled away.
                    Box(
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(vertical = MenuTokens.SheetHandlePaddingV)
                            .size(MenuTokens.SheetHandleWidth, MenuTokens.SheetHandleHeight)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                    )
                    key(sheet.opening) { content(current) }
                }
            }
            // Mid-exit nothing on it answers — see AnchoredMenu's three guards.
            if (!expanded) Box(Modifier.matchParentSize().swallowSheetTouches())
        }
    }
}

@Stable
private class SheetState<T : Any> {
    var item: T? = null
    var opening: Any? = null
    val progress = Animatable(0f)
    val stillVisible by derivedStateOf { progress.value > 0f }

    /** How far the sheet has been dragged down, in px. Read while drawing only. */
    var drag by mutableFloatStateOf(0f)

    /**
     * The sheet's own height, in px. State so the slide's layer picks up the
     * first measurement: written on layout, read only while drawing, so it
     * re-runs the layer, never the composition.
     */
    var height by mutableIntStateOf(0)
}

private fun Modifier.swallowSheetTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}

private val SHAPE = RoundedCornerShape(topStart = MenuTokens.SheetRadius, topEnd = MenuTokens.SheetRadius)

/** Material's own sheet scrim strength, which these sheets were drawn over. */
private val SCRIM = Color.Black.copy(alpha = 0.32f)

/** How much of its height the sheet has to be dragged before letting go puts it away. */
private const val DISMISS_FRACTION = 0.25f

/** A flick down faster than this, in px/s, puts the sheet away however short. */
private const val DISMISS_VELOCITY = 1_500f
