package com.music.bitchord.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.utils.containSheetGestures

/**
 * The track menu that hangs off the player's three-dot button.
 *
 * It is the same menu the long-press on a track lifts into — same surface
 * ([heldMenuSurface]: liquid glass when the app's glass is on, the flat plate
 * when it is not), same rows, same ink — only it is anchored to a button
 * rather than grown out of a held row, and drawn inside the player sheet's own
 * window so it sits over the player.
 *
 * Placement is one layout modifier inside a full-screen box: the side comes
 * from the room around the button ([glassMenuSlot]), the panel is measured
 * against that room, and it is placed against its own measured height in the
 * same pass.
 *
 * The content is the caller's; see `SongActionsSheet` with
 * [SongActionsPresentation.Glass] for the rows.
 */
@Composable
fun GlassActionMenu(
    /** The button's bounds, in the coordinate space of this composable's root. */
    anchor: IntRect,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** How wide the menu wants to be. Never wider than the host's own width. */
    menuWidth: Dp = 300.dp,
    /**
     * Where this composable's root sits in the window, so the window's system
     * bars can be measured from it. The player's panel fills the window, so
     * this is zero there unless the panel is still settling.
     */
    hostOffsetInWindow: IntOffset = IntOffset.Zero,
    /** The rows. The panel is bounded to the room beside the button; the rows bring their own scroll. */
    content: @Composable ColumnScope.() -> Unit,
) {
    val context = LocalContext.current
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val systemAnimationsOff = remember(context) {
        runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }
    val reduceMotion = reduceAnimation || systemAnimationsOff

    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(
            1f,
            if (reduceMotion) {
                tween(durationMillis = 140, easing = LinearEasing)
            } else {
                // A gentler cousin of the held menu's spring: the panel grows
                // out of the button and settles, without the bounce a lift has.
                spring(dampingRatio = 0.84f, stiffness = 340f)
            },
        )
    }

    BackHandler(onBack = onDismiss)

    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // The same plate and hairline as the held menu, so the two read as one
    // material in the two places it appears.
    val plate = (if (dark) Color.Black else Color.White).copy(
        alpha = if (reduceDynamicBlur) 0.85f else if (dark) 0.78f else 0.85f,
    )
    val hairline = if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.06f)
    val shape = RoundedCornerShape(MENU_RADIUS)

    val configuration = LocalConfiguration.current
    // Handles only: their values are read while the menu is placed, so a bar
    // appearing or going relayouts the menu without recomposing it.
    val safeDrawing = WindowInsets.safeDrawing
    val windowInfo = LocalWindowInfo.current
    // Written while the menu is placed and read while it is drawn, in the same
    // frame: which side it opened on, for the corner its entrance grows from.
    // A plain field, not state — the side never has to recompose anything.
    val placement = remember { GlassMenuSide() }

    Box(
        modifier = modifier
            .fillMaxSize()
            // Keeps a scroll that runs out of rows from being taken as a drag
            // of the player sheet underneath.
            .containSheetGestures(),
    ) {
        // The page, pushed back just enough to say the rest is not the menu.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = if (dark) 0.30f else 0.10f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )

        Column(
            Modifier
                // Measured and placed in one pass. The side and the height cap
                // come from the room around the button alone, so nothing here
                // waits on the menu's own measured height to arrive as state —
                // which used to cost a recomposition per open and a first frame
                // drawn in the wrong place.
                .layout { measurable, constraints ->
                    val widthPx = constraints.maxWidth
                    // A parent that is itself unbounded (a sheet measured before
                    // its window settles, say) passes Int.MAX_VALUE as maxHeight.
                    // A vertically scrollable Column measures against that and
                    // dies with "measured with an infinity maximum height
                    // constraints", so clamp it to the screen height first.
                    val heightPx = constraints.maxHeight.takeIf { it < Constraints.Infinity }
                        ?: configuration.screenHeightDp.dp.roundToPx()
                    val marginPx = MENU_MARGIN.roundToPx()
                    val gapPx = MENU_GAP.roundToPx()
                    // Never under a side cutout or a side navigation bar
                    // either: the same safe area, across.
                    val left = glassMenuSafeStart(
                        inset = safeDrawing.getLeft(this, layoutDirection),
                        hostStart = hostOffsetInWindow.x,
                    ) + marginPx
                    val right = glassMenuSafeEnd(
                        hostSize = widthPx,
                        windowSize = windowInfo.containerSize.width,
                        inset = safeDrawing.getRight(this, layoutDirection),
                        hostStart = hostOffsetInWindow.x,
                    ) - marginPx
                    val menuWidthPx = menuWidth.roundToPx()
                        .coerceAtMost((right - left).coerceAtLeast(0))
                    val slot = glassMenuSlot(
                        anchorTop = anchor.top,
                        anchorBottom = anchor.bottom,
                        // Never under the status bar, the cutout or the
                        // gesture bar: the band is the window's safe area
                        // in this host's coordinates, less the margins.
                        top = glassMenuSafeStart(safeDrawing.getTop(this), hostOffsetInWindow.y) + marginPx,
                        bottom = glassMenuSafeEnd(
                            hostSize = heightPx,
                            windowSize = windowInfo.containerSize.height,
                            inset = safeDrawing.getBottom(this),
                            hostStart = hostOffsetInWindow.y,
                        ) - marginPx,
                        gap = gapPx,
                        minRoom = MENU_MIN_ROOM.roundToPx(),
                    )
                    placement.below = slot.below
                    val placeable = measurable.measure(
                        Constraints(
                            minWidth = menuWidthPx,
                            maxWidth = menuWidthPx,
                            maxHeight = slot.maxHeight,
                        ),
                    )
                    val x = glassMenuX(anchor.right, left, right, menuWidthPx)
                    val y = glassMenuY(slot, placeable.height)
                    layout(widthPx, heightPx) { placeable.place(x, y) }
                }
                .graphicsLayer {
                    val p = progress.value.coerceIn(0f, 1f)
                    alpha = p
                    if (!reduceMotion) {
                        val below = placement.below
                        transformOrigin = if (below) {
                            TransformOrigin(1f, 0f)
                        } else {
                            TransformOrigin(1f, 1f)
                        }
                        val scale = 0.90f + 0.10f * p
                        scaleX = scale
                        scaleY = scale
                        translationY = (1f - p) * if (below) -10f else 10f
                    }
                }
                // The exact surface the held menu uses; glass comes from the
                // app's pooled backdrop, the same one the nav bar and the
                // long-press menu sample.
                .heldMenuSurface(shape, plate, hairline, reduceDynamicBlur)
                // No verticalScroll here: the rows bring their own (see
                // SongActionsPresentation.Glass), and a scrollable inside a
                // scrollable is measured with infinite height and crashes.
                // The panel is bounded by heightIn above, which is what lets
                // the rows' own scroll work.
                .padding(vertical = 6.dp),
            content = content,
        )
    }
}

/** The menu's corner radius — the same curve the held menu uses. */
private val MENU_RADIUS = 22.dp

/** The least the menu keeps between itself and the host's edges. */
private val MENU_MARGIN = 12.dp

/** Between the button and the menu hung off it. */
private val MENU_GAP = 6.dp

/** Room below the button that is enough to open downwards whatever is above. */
private val MENU_MIN_ROOM = 220.dp

/** Which side of the button the menu opened on — see [GlassActionMenu]. */
private class GlassMenuSide {
    var below: Boolean = true
}

/**
 * Which side of the button the menu opens on, and the stretch of the band it
 * may fill there.
 *
 * [top] and [bottom] are the usable band of the host, already shrunk by its
 * margins. The slot is the room on the chosen side *within* that band: it
 * starts one gap off the button, or at the band's edge if the button itself
 * is outside the band, so a menu at the cap never leaves the band or crosses
 * back over its own button. It never depends on the menu's own height, which
 * is what lets the menu be measured and placed in a single pass.
 */
internal fun glassMenuSlot(
    anchorTop: Int,
    anchorBottom: Int,
    top: Int,
    bottom: Int,
    gap: Int,
    minRoom: Int,
): GlassMenuSlot {
    val belowStart = (anchorBottom + gap).coerceIn(top, maxOf(top, bottom))
    val aboveEnd = (anchorTop - gap).coerceIn(top, maxOf(top, bottom))
    val roomBelow = (bottom - belowStart).coerceAtLeast(0)
    val roomAbove = (aboveEnd - top).coerceAtLeast(0)
    val below = roomBelow >= minRoom || roomBelow >= roomAbove
    return if (below) {
        GlassMenuSlot(below = true, maxHeight = roomBelow, edge = belowStart)
    } else {
        GlassMenuSlot(below = false, maxHeight = roomAbove, edge = aboveEnd)
    }
}

/**
 * See [glassMenuSlot]. [edge] is the menu's top when it hangs [below] the
 * button, and its bottom when it stands above it.
 */
internal data class GlassMenuSlot(val below: Boolean, val maxHeight: Int, val edge: Int)

/** The menu's top: hung from the slot's top edge, or standing on its bottom one. */
internal fun glassMenuY(slot: GlassMenuSlot, menuHeight: Int): Int =
    if (slot.below) slot.edge else slot.edge - menuHeight

/**
 * The menu's left: right edge on the button's, kept inside [left]..[right],
 * the host's safe band across with the margins already taken off.
 */
internal fun glassMenuX(anchorRight: Int, left: Int, right: Int, menuWidth: Int): Int =
    (anchorRight - menuWidth).coerceIn(left, (right - menuWidth).coerceAtLeast(left))

/**
 * Where the window's safe area starts (its top, or its left) in a host whose
 * own start sits at [hostStart] in the window, given the window's [inset] on
 * that side.
 */
internal fun glassMenuSafeStart(inset: Int, hostStart: Int): Int = (inset - hostStart).coerceAtLeast(0)

/**
 * Where the window's safe area ends (its bottom, or its right) in a host
 * [hostSize] long whose start sits at [hostStart] in the window, given the
 * window's [inset] on that side. A window size of zero (not known yet) falls
 * back to the host's own end.
 */
internal fun glassMenuSafeEnd(hostSize: Int, windowSize: Int, inset: Int, hostStart: Int): Int {
    val windowEnd = if (windowSize > 0) windowSize - hostStart else hostSize
    return minOf(hostSize, windowEnd - inset).coerceAtLeast(0)
}
