package com.music.bitchord.ui.player

import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import com.music.bitchord.ui.components.menu.LocalMenuInteractive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.key
import androidx.compose.runtime.Stable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.ui.components.liveMenuFrost
import com.music.bitchord.ui.components.optimizedHazeEffect
import com.music.bitchord.ui.utils.containSheetGestures
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

internal val DRAWER_SHAPE = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)

/**
 * The widest the drawer itself gets, however wide the window behind it is.
 *
 * Full-bleed it is fine on a phone and wrong on a tablet: the rows inside are a
 * label and a radio mark, so stretched across a landscape window each one is a
 * word at the far left, a mark at the far right and a hand's width of nothing
 * between them. Matches Material's own [BottomSheetDefaults.SheetMaxWidth], so
 * this drawer and the sheets the rest of the app puts up at the M3 default
 * settle at the same width instead of at two.
 *
 * No effect on a phone, which is narrower than this everywhere it runs.
 */
internal val DRAWER_MAX_WIDTH = 640.dp
internal val ROW_SHAPE = RoundedCornerShape(16.dp)
internal val SCRIM_COLOR = Color.Black.copy(alpha = 0.5f)

/**
 * How much of its own height the drawer has to be dragged before letting go
 * dismisses it rather than springing back. A quarter is enough to be a decision
 * and little enough that a flick reads as one.
 */
private const val DISMISS_DRAG_FRACTION = 0.25f

/**
 * The bottom-edge drawer shell every player sheet in this package puts up —
 * [AudioOutputSheet] and [ListenTogetherMembersSheet] alike: dark over a
 * scrim, a grab handle, a title, drag down to put it away. Pulled out because
 * the drag gesture, the scrim fade tied to it, and the haze background are
 * identical between the two and worth keeping in exactly one place.
 *
 * Public because a platform's own player sheet draws in it too: the phone's
 * lyrics card goes up in this same shell rather than in a second one that would
 * have to re-learn the drag gesture, the scrim fade and the frosted material.
 */
@Composable
fun PlayerDrawer(
    hazeState: HazeState,
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** Air between the title and whatever the drawer opens with. */
    titleGap: Dp = 14.dp,
    /**
     * How tall the drawer may grow.
     *
     * Capped so a phone full of rows scrolls inside the drawer rather than
     * growing one into a full-screen page — but a panel that is mostly a
     * graph wants more room than a list of output devices does, and a panel
     * that is *also* a list wants both. The cap is a parameter rather than a
     * constant for that reason.
     */
    maxHeight: Dp = 560.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Where the app keeps a drawer host, the drawer is drawn by it: so it is
    // over everything, and so it can play its way back down after the caller
    // has stopped asking for it — a drawer removed with its caller had no
    // exit at all (UI 12, stage 0). Where there is none (desktop), it is
    // drawn here, as it always was.
    val host = LocalDrawerHost.current
    if (host != null && !LocalInDrawerHost.current) {
        val drawer: @Composable (visible: Boolean, onGone: () -> Unit) -> Unit = { visible, onGone ->
            DrawerBody(visible, onGone, hazeState, title, onDismiss, modifier, titleGap, maxHeight, content)
        }
        val current by rememberUpdatedState(drawer)
        DisposableEffect(host) {
            val request = host.open { visible, onGone -> current(visible, onGone) }
            onDispose { host.withdraw(request) }
        }
        return
    }
    DrawerBody(true, {}, hazeState, title, onDismiss, modifier, titleGap, maxHeight, content)
}

/**
 * The app's one slot for a player drawer, drawn by [DrawerHostOverlay]. A
 * drawer asks while its caller composes it, and is let go when it stops; the
 * overlay keeps the last one up through its exit.
 */
@Stable
class DrawerHostState {
    internal class Request(val drawer: @Composable (visible: Boolean, onGone: () -> Unit) -> Unit)

    /** The drawer asked for now, or null once its caller has let go. */
    internal var request by mutableStateOf<Request?>(null)
        private set

    /** Whether a drawer is up (asked for, not just finishing its exit) — read as state. */
    val isOpen: Boolean get() = request != null

    /** The drawer on screen: the one asked for, or the last one, until its exit has played. */
    internal var shown by mutableStateOf<Request?>(null)
        private set

    internal fun open(drawer: @Composable (visible: Boolean, onGone: () -> Unit) -> Unit): Request =
        Request(drawer).also {
            request = it
            shown = it
        }

    internal fun withdraw(request: Request) {
        if (this.request === request) this.request = null
    }

    internal fun gone(request: Request) {
        if (shown === request && this.request !== request) shown = null
    }
}

val LocalDrawerHost = staticCompositionLocalOf<DrawerHostState?> { null }

internal val LocalInDrawerHost = staticCompositionLocalOf { false }

/**
 * Draws [state]'s drawer, and keeps the last one through its exit. Compose it
 * over the player — among the root's overlays, under its sheets and menus.
 */
@Composable
fun DrawerHostOverlay(state: DrawerHostState) {
    val shown = state.shown ?: return
    CompositionLocalProvider(LocalInDrawerHost provides true) {
        key(shown) {
            // Down once its caller lets go; forgotten once it is all the way
            // down, so nothing of it is left over the page.
            shown.drawer(state.request === shown) { state.gone(shown) }
        }
    }
}

/**
 * The drawer itself: dark over a scrim, a grab handle, a title, drag down to
 * put it away. [visible] false plays it back down.
 *
 * Every moving value — the slide, the drag, the scrim — is read only while
 * drawing, so neither the entrance, the exit nor a drag recomposes anything.
 * The scrim used to be computed from the drag while composing, which
 * recomposed the drawer on every frame of a drag (UI 12, stage 0).
 */
@OptIn(ExperimentalHazeMaterialsApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun DrawerBody(
    visible: Boolean,
    onGone: () -> Unit,
    hazeState: HazeState,
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier,
    titleGap: Dp,
    maxHeight: Dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reduceDynamicBlur by PlayerSettings.reduceDynamicBlur.collectAsStateWithLifecycle()

    val progress = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        progress.animateTo(
            if (visible) 1f else 0f,
            if (visible) tween(260, easing = FastOutSlowInEasing) else tween(180),
        )
        if (!visible) onGone()
    }
    // How far the drawer has been dragged down, in pixels, and its own height
    // — both read only while drawing.
    val drag = remember { mutableFloatStateOf(0f) }
    val height = remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    // Going, it keeps nothing focused and lets nothing in: a reset button
    // focused as it closed can't be pressed with Enter on the way down. The
    // focus is cleared only while it is the drawer's own, so a surface opened
    // over it keeps its own.
    val focusManager = LocalFocusManager.current
    val holdsFocus = remember { BooleanArray(1) }
    if (!visible) SideEffect { if (holdsFocus[0]) focusManager.clearFocus(force = true) }
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    // A short drag's way back to rest, while it runs: stopped by the next
    // drag, so the two never write the offset over each other.
    val springBack = remember { arrayOfNulls<Job>(1) }

    val dismissOnRelease: () -> Unit = {
        val h = height.intValue
        springBack[0]?.cancel()
        if (h > 0 && drag.floatValue > h * DISMISS_DRAG_FRACTION) {
            currentOnDismiss()
        } else {
            springBack[0] = scope.launch {
                animate(drag.floatValue, 0f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { v, _ ->
                    drag.floatValue = v
                }
            }
        }
    }

    // The drawer's rows scroll, and that scroll takes the finger before the
    // drag detector below ever sees it. So a pull down past the top of the
    // list moves the drawer itself — the way a sheet with a list in it
    // behaves — and pushing back up returns it before the list scrolls again.
    // Everything left over is kept here rather than handed on to the player's
    // sheet, which would otherwise be dragged down along with the drawer.
    //
    // Only where scrolling is dragging, though: see [drawerFollowsListScroll].
    val drawerScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (drag.floatValue <= 0f || available.y >= 0f) return Offset.Zero
                springBack[0]?.cancel()
                val used = available.y.coerceAtLeast(-drag.floatValue)
                drag.floatValue += used
                return Offset(0f, used)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    springBack[0]?.cancel()
                    drag.floatValue += available.y
                }
                if (drag.floatValue > 0f && available.y < 0f) {
                    val used = available.y.coerceAtLeast(-drag.floatValue)
                    drag.floatValue += used
                    return Offset(0f, used)
                }
                return available
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (drag.floatValue <= 0f) return Velocity.Zero
                dismissOnRelease()
                return available
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity) = available
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .containSheetGestures(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // The player behind, dimmed with the drawer and lighter as it is
        // dragged away — its own layer, so the drawer above doesn't fade too.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val h = height.intValue
                    val p = progress.value.coerceIn(0f, 1f)
                    alpha = if (h > 0) p * (1f - drag.floatValue / h).coerceIn(0f, 1f) else p
                }
                .background(SCRIM_COLOR)
                .then(
                    if (visible) {
                        Modifier.clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = { currentOnDismiss() },
                        )
                    } else {
                        Modifier
                    },
                ),
        )
        Column(
            modifier = Modifier
                // Capped so a phone full of rows scrolls inside the drawer
                // rather than growing one into a full-screen page.
                .heightIn(max = maxHeight)
                // Capped before the fill, so [Modifier.fillMaxWidth] fills to
                // the cap rather than to the window. Centred by the parent's
                // own BottomCenter alignment once it is narrower.
                .widthIn(max = DRAWER_MAX_WIDTH)
                .fillMaxWidth()
                .onSizeChanged { height.intValue = it.height }
                .graphicsLayer {
                    // Unseen until measured, then up from the edge — and
                    // back down to it on the way out.
                    val h = height.intValue
                    val p = progress.value.coerceIn(0f, 1f)
                    alpha = if (h > 0) 1f else 0f
                    translationY = drag.floatValue + (1f - p) * h
                }
                .clip(DRAWER_SHAPE)
                .then(
                    if (reduceDynamicBlur) {
                        Modifier.background(Color(0xFF121212))
                    } else {
                        Modifier
                            .liveMenuFrost(
                                state = hazeState,
                                style = HazeMaterials.regular(Color(0xFF141414)),
                            )
                            .background(Color(0xFF121212).copy(alpha = 0.9f))
                    }
                )
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    enabled = visible,
                    onClick = {},
                )
                .then(if (visible) Modifier else Modifier.clearAndSetSemantics { })
                .onFocusChanged { holdsFocus[0] = it.hasFocus }
                .onPreviewKeyEvent { !visible }
                .focusProperties { if (!visible) enter = { FocusRequester.Cancel } }
                .focusGroup()
                // Dragged down to dismiss, like every other sheet in the app.
                // Upward drag is clamped to zero rather than followed: there is
                // nothing above the drawer to reveal.
                .then(if (drawerFollowsListScroll) Modifier.nestedScroll(drawerScroll) else Modifier)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { springBack[0]?.cancel() },
                        onDragEnd = dismissOnRelease,
                        onDragCancel = { drag.floatValue = 0f },
                    ) { _, delta ->
                        drag.floatValue = (drag.floatValue + delta).coerceAtLeast(0f)
                    }
                }
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 10.dp, bottom = 20.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The grab handle every sheet here has, and the thing that says the
            // drawer can be pulled away before anybody tries it.
            Box(
                Modifier
                    .padding(bottom = 12.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.25f)),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, bottom = titleGap),
            )
            // On its way down, nothing in it answers: rows that read
            // LocalMenuInteractive stop, it leaves the accessibility tree, and
            // the cover below takes every touch, whatever its controls are.
            CompositionLocalProvider(LocalMenuInteractive provides visible) { content() }
        }
        if (!visible) {
            Box(
                Modifier
                    .matchParentSize()
                    .clearAndSetSemantics { }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        }
                    },
            )
        }
    }
}
