package com.music.bitchord.ui.components.menu

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.components.heldMenuSurface
import com.music.bitchord.ui.utils.containSheetGestures

/** The spring a menu opens and closes on, kept across compositions. */
@Stable
internal class MenuAnimation {
    val progress = Animatable(0f)

    // Read only once the caller has asked the menu to close, so the frames of
    // the entrance never touch whoever reads MenuPresence.isShowing.
    val stillVisible by derivedStateOf { progress.value > 0f }
}

/**
 * Whether an anchored menu is open, and where it is in its entrance or exit.
 *
 * The menu outlives its own closing: [isShowing] stays true until the exit
 * spring has run, so the host keeps composing the menu (and, in the player,
 * keeps recording the layer its glass samples) for those frames. Opening
 * costs no extra recomposition: while [expanded] is true, [isShowing] answers
 * without reading the animation at all.
 *
 * A new value each time [expanded] changes rather than a state written while
 * composing, which would recompose the caller once more on the next frame.
 */
@Immutable
class MenuPresence internal constructor(
    /** What the caller asked for. */
    val expanded: Boolean,
    internal val animation: MenuAnimation,
) {
    /** True from the frame the menu is asked for until its exit has finished. */
    val isShowing: Boolean get() = expanded || animation.stillVisible

    override fun equals(other: Any?): Boolean =
        other is MenuPresence && other.expanded == expanded && other.animation === animation

    override fun hashCode(): Int = 31 * expanded.hashCode() + System.identityHashCode(animation)
}

/**
 * Remembers a menu's spring and runs it: in when [expanded] turns true, back
 * out when it turns false. Compose the menu while [MenuPresence.isShowing].
 */
@Composable
fun rememberMenuPresence(expanded: Boolean): MenuPresence {
    val reduceMotion = rememberReduceMenuMotion()
    val animation = remember { MenuAnimation() }
    LaunchedEffect(animation, expanded) {
        animation.progress.animateTo(
            targetValue = if (expanded) 1f else 0f,
            animationSpec = if (expanded) MenuMotion.open(reduceMotion) else MenuMotion.close(reduceMotion),
        )
    }
    return remember(animation, expanded) { MenuPresence(expanded, animation) }
}

/**
 * The app's anchored menu: a panel hung off the button that opened it, over a
 * scrim, in the host's own window.
 *
 * It opens on whichever side of the button has the room, stays inside the
 * window's safe area, and is capped to that room so long content scrolls
 * inside it — the content brings its own scroll. The panel is
 * [heldMenuSurface]: liquid glass from whatever [com.music.bitchord.ui.components.LocalAppBackdrop]
 * the host provides, the flat plate when glass is off.
 *
 * [anchor] and [hostOffsetInWindow] are read while the panel is placed, never
 * while it is composed, so a button or host that moves every frame (the
 * player sliding up) relayouts this panel alone and recomposes nothing.
 *
 * Measured and placed in one pass: the side and the height cap come from the
 * room around the button alone, so nothing waits on the panel's own measured
 * height to arrive as state.
 */
@Composable
fun AnchoredMenu(
    presence: MenuPresence,
    /** The button's bounds in the coordinate space of this composable's root; null places it at the top end. */
    anchor: () -> IntRect?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** How wide the menu wants to be. Never wider than the host's safe width. */
    menuWidth: Dp = MenuTokens.Width,
    /**
     * Where this composable's root sits in the window, so the window's system
     * bars can be measured from it.
     */
    hostOffsetInWindow: () -> IntOffset = { IntOffset.Zero },
    content: @Composable ColumnScope.() -> Unit,
) {
    val reduceMotion = rememberReduceMenuMotion()
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val expanded = presence.expanded

    BackHandler(enabled = expanded, onBack = onDismiss)

    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val colors = menuColors(dark, reduceDynamicBlur)
    val shape = RoundedCornerShape(MenuTokens.Radius)
    val progress = presence.animation.progress

    val configuration = LocalConfiguration.current
    // Handles only: their values are read while the menu is placed, so a bar
    // appearing or going relayouts the menu without recomposing it.
    val safeDrawing = WindowInsets.safeDrawing
    val windowInfo = LocalWindowInfo.current
    // Written while the menu is placed and read while it is drawn, in the same
    // frame: which side it opened on, for the corner its entrance grows from.
    // A plain field, not state — the side never has to recompose anything.
    val side = remember { MenuSide() }
    val closeLabel = stringResource(R.string.close)

    Box(
        modifier = modifier
            .fillMaxSize()
            // Keeps a scroll that runs out of rows from being taken as a drag
            // of a sheet underneath.
            .containSheetGestures(),
    ) {
        // The page, pushed back just enough to say the rest is not the menu.
        // It fades with the panel; while closing it lets touches through.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) }
                .background(colors.scrim)
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
                .layout { measurable, constraints ->
                    val widthPx = constraints.maxWidth
                    // A parent that is itself unbounded passes Int.MAX_VALUE as
                    // maxHeight, and a scrollable column measured against that
                    // throws; clamp it to the screen height first.
                    val heightPx = constraints.maxHeight.takeIf { it < Constraints.Infinity }
                        ?: configuration.screenHeightDp.dp.roundToPx()
                    val host = hostOffsetInWindow()
                    val marginPx = MenuTokens.Margin.roundToPx()
                    val left = menuSafeStart(
                        inset = safeDrawing.getLeft(this, layoutDirection),
                        hostStart = host.x,
                    ) + marginPx
                    val right = menuSafeEnd(
                        hostSize = widthPx,
                        windowSize = windowInfo.containerSize.width,
                        inset = safeDrawing.getRight(this, layoutDirection),
                        hostStart = host.x,
                    ) - marginPx
                    val top = menuSafeStart(safeDrawing.getTop(this), host.y) + marginPx
                    val bottom = menuSafeEnd(
                        hostSize = heightPx,
                        windowSize = windowInfo.containerSize.height,
                        inset = safeDrawing.getBottom(this),
                        hostStart = host.y,
                    ) - marginPx
                    val menuWidthPx = menuWidth.roundToPx()
                        .coerceAtMost((right - left).coerceAtLeast(0))
                    val button = anchor() ?: IntRect(right, top, right, top)
                    val slot = menuSlot(
                        anchorTop = button.top,
                        anchorBottom = button.bottom,
                        top = top,
                        bottom = bottom,
                        gap = MenuTokens.Gap.roundToPx(),
                        minRoom = MenuTokens.MinRoom.roundToPx(),
                    )
                    side.below = slot.below
                    val placeable = measurable.measure(
                        Constraints(
                            minWidth = menuWidthPx,
                            maxWidth = menuWidthPx,
                            maxHeight = slot.maxHeight,
                        ),
                    )
                    val x = menuX(button.right, left, right, menuWidthPx)
                    val y = menuY(slot, placeable.height)
                    layout(widthPx, heightPx) { placeable.place(x, y) }
                }
                .graphicsLayer {
                    val p = progress.value.coerceIn(0f, 1f)
                    alpha = p
                    if (!reduceMotion) {
                        val below = side.below
                        transformOrigin = TransformOrigin(1f, if (below) 0f else 1f)
                        val scale = MenuMotion.START_SCALE + (1f - MenuMotion.START_SCALE) * p
                        scaleX = scale
                        scaleY = scale
                        translationY = (1f - p) * if (below) -MenuMotion.TRAVEL_PX else MenuMotion.TRAVEL_PX
                    }
                }
                .heldMenuSurface(shape, colors.plate, colors.hairline, reduceDynamicBlur),
        ) {
            // A row activated while the menu is already on its way out would
            // run its action twice. Three ways in, three guards: the rows read
            // LocalMenuInteractive and stop being clickable (which also ends
            // a press that began before the close, and keyboard activation);
            // the closing panel leaves the accessibility tree; and a sibling
            // drawn over the rows takes any new touch first.
            CompositionLocalProvider(LocalMenuInteractive provides expanded) {
                // No verticalScroll here: the rows bring their own, and a
                // scrollable inside a scrollable is measured with infinite
                // height and crashes. The cap above is what lets theirs work.
                Column(
                    Modifier
                        .then(if (expanded) Modifier else Modifier.clearAndSetSemantics { })
                        .padding(vertical = MenuTokens.PanelPadding),
                    content = content,
                )
            }
            if (!expanded) Box(Modifier.matchParentSize().swallowTouches())
        }
    }
}

/**
 * False while the menu around a row is closing. A row that can be activated
 * reads it so that it can't be once its menu has been dismissed.
 */
internal val LocalMenuInteractive = compositionLocalOf { true }

/** Which side of the button the menu opened on — see [AnchoredMenu]. */
private class MenuSide {
    var below: Boolean = true
}

private fun Modifier.swallowTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}
