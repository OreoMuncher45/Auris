package com.music.bitchord.ui.components.menu

import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Job
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.positionChange
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
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.ExperimentalComposeUiApi
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
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun <T : Any> AppSheet(
    item: T?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.background,
    dragFromContent: Boolean = true,
    /**
     * Whether focus is kept inside the sheet while it is up. Read when focus
     * moves, not when composed. False lets a surface above it — a dialog, a
     * sheet stacked on this one — take the focus; the host says when.
     */
    trapFocus: () -> Boolean = { true },
    /** Called once the sheet is all the way down after going, so a host can forget it. */
    onGone: () -> Unit = {},
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
    val focusManager = LocalFocusManager.current
    LaunchedEffect(expanded) {
        // Opening or going, nothing else moves the sheet any more.
        sheet.springBack?.cancel()
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
        SideEffect { onGone() }
        return
    }
    // A dialog up over the sheet needs the focus for its field; the trap
    // gives way to it for as long as one is up.
    val dialogs = LocalDialogHost.current
    val current = sheet.item ?: return

    // A sheet on its way out keeps nothing focused: the keyboard goes with
    // it, and a form it carried can't be submitted from it. Cleared once the
    // composition that closed it is applied, so nothing is left to hand the
    // focus back. Only while the focus is its own: the exit recomposes, and
    // by then a sheet opened in its place may hold a field it mustn't lose.
    if (!expanded) SideEffect { if (sheet.holdsFocus) focusManager.clearFocus(force = true) }

    // Focus moves into the sheet as it opens — and again if a keyboard comes
    // into use while it is up — rather than staying on whatever opened it
    // behind the scrim. Never when the focus is already inside (a form's
    // field the listener is typing in): content that focuses its own field
    // does so after this and keeps it.
    val sheetFocus = remember { FocusRequester() }
    val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    LaunchedEffect(expanded, keyboard) {
        // Into the sheet — onto its handle, the first thing in it — unless
        // something inside already has the focus. In touch mode too: a field
        // behind the sheet would otherwise keep the software keyboard's
        // typing while covered, and only clearing the focus lets the window
        // hand it straight back to that field.
        if (expanded && !sheet.holdsFocus) sheetFocus.requestFocus()
    }

    BackHandler(enabled = expanded, onBack = onDismiss)
    val scope = rememberCoroutineScope()
    val closeLabel = stringResource(R.string.close)
    // The gesture and the list connection below are kept across
    // compositions; they reach the caller's current dismissal through this.
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val settle: (velocity: Float) -> Unit = { velocity ->
        val height = sheet.height
        sheet.springBack?.cancel()
        if (height > 0 && (sheet.drag > height * DISMISS_FRACTION || velocity > DISMISS_VELOCITY)) {
            currentOnDismiss()
        } else if (sheet.drag > 0f) {
            sheet.springBack = scope.launch {
                animate(sheet.drag, 0f, animationSpec = tween(200)) { v, _ -> sheet.drag = v }
            }
        }
    }
    val currentSettle by rememberUpdatedState(settle)
    val pull = remember(dragFromContent) {
        object : NestedScrollConnection {
            // Pushing back up returns the sheet before the list scrolls again.
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (sheet.drag <= 0f || available.y >= 0f) return Offset.Zero
                sheet.springBack?.cancel()
                val used = available.y.coerceAtLeast(-sheet.drag)
                sheet.drag += used
                return Offset(0f, used)
            }

            // A list resting at its top, pulled further, pulls the sheet.
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    sheet.springBack?.cancel()
                    sheet.drag += available.y
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (sheet.drag <= 0f) return Velocity.Zero
                currentSettle(available.y)
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
                // Outside the slide's layer — see the tracking note below. The
                // sheet only ever moves down from where it rests, so where it
                // rests is all it can be touched in. Restarted for a new
                // item, so a drag begun on one can't dismiss the next.
                .pointerInput(current) {
                    val tracker = VelocityTracker()
                    // Tracked from the finger's first touch, not from where
                    // the drag cleared the touch slop: a short flick is only
                    // a few events long, and the ones before the slop are
                    // what say how fast it was. This sits outside the layer
                    // that slides the panel, so positions and deltas are the
                    // finger's own, against where the sheet rests — not
                    // shrunk by the panel moving under the finger.
                    fun track(change: PointerInputChange) =
                        tracker.addPosition(change.uptimeMillis, change.position)
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        tracker.resetTracking()
                        track(down)
                        val start = awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
                            change.consume()
                            // A new drag takes the sheet from a spring-back
                            // still on its way, rather than fighting it.
                            sheet.springBack?.cancel()
                            sheet.drag = (sheet.drag + over).coerceAtLeast(0f)
                            track(change)
                        } ?: return@awaitEachGesture
                        val ended = verticalDrag(start.id) { change ->
                            sheet.drag = (sheet.drag + change.positionChange().y).coerceAtLeast(0f)
                            change.consume()
                            track(change)
                        }
                        currentSettle(if (ended) tracker.calculateVelocity().y else 0f)
                    }
                }
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
                .then(if (dragFromContent) Modifier.nestedScroll(pull) else Modifier),
        ) {
            CompositionLocalProvider(LocalMenuInteractive provides expanded) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        // Drawn in the page's window rather than a dialog's,
                        // so focus is kept by hand: while the sheet is up,
                        // nothing behind it can be reached, and while it goes,
                        // nothing on it can be — not even by the window
                        // handing its focus back to the first thing it finds.
                        .onFocusChanged { sheet.holdsFocus = it.hasFocus }
                        .focusRequester(sheetFocus)
                        .focusProperties {
                            if (expanded) {
                                exit = {
                                    val dialogUp = dialogs?.requests?.isNotEmpty() == true
                                    if (trapFocus() && !dialogUp) FocusRequester.Cancel else FocusRequester.Default
                                }
                            } else {
                                enter = { FocusRequester.Cancel }
                            }
                        }
                        .focusGroup()
                        .then(if (expanded) Modifier else Modifier.clearAndSetSemantics { })
                        .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
                ) {
                    // The grab handle, and the thing that says the sheet can be
                    // pulled away. Also where the focus lands as the sheet
                    // opens: focusable with no indication, so in touch mode it
                    // shows nothing, and with a keyboard Tab moves on from it.
                    Box(
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .focusable()
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

    /** Whether anything inside the sheet has the focus. Read when deciding to take it, never composed. */
    var holdsFocus = false

    /** A short drag's way back to rest, while it runs. */
    var springBack: Job? = null

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
