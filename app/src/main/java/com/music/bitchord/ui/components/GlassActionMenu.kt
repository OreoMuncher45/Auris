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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
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
 * Placement is a plain offset inside a full-screen box, not a custom [Layout]:
 * a menu is a panel and a scrim, and the only thing that needs measuring is
 * whether there is room under the button or the panel has to open upwards.
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
    val density = LocalDensity.current

    var menuHeightPx by remember { mutableIntStateOf(0) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            // Keeps a scroll that runs out of rows from being taken as a drag
            // of the player sheet underneath.
            .containSheetGestures(),
    ) {
        val widthPx = constraints.maxWidth
        // A parent that is itself unbounded (a sheet measured before its window
        // settles, say) passes Int.MAX_VALUE as maxHeight. A vertically
        // scrollable Column measures against that and dies with
        // "measured with an infinity maximum height constraints", so clamp it
        // to the screen height before the menu ever sees it.
        val heightPx = constraints.maxHeight.takeIf { it < Int.MAX_VALUE }
            ?: with(density) {
                LocalConfiguration.current.screenHeightDp.dp.toPx().toInt()
            }
        val marginPx = with(density) { 12.dp.roundToPx() }
        val gapPx = with(density) { 6.dp.roundToPx() }
        val minRoomPx = with(density) { 220.dp.roundToPx() }
        val menuWidthPx = with(density) { menuWidth.roundToPx() }
            .coerceAtMost((widthPx - marginPx * 2).coerceAtLeast(0))

        val roomBelow = heightPx - anchor.bottom - gapPx
        val roomAbove = anchor.top - gapPx
        val placeBelow = roomBelow >= minRoomPx || roomBelow >= roomAbove

        val x = (anchor.right - menuWidthPx)
            .coerceIn(marginPx, (widthPx - marginPx - menuWidthPx).coerceAtLeast(marginPx))
        val y = if (placeBelow) {
            anchor.bottom + gapPx
        } else {
            (anchor.top - gapPx - menuHeightPx).coerceAtLeast(marginPx)
        }
        val maxMenuHeight = (heightPx - y - marginPx).coerceAtLeast(0)

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
                .offset { IntOffset(x, y) }
                .width(with(density) { menuWidthPx.toDp() })
                .heightIn(max = with(density) { maxMenuHeight.toDp() })
                .graphicsLayer {
                    val p = progress.value.coerceIn(0f, 1f)
                    alpha = p
                    if (!reduceMotion) {
                        transformOrigin = if (placeBelow) {
                            TransformOrigin(1f, 0f)
                        } else {
                            TransformOrigin(1f, 1f)
                        }
                        val scale = 0.90f + 0.10f * p
                        scaleX = scale
                        scaleY = scale
                        translationY = (1f - p) * if (placeBelow) -10f else 10f
                    }
                }
                // The exact surface the held menu uses; glass comes from the
                // app's pooled backdrop, the same one the nav bar and the
                // long-press menu sample.
                .heldMenuSurface(shape, plate, hairline, reduceDynamicBlur)
                .onSizeChanged { menuHeightPx = it.height }
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