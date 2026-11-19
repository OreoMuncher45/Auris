package com.music.bitchord.ui.player

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import com.music.bitchord.sharedui.resources.*
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.Haptics
import kotlinx.coroutines.flow.first

/**
 * The player's own end of the docking into the mini player, for whichever
 * shape it takes: where it sits on screen, the fade, and the artwork drawn
 * over that fade — see [PlayerDock].
 */
internal fun Modifier.playerDockHost(
    dock: PlayerDock?,
    dockFrame: DockFrame,
    dockT: () -> Float,
    docking: () -> Boolean,
    dockFade: () -> Float,
    p: () -> Float,
    sleeveLayer: GraphicsLayer,
    creditsLayer: GraphicsLayer,
    density: Density,
): Modifier {
    // Whether the title and artist are on their way into the mini player.
    // Both ends of that decision have to agree: the credits are recorded
    // instead of drawn, and the recorded layer is drawn over the sleeve, so
    // one of them without the other loses the text entirely.
    val creditsHandover: () -> Boolean = {
        docking() && dock?.hasMiniText() == true
    }

    // How big the bar's title is compared with the player's. The title that
    // flies between them is scaled by this, so it arrives at the size it is
    // going to be rather than at the size it left.
    val creditsTitleRatio: () -> Float = {
        val mini = dock?.miniTextTitlePx() ?: 0f
        val full = with(density) { CREDITS_TITLE_SIZE.toPx() }
        if (mini > 0f && full > 0f) (mini / full).coerceIn(0.4f, 1.2f) else 1f
    }

    return this
        .then(
            if (dock != null) {
                Modifier.onGloballyPositioned { coordinates ->
                    dockFrame.host = coordinates
                    // Where the player would be fully open, on screen: its
                    // position now, less the sheet's offset that placed it
                    // there. Constant for the life of the sheet, and that
                    // plus the offset of any later frame is where the
                    // player is in that frame.
                    //
                    // Measured once, and again only if the player's size
                    // changes: this is called on every frame the sheet
                    // moves, with the same answer each time. Measuring it
                    // each time also walked the artwork back and forth by a
                    // pixel, the sheet rounding its offset its own way.
                    if (dockFrame.anchor != null && dockFrame.anchorFor == coordinates.size) {
                        return@onGloballyPositioned
                    }
                    val offset = dock.offset() ?: return@onGloballyPositioned
                    dockFrame.anchorFor = coordinates.size
                    dockFrame.anchor = coordinates.positionOnScreen() - Offset(0f, offset.toInt().toFloat())
                    if (!dock.attached) dock.attached = true
                }
            } else {
                Modifier
            },
        )
        // Outside the fade below, so the artwork is the one thing on the
        // player that doesn't fade on its way to the bar. Drawn on top of
        // everything while it travels, as the one thing still moving.
        .drawWithContent {
            drawContent()
            if (docking()) {
                translate(dockFrame.portalLeft, dockFrame.portalTop) {
                    val portalScale = dockFrame.portalScale
                    scale(portalScale, portalScale, pivot = Offset.Zero) { drawLayer(sleeveLayer) }
                }
                // ...and then the title and the artist, on their way into the
                // bar. Everything else on the player is allowed to be behind
                // the artwork while it leaves; these are not, because the band
                // the artwork's lower edge crosses is theirs.
                //
                // They travel rather than fade: the position and the size are
                // worked out against the mini player's own title, which is in
                // another window and is found in screen coordinates. The sheet
                // is `offset` below where it was when it was open, so a screen
                // point is that much further down the player's own box.
                val anchor = dockFrame.anchor
                val offset = dock?.offset()
                val target = dock?.miniTextOnScreen()
                if (creditsHandover() && anchor != null && offset != null &&
                    target != null && dockFrame.creditsKnown
                ) {
                    val progress = (1f - dockT()).coerceIn(0f, 1f)
                    // The title travels first and hands over last. It is home
                    // by the time the sheet's own dimming has cleared the bar
                    // — so the words it is replacing are already lit when it
                    // arrives — and the dissolve into them happens in place,
                    // over the last stretch of the trip.
                    val travel = creditsTravel(progress)
                    val from = Offset(dockFrame.creditsLeft, dockFrame.creditsTop)
                    val to = creditsDockTarget(target, anchor, offset)
                    val at = Offset(
                        from.x + (to.x - from.x) * travel,
                        from.y + (to.y - from.y) * travel,
                    )
                    // The bar's title is smaller than the player's. The title
                    // that flies there has to end up the size it is going to
                    // be, or it arrives as the right words at the wrong size.
                    val ratio = creditsTitleRatio()
                    val size = 1f + (ratio - 1f) * travel
                    creditsLayer.alpha = creditsHandoverAlpha(progress)
                    translate(at.x, at.y) {
                        scale(size, size, pivot = Offset.Zero) { drawLayer(creditsLayer) }
                    }
                } else if (dockFrame.creditsKnown) {
                    // Nothing to fly to this trip: the mini player's own title
                    // has not been laid out, so there is no target and no
                    // dissolve to run. Keep the recorded words where they are
                    // — on top of the sleeve drawn a few lines up, which is
                    // the point. Left to the layout, they were under the
                    // artwork for the whole of the open, and the title and
                    // artist slid out from behind the cover when it settled.
                    creditsLayer.alpha = 1f
                    val inPlace = 1f - 0.2f * p()
                    translate(dockFrame.creditsLeft, dockFrame.creditsTop) {
                        scale(inPlace, inPlace, pivot = Offset.Zero) { drawLayer(creditsLayer) }
                    }
                }
            }
        }
        // Never quite zero while docking. A layer at alpha 0 is skipped
        // outright, children and all — and the sleeve records itself for
        // the draw above from inside it, so for the last stretch of the
        // way home the cover went on being drawn from a stale recording,
        // bigger and higher than the bar's. One step of 255 is nothing to
        // look at and keeps the subtree drawing.
        .graphicsLayer { alpha = if (docking()) dockFade().coerceAtLeast(DOCK_FADE_FLOOR) else 1f }
}

/** The least the faded player is drawn at while docking — see the player's fade. */
internal const val DOCK_FADE_FLOOR = 1f / 255f

/**
 * What one frame of docking into the mini player worked out — see
 * [PlayerDock]. Written while the sleeve is laid out and read further down
 * the same frame, by the tile inside the sleeve and by the player's draw; plain
 * fields rather than state, because nothing should recompose or re-lay out for
 * them — the sheet's offset already drives every frame that reads them.
 */
internal class DockFrame {
    /**
     * Where the player sits on screen with the sheet fully open. State, the
     * one field here that is: it arrives a frame after the sheet first lays
     * out, and the frame it arrives in has to hear about it.
     */
    var anchor: Offset? by mutableStateOf(null)
    /** The player's size [anchor] was measured at. */
    var anchorFor = IntSize.Zero
    /** The mini player's cover, in the sleeve's box. */
    var miniInBox: Rect? = null
    /** The sheet's offset this frame, in the whole pixels it is placed at. */
    var offset = 0f
    /** How much of that the artwork rides — see `dockRide`. */
    var ride = 0f
    /** The sleeve as laid out, in the same box. */
    var sleeve: Rect = Rect.Zero
    /**
     * The sleeve's corner as a shape, made again only when its radius moves:
     * both of the sleeve's clipping layers ask for it, on every frame of any
     * transition, and an unchanged radius needs no new shape.
     */
    private var cornerRadius = Dp.Unspecified
    private var corner: Shape = RectangleShape
    fun cornerShape(radius: Dp): Shape {
        if (radius != cornerRadius) {
            cornerRadius = radius
            corner = RoundedCornerShape(radius)
        }
        return corner
    }
    /** Where the sleeve's pixels land in the player, for drawing it on top. */
    var portalLeft = 0f
    var portalTop = 0f
    /**
     * Where the player's own title and artist are, in the player's box.
     *
     * Written while the credits are laid out and read by the draw that flies
     * them into the mini player. A recorded layer carries no placement of its
     * own, which is the same reason [portalLeft] and [portalTop] exist for
     * the sleeve.
     */
    var creditsLeft = 0f
    var creditsTop = 0f
    /** Whether the credits have been placed at least once. */
    var creditsKnown = false
    /**
     * How much smaller than laid out the sleeve is drawn there. Always 1 for
     * the portrait sleeve, which is re-laid out at its size each frame; the
     * landscape one keeps its layout and is scaled down onto the cover.
     */
    var portalScale = 1f
    /** The player itself, for placing the landscape sleeve within it. */
    var host: LayoutCoordinates? = null
    /** The landscape sleeve, as last placed. */
    var landscapeArt: LayoutCoordinates? = null
}

/**
 * Horizontal fling skips tracks; the artwork follows the finger through
 * [motion] so the gesture has something to hold on to.
 *
 * Stands down while the pipeline or cast dialog is up or a panel's list is
 * scrolling, and answers [onBlockedControl] instead of skipping while a party
 * host has locked the controls.
 */
internal fun Modifier.skipSwipeGesture(
    pipelineOpen: Boolean,
    castOpen: Boolean,
    panelScrolling: Boolean,
    controlsLocked: Boolean,
    swipeThreshold: Float,
    haptics: Haptics,
    onBlockedControl: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    motion: TrackChangeMotion,
): Modifier = pointerInput(pipelineOpen, castOpen, panelScrolling, controlsLocked) {
        if (pipelineOpen || castOpen || panelScrolling) return@pointerInput
        var total = 0f
        // One per gesture block, fed the changes the detector already hands
        // over: the release's speed is what lets a quick flick skip.
        val velocity = VelocityTracker()
        val flingVelocity = SWIPE_FLING_DP_PER_S.dp.toPx()
        // Whether this block holds the art. The detector doesn't call
        // onDragCancel when the block itself is cancelled — a key changing
        // mid-drag, or the gesture leaving the screen — so the hold is let go
        // of on the way out instead of leaving the art under a finger that has
        // gone.
        var holding = false
        // When the finger last moved sideways, so a release after a pause
        // isn't read as the flick that came before it.
        var lastMoveNanos = 0L
        try {
            detectHorizontalDragGestures(
                onDragStart = {
                    total = 0f
                    velocity.resetTracking()
                    holding = true
                    motion.dragStart()
                },
                onDragCancel = {
                    holding = false
                    motion.springBack()
                },
                onDragEnd = {
                    holding = false
                    val direction = trackSwipeDirection(
                        total = total,
                        velocity = releaseVelocity(
                            velocity = velocity.calculateVelocity().x,
                            sinceLastMoveMs = (System.nanoTime() - lastMoveNanos) / 1_000_000L,
                        ),
                        threshold = swipeThreshold,
                        flingVelocity = flingVelocity,
                    )
                    when {
                        // Still tracks the finger and still springs back, so the
                        // sleeve does not feel dead — it just says why it did not
                        // move on.
                        controlsLocked -> {
                            if (direction != 0) onBlockedControl()
                            motion.springBack()
                        }
                        // Nowhere to go: back it comes, as for a short drag.
                        direction == 1 && !motion.hasNext -> motion.springBack()
                        // The same two buzzes the transport glyphs give, so swiping
                        // the sleeve and tapping skip feel like one gesture with two
                        // spellings. The art leaves before the call, so it is already
                        // moving the frame the finger lifts.
                        direction == 1 -> {
                            haptics.play(Haptic.SkipNext)
                            motion.commit(1)
                            onNext()
                        }
                        direction == -1 -> {
                            haptics.play(Haptic.SkipPrevious)
                            motion.commit(-1)
                            onPrevious()
                        }
                        else -> motion.springBack()
                    }
                },
                onHorizontalDrag = { change, delta ->
                    total += delta
                    velocity.addPointerInputChange(change)
                    lastMoveNanos = System.nanoTime()
                    motion.drag(total, swipeThreshold)
                },
            )
        } finally {
            if (holding) motion.springBack()
        }
    }

/**
 * Where the flying credits land, in the player's own box: the mini player's
 * title, [target], found on screen.
 *
 * [anchor] is where the player's top-left sits on screen with the sheet fully
 * open, and the sheet is [offset] below that this frame. The sheet only moves
 * down, so the offset comes off the vertical coordinate alone — as it does for
 * the sleeve's own trip to the cover.
 */
internal fun creditsDockTarget(target: Rect, anchor: Offset, offset: Float): Offset =
    Offset(target.left - anchor.x, target.top - anchor.y - offset)
