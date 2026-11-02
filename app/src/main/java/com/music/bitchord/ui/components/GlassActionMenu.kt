package com.music.bitchord.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.utils.containSheetGestures

/**
 * A menu that hangs off the button that opened it, in the app's liquid glass.
 *
 * The sheet is the wrong shape for a button like the player's three-dot menu:
 * a sheet rises from the bottom of the screen, so a menu opened from a button
 * in the top right answers a tap in the top right with a panel that fills the
 * bottom of the display. The distance between the question and its answer is
 * the whole of the screen, and the thing that was asked about — *this* track,
 * *now* — is behind a slab while it is being answered.
 *
 * So the menu hangs off the button instead. It opens under the button it came
 * from, aligned to it, and it is made of the same material the rest of the
 * floating chrome is: the app's liquid glass engine, sampling the artwork
 * behind it and refracting it through a lens, with a hairline edge and the
 * engine's own specular rim.
 *
 * ## Where it is drawn
 *
 * The host draws it in the same window as the content it floats over, because
 * the glass samples what is behind it: a menu in a separate window would
 * refract a different picture from the one it is sitting on. The host is
 * responsible for the [anchor] being in *its own* coordinate space, and for
 * providing a backdrop that records what is behind the menu.
 *
 * ## What it honours
 *
 * "Reduce animation" (or a system animator scale of zero) turns the opening
 * into a short fade with nothing moving. "Reduce dynamic blur" swaps the
 * glass for the flat plate — which is also the cost that setting exists to
 * remove.
 *
 * The Liquid Glass switch in Settings governs the app's *floating chrome* —
 * the nav bar and the top controls. This menu belongs to the player, which is
 * glass by definition in this shell, so it is not gated on that switch; see
 * [Modifier.liquidGlass] for the same reasoning.
 */
@Composable
fun GlassActionMenu(
    /**
     * The bounds of the button that opened the menu, in the coordinate space of
     * the host's own root. The menu is placed under it and aligned to its end.
     */
    anchor: IntRect,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** How wide the menu wants to be. Never wider than the host's own width. */
    menuWidth: Dp = 300.dp,
    /** The rows. Drawn in a column that scrolls when they do not all fit. */
    content: @Composable ColumnScope.() -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
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

    // The menu is on screen for as long as the caller keeps it there, so it
    // opens once and stays. The progress is read in the draw phase, so the
    // animation never recomposes the rows underneath it.
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(
            1f,
            if (reduceMotion) {
                tween(durationMillis = 140, easing = LinearEasing)
            } else {
                // A spring, but a gentle one: the menu grows out of the button
                // it belongs to, so the arrival has to read as that and not as
                // a panel dropping onto the screen.
                spring(dampingRatio = 0.86f, stiffness = 380f)
            },
        )
    }

    BackHandler(onBack = onDismiss)

    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // What the menu stands on where the engine cannot run: plain white or
    // black at a high enough strength to read as a surface over the artwork.
    val plate = (if (dark) Color.Black else Color.White).copy(
        alpha = if (reduceDynamicBlur) 0.92f else 0.82f,
    )
    val hairline = if (dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f)

    val shape = RoundedCornerShape(MENU_RADIUS)

    Layout(
        modifier = modifier
            .fillMaxSize()
            // The menu is drawn over the player, and the player lives in a
            // sheet. A scroll inside the menu that runs out of rows hands the
            // rest of the gesture up, and the sheet would take it as a drag of
            // the player itself.
            .containSheetGestures(),
        content = {
            // The scrim. Barely there — enough to say "the rest of the screen
            // is not the menu any more" without dimming the artwork the menu
            // is made of.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.16f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
            Column(
                Modifier
                    .clip(shape)
                    .then(
                        if (reduceDynamicBlur || !isGlassSupported()) {
                            Modifier.background(plate, shape)
                        } else {
                            Modifier.liquidGlass(shape)
                        },
                    )
                    // The edge every floating surface in this app ends in. The
                    // glass draws its own refractive rim as well; this is the
                    // line that keeps them all the same line.
                    .border(0.5.dp, hairline, shape)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 6.dp),
                content = content,
            )
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val margin = 12.dp.roundToPx()
        val gap = 6.dp.roundToPx()

        val scrim = measurables[0].measure(Constraints.fixed(width, height))

        // The button's own edges, in this layout's coordinates.
        val anchorLeft = anchor.left
        val anchorRight = anchor.right
        val anchorTop = anchor.top
        val anchorBottom = anchor.bottom

        // The room under the button and the room over it. The menu goes under
        // wherever it can: a menu that opens upward from a button near the
        // top of the screen reads as a panel that fell in from above.
        val roomBelow = height - anchorBottom - gap
        val roomAbove = anchorTop - gap
        val placeBelow = roomBelow >= MIN_ROOM.roundToPx() || roomBelow >= roomAbove
        val room = if (placeBelow) roomBelow else roomAbove

        val menuWidthPx = menuWidth.roundToPx().coerceAtMost((width - margin * 2).coerceAtLeast(0))
        val menu = measurables[1].measure(
            Constraints(
                minWidth = menuWidthPx,
                maxWidth = menuWidthPx,
                minHeight = 0,
                maxHeight = room.coerceAtLeast(0),
            ),
        )

        // Aligned to the button's own edge. The button is on the right of the
        // screen in every place this menu is opened from, so the menu's right
        // edge is the button's right edge — the menu hangs under the thing
        // that opened it rather than drifting off to one side of it.
        val x = (anchorRight - menuWidthPx)
            .coerceIn(margin, (width - margin - menuWidthPx).coerceAtLeast(margin))
        val y = if (placeBelow) {
            anchorBottom + gap
        } else {
            anchorTop - gap - menu.height
        }

        layout(width, height) {
            scrim.place(0, 0)
            menu.placeWithLayer(x, y) {
                val p = progress.value.coerceIn(0f, 1f)
                alpha = p
                if (!reduceMotion) {
                    // Out of the button: the corner nearest it stays put and
                    // the far edges travel, so the menu reads as growing from
                    // the tap rather than appearing beside it.
                    transformOrigin = if (placeBelow) {
                        TransformOrigin(1f, 0f)
                    } else {
                        TransformOrigin(1f, 1f)
                    }
                    val scale = 0.88f + 0.12f * p
                    scaleX = scale
                    scaleY = scale
                    // A few pixels of travel toward the button, so the motion
                    // has a direction as well as a scale.
                    translationY = (1f - p) * if (placeBelow) -12f else 12f
                }
            }
        }
    }
}

/** The menu's corner radius — the same curve the app's other glass uses. */
private val MENU_RADIUS = 22.dp

/**
 * The least room under the button for the menu to open downward. Below this
 * it opens upward instead, whatever the space over the button says.
 */
private val MIN_ROOM = 220.dp
