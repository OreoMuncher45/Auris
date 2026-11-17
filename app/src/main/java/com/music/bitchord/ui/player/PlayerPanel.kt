package com.music.bitchord.ui.player

// Ported from Zenith (rezapranata089-bit/Zenith, GPL-3.0), a sibling fork of
// BitChord: the mini player's pill and the full player as one panel.

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.ui.components.ExplicitSongTitle
import com.music.bitchord.ui.components.GLASS_EDGE_COLOR
import com.music.bitchord.ui.components.GLASS_EDGE_WIDTH
import com.music.bitchord.ui.components.MiniPlayerNextGlyph
import com.music.bitchord.ui.components.glassContentColor
import com.music.bitchord.ui.components.liquidGlass
import com.music.bitchord.ui.components.MiniArt
import com.music.bitchord.ui.components.miniArtPadding
import com.music.bitchord.ui.components.miniPlayerGestures
import com.music.bitchord.ui.components.optimizedHazeEffect
import com.music.bitchord.ui.components.rememberRemoteArtworkUrl
import com.music.bitchord.ui.components.thumbnailBorder
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlin.math.roundToInt

/** Up to this much of the open fraction the mini row is still on the panel. */
private const val MINI_ROW_UNTIL = 0.2f

/**
 * The same two, in a light theme. The pill there is pale and the player is
 * dark, so there is no middle that matches either: the less of the drag spent
 * between them, the less of it is a muddy grey with the pill's dark text
 * half-visible on it.
 */
private const val MINI_ROW_UNTIL_LIGHT = 0.1f
private const val PANEL_FILL_UNTIL_LIGHT = 0.1f
private const val PANEL_FILL_UNTIL_DARK = 0.2f

/** The rounding the panel's top corners keep on the way up, as a sheet's do. */
private const val PANEL_SHEET_CORNER_DP = 22f

/** The panel's radius, following its progress — read at draw, never composed. */
private class PanelCorner(private val dock: PlayerDock) : CornerSize {
    override fun toPx(shapeSize: Size, density: Density): Float =
        dock.panelCornerPx(PANEL_SHEET_CORNER_DP * density.density)
}

/** What the mini player's pill is made of, so the panel can be made of it too. */
internal enum class PanelPillMaterial {
    /** The liquid glass bar's docked player. */
    Glass,

    /** The plain mini player, frosted over the page. */
    Frost,

    /** The plain mini player under "reduce dynamic blur": a solid surface. */
    Solid,
}

/**
 * The mini player's pill and the full player as ONE surface, in the app's own
 * window — not a sheet in a window of its own.
 *
 * The container is a single shape in the pill's material, whose size and
 * corners follow [PlayerDock.panelBounds]. The pill's own row
 * ([PanelMiniRow]) fades out of it and the full player fades in, both on the
 * container; the real pill underneath stands aside while this is up (see
 * [PlayerDock.panelShown]), so there is never a second panel to see. As it
 * opens a plain fill comes in over the material and the material is dropped
 * once that is opaque — the moment its blur stops costing anything.
 *
 * Without a [dock] ("reduce animation") it is just the player, full window.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun PlayerPanel(
    dock: PlayerDock?,
    motion: PlayerSheetMotion?,
    song: Song,
    isPlaying: Boolean,
    isLoading: Boolean,
    controlsLocked: Boolean,
    onBlockedControl: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    scrimAlpha: Float,
    material: PanelPillMaterial,
    hazeState: HazeState,
    onLaidOut: (IntSize) -> Unit,
    modifier: Modifier = Modifier,
    /** Drawn above the panel, unclipped — the ⋯ menu. */
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    val full = remember { arrayOf(IntSize.Zero) }
    // Whatever takes the panel away, the pill comes back with it.
    DisposableEffect(dock) {
        onDispose { dock?.releasePanel() }
    }
    val shape = remember(dock) {
        RoundedCornerShape(if (dock != null) PanelCorner(dock) else CornerSize(0.dp))
    }
    val covered by remember(dock) {
        derivedStateOf { dock != null && dock.attached && dock.openFraction() >= 1f }
    }
    val lightTheme = MaterialTheme.colorScheme.background.let {
        (0.299f * it.red + 0.587f * it.green + 0.114f * it.blue) > 0.5f
    }
    val rowUntil = if (lightTheme) MINI_ROW_UNTIL_LIGHT else MINI_ROW_UNTIL
    val fillUntil = if (lightTheme) PANEL_FILL_UNTIL_LIGHT else PANEL_FILL_UNTIL_DARK
    val miniVisible by remember(dock, rowUntil) {
        derivedStateOf { dock != null && dock.openFraction() < rowUntil }
    }
    val materialNeeded by remember(dock, fillUntil) {
        derivedStateOf { dock != null && dock.panelFill(fillUntil) < 1f }
    }
    val surfaceColor = MaterialTheme.colorScheme.surface
    val surface = when {
        dock == null || covered || !materialNeeded -> Modifier
        material == PanelPillMaterial.Glass -> Modifier.liquidGlass(shape)
        material == PanelPillMaterial.Frost -> Modifier
            .clip(shape)
            .optimizedHazeEffect(state = hazeState, style = HazeMaterials.thin(surfaceColor))
            .border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, shape)
        else -> Modifier
            .background(surfaceColor, shape)
            .border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, shape)
    }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged {
                full[0] = it
                onLaidOut(it)
            }
            .onGloballyPositioned { dock?.panelPlaced(it) }
            .drawBehind {
                if (dock == null) return@drawBehind
                val open = dock.openFraction()
                // Not under a portrait player that is fully up: its own
                // background is opaque, and a full-screen blend behind it
                // would be paid on every frame for nothing anyone can see.
                if (open > 0f && !(open >= 1f && dock.attached)) {
                    drawRect(Color.Black.copy(alpha = scrimAlpha * open))
                }
            },
    ) {
        Box(
            (if (dock != null) Modifier.panelBounds(dock) else Modifier.fillMaxSize())
                .then(surface)
                // The panel is the touch target wherever it stands, so a tap
                // on a part of the player that has no control of its own
                // never falls through to the page underneath.
                .pointerInput(Unit) {
                    awaitEachGesture { awaitFirstDown(requireUnconsumed = false) }
                }
                .then(if (motion != null) Modifier.playerPullDown(motion) else Modifier),
        ) {
            if (dock != null && !covered) {
                val panelColor = dock.panelColor
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = dock.panelFill(fillUntil) }
                        .background(panelColor, shape),
                )
            }
            Box(Modifier.fillMaxSize().clip(shape)) {
                Box(
                    (if (dock != null) Modifier.windowSized(dock) { full[0] } else Modifier.fillMaxSize())
                        .graphicsLayer {
                            // Once the player takes part in the docking it fades
                            // itself (and flies its cover and credits outside
                            // that fade), so the panel must not.
                            alpha = if (dock == null || dock.attached) {
                                1f
                            } else {
                                (dock.openFraction() / rowUntil).coerceIn(0f, 1f)
                            }
                        },
                ) { content() }
                if (dock != null && miniVisible) {
                    val density = LocalDensity.current
                    val windowWidth = LocalWindowInfo.current.containerSize.width
                    val pill = remember(dock) { dock.miniPillInWindow() }
                    val rowHeight = with(density) { (pill?.height ?: 0f).toDp() }
                        .takeIf { it > 0.dp } ?: 56.dp
                    // Inline in the tab bar the glass pill is narrow and drops
                    // its artist line and skip button; the row does the same.
                    val inline = material == PanelPillMaterial.Glass &&
                        pill != null && pill.width < windowWidth * 0.8f
                    PanelMiniRow(
                        dock = dock,
                        song = song,
                        isPlaying = isPlaying,
                        isLoading = isLoading,
                        glass = material == PanelPillMaterial.Glass,
                        inline = inline,
                        controlsLocked = controlsLocked,
                        onBlockedControl = onBlockedControl,
                        onPlayPause = onPlayPause,
                        onNext = onNext,
                        onPrevious = onPrevious,
                        motion = motion,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .fillMaxWidth()
                            .height(rowHeight)
                            .graphicsLayer {
                                alpha = (1f - dock.openFraction() / rowUntil).coerceIn(0f, 1f)
                            },
                    )
                }
            }
        }
        overlay()
    }
}

/** The container's place this frame: size and position come from the dock. */
private fun Modifier.panelBounds(dock: PlayerDock): Modifier = layout { measurable, constraints ->
    val window = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
    val rect = dock.panelBounds(window)
    val width = rect.width.roundToInt().coerceAtLeast(1)
    val height = rect.height.roundToInt().coerceAtLeast(1)
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(constraints.maxWidth, constraints.maxHeight) {
        placeable.place(rect.left.roundToInt(), rect.top.roundToInt())
    }
}

/**
 * The full player, laid out at the whole window's size whatever the panel is,
 * and put back where the window's left edge is — so the panel reveals it
 * instead of squeezing it.
 */
private fun Modifier.windowSized(dock: PlayerDock, full: () -> IntSize): Modifier =
    layout { measurable, constraints ->
        val size = full().takeIf { it.width > 0 && it.height > 0 }
            ?: IntSize(constraints.maxWidth, constraints.maxHeight)
        val placeable = measurable.measure(Constraints.fixed(size.width, size.height))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val rect = dock.panelBounds(Size(size.width.toFloat(), size.height.toFloat()))
            placeable.place(-rect.left.roundToInt(), 0)
        }
    }

/**
 * The pill's row, on the panel: the same cover, title, artist and transport as
 * the pill, where the pill had them — laid out as whichever pill is underneath
 * lays them out ([com.music.bitchord.ui.components.MiniPlayer] or the glass
 * bar's docked player, expanded or inline).
 */
@Composable
private fun PanelMiniRow(
    song: Song,
    isPlaying: Boolean,
    isLoading: Boolean,
    glass: Boolean,
    inline: Boolean,
    controlsLocked: Boolean,
    onBlockedControl: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    motion: PlayerSheetMotion?,
    modifier: Modifier,
    dock: PlayerDock,
) {
    val haptics = rememberHaptics()
    val contentColor = if (glass) glassContentColor() else MaterialTheme.colorScheme.onBackground
    val secondary = if (glass) contentColor.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
    // The pill's own figures, not a copy of them: the player's cover lands on
    // the pill's art, and this row stands in for the pill while it does.
    val art = MiniArt.of(inline)
    val glyphSlot: Dp = if (inline) 32.dp else 40.dp
    val glyphSize: Dp = if (inline) 24.dp else 32.dp
    Box(
        contentAlignment = Alignment.Center,
        // The row sees the height the pill is stretched to, so the cover can sit
        // on the centre of that pill's end cap rather than the row's.
        propagateMinConstraints = true,
        modifier = modifier
            .miniPlayerGestures(
                pull = motion,
                onNext = {
                    haptics.play(Haptic.SkipNext)
                    onNext()
                },
                onPrevious = {
                    haptics.play(Haptic.SkipPrevious)
                    onPrevious()
                },
                locked = controlsLocked,
                onBlocked = onBlockedControl,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { motion?.open() },
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .miniArtPadding(art),
        ) {
            AsyncImage(
                model = rememberRemoteArtworkUrl(song)?.artworkAt(ROW_ART_PX),
                contentDescription = null,
                modifier = Modifier
                    .size(art.size)
                    // The player's own cover is the one that flies; this one
                    // stands aside while it does.
                    .graphicsLayer { alpha = if (dock.coversMiniArt) 0f else 1f }
                    .clip(art.shape)
                    .then(if (glass) Modifier else Modifier.thumbnailBorder(art.shape))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.width(art.gap))
            if (inline) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Column(Modifier.weight(1f)) {
                    ExplicitSongTitle(
                        song = song,
                        style = MaterialTheme.typography.titleMedium,
                        color = contentColor,
                    )
                    Text(
                        text = song.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (isLoading) {
                Box(Modifier.size(glyphSlot), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        color = contentColor,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(if (inline) 18.dp else 22.dp),
                    )
                }
            } else {
                IconButton(
                    onClick = {
                        haptics.play(if (isPlaying) Haptic.Pause else Haptic.Resume)
                        onPlayPause()
                    },
                    modifier = Modifier.size(glyphSlot),
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(if (isPlaying) R.string.pause else R.string.play),
                        tint = contentColor,
                        modifier = Modifier.size(glyphSize),
                    )
                }
            }
            if (!inline) {
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        haptics.play(Haptic.SkipNext)
                        onNext()
                    },
                    enabled = !controlsLocked,
                    modifier = Modifier.size(glyphSlot),
                ) {
                    MiniPlayerNextGlyph(tint = contentColor.copy(alpha = if (controlsLocked) 0.3f else 1f))
                }
            }
        }
    }
}

/**
 * Drag the open player down to put it away — what the modal sheet used to do
 * for it. A drag nothing inside claims, and the pull a list leaves over when
 * it is already at its top, both move the panel through [PlayerSheetMotion]:
 * the same motion the pill's pull and every close run on.
 */
@Composable
internal fun Modifier.playerPullDown(motion: PlayerSheetMotion): Modifier {
    val connection = remember(motion) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Back up first: a pulled panel returns before the list scrolls again.
                if (!motion.pulling || available.y >= 0f) return Offset.Zero
                return Offset(0f, motion.pullBy(available.y))
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source != NestedScrollSource.UserInput || available.y <= 0f) {
                    return Offset.Zero
                }
                return Offset(0f, motion.pullBy(available.y))
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!motion.pulling) return Velocity.Zero
                motion.releasePull(available.y)
                return available
            }
        }
    }
    val dragState = rememberDraggableState { delta -> motion.pullBy(delta) }
    return this
        .nestedScroll(connection)
        .draggable(
            state = dragState,
            orientation = Orientation.Vertical,
            onDragStopped = { velocity -> motion.releasePull(velocity) },
        )
}
