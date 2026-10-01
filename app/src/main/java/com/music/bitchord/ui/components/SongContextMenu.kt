package com.music.bitchord.ui.components

import android.provider.Settings
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.icons.BitChordIcons
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlin.math.roundToInt

/**
 * The track menu a *held* row lifts into, in the shape Apple Music uses: the
 * row pops out of its list as a card, the page behind it blurs, and the
 * actions hang underneath it.
 *
 * Only a hold opens this. The ⋮ keeps opening [SongActionsSheet] as a bottom
 * sheet — a tap on a small button is a request for the full menu, while a hold
 * on the row itself is a gesture about *that row*, and answering it where the
 * finger is keeps the row in view instead of sending the eye to the bottom of
 * the screen.
 *
 * [song] and [origin] come and go together: non-null while the menu is wanted,
 * null once anything has closed it. The last pair is kept here so the menu can
 * play its way back into the row after the caller has already let go of it.
 * [origin] is the held row's bounds in window coordinates — see
 * [LongPressOrigin].
 *
 * Both of the app's accessibility switches are honoured: "Reduce animation"
 * (or the system's animator scale at zero) turns the lift into a short fade
 * with nothing moving, and "Reduce dynamic blur" swaps the blurred page for a
 * plain dimming scrim, which is also the cost that setting exists to remove.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun SongContextMenu(
    song: Song?,
    origin: Rect?,
    hazeState: HazeState,
    onDismiss: () -> Unit,
    /**
     * Tapping the lifted card opens the song's album, as Apple Music's does.
     * Offered, chevron and all, only for a song that has an album to open.
     */
    onOpenAlbum: (Song) -> Unit,
    actions: @Composable (Song) -> Unit,
) {
    val requested = if (song != null && origin != null) song to origin else null
    var last by remember { mutableStateOf<Pair<Song, Rect>?>(null) }
    if (requested != null && requested != last) {
        SideEffect { last = requested }
    }
    val shown = requested ?: last ?: return
    val (menuSong, heldBounds) = shown
    val open = requested != null

    val context = LocalContext.current
    val appReduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val systemAnimationsOff = remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
    val reduceMotion = appReduceAnimation || systemAnimationsOff
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()

    val progress = remember { Animatable(0f) }
    LaunchedEffect(open) {
        if (open) {
            progress.animateTo(
                1f,
                if (reduceMotion) {
                    tween(durationMillis = 140, easing = LinearEasing)
                } else {
                    // Under-damped on purpose: the small overshoot is the "pop"
                    // of the row coming off the page.
                    spring(dampingRatio = 0.74f, stiffness = 420f)
                },
            )
        } else {
            progress.animateTo(
                0f,
                tween(durationMillis = if (reduceMotion) 110 else 220, easing = FastOutSlowInEasing),
            )
            last = null
        }
    }

    BackHandler(enabled = open, onBack = onDismiss)

    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // The scrim does less work when the blur is there to push the page back,
    // and more when it is the only thing doing it.
    val scrimColor = when {
        reduceDynamicBlur && dark -> Color.Black.copy(alpha = 0.62f)
        reduceDynamicBlur -> Color.Black.copy(alpha = 0.36f)
        dark -> Color.Black.copy(alpha = 0.38f)
        else -> Color.Black.copy(alpha = 0.12f)
    }
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh
    val hairline = if (dark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f)
    val cardShape = RoundedCornerShape(18.dp)
    val menuShape = RoundedCornerShape(16.dp)

    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val insets = WindowInsets.safeDrawing
    var overlayOffset by remember { mutableStateOf(Offset.Zero) }

    Layout(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { overlayOffset = it.positionInWindow() }
            // Once it is on its way out nothing on it answers any more — a
            // second tap on a row mid-exit would run its action twice.
            .then(if (open) Modifier else Modifier.pointerInput(Unit) { swallowAll() }),
        content = {
            // The page, pushed back. Everything behind the card and menu is
            // one layer so the fade is a single alpha rather than a blur
            // radius animated per frame.
            val backdropAlpha = { progress.value.coerceIn(0f, 1f) }
            Spacer(
                Modifier
                    .fillMaxSize()
                    .then(
                        if (reduceDynamicBlur) {
                            Modifier
                        } else {
                            Modifier.optimizedHazeEffect(
                                state = hazeState,
                                style = HazeMaterials.ultraThin(MaterialTheme.colorScheme.background),
                            ) {
                                alpha = backdropAlpha()
                            }
                        },
                    )
                    .graphicsLayer { alpha = backdropAlpha() }
                    .background(scrimColor)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = open,
                        onClick = onDismiss,
                    ),
            )
            PreviewCard(
                song = menuSong,
                shape = cardShape,
                surface = surface,
                hairline = hairline,
                elevated = !reduceMotion,
                showChevron = menuSong.albumId != null,
                onClick = { if (open) onOpenAlbum(menuSong) },
            )
            Column(
                Modifier
                    // Taps between rows land on the menu, not the scrim under it.
                    .pointerInput(Unit) {}
                    .clip(menuShape)
                    .background(surface.copy(alpha = if (reduceDynamicBlur) 1f else 0.96f))
                    .border(0.5.dp, hairline, menuShape),
            ) {
                actions(menuSong)
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val margin = 16.dp.roundToPx()
        val gap = 10.dp.roundToPx()
        val top = insets.getTop(this) + 8.dp.roundToPx()
        val bottom = height - insets.getBottom(this) - 8.dp.roundToPx()

        val backdrop = measurables[0].measure(Constraints.fixed(width, height))
        val cardWidth = (width - margin * 2).coerceAtMost(520.dp.roundToPx()).coerceAtLeast(0)
        val card = measurables[1].measure(Constraints(minWidth = cardWidth, maxWidth = cardWidth))
        val menuWidth = cardWidth.coerceAtMost(292.dp.roundToPx())
        val menuMaxHeight = (bottom - top - card.height - gap).coerceAtLeast(0)
        val menu = measurables[2].measure(
            Constraints(minWidth = menuWidth, maxWidth = menuWidth, maxHeight = menuMaxHeight),
        )

        // The held row, in this layout's own coordinates.
        val held = heldBounds.translate(-overlayOffset)
        // Centred on the row it came from, then pushed back on screen — on a
        // phone the clamp is what decides, on a tablet the row's position does.
        val cardX = (held.center.x - cardWidth / 2f).roundToInt()
            .coerceIn(margin, (width - margin - cardWidth).coerceAtLeast(margin))
        // Where the row was, unless that would leave the menu off the bottom
        // of the screen, in which case the card climbs until it fits.
        val total = card.height + gap + menu.height
        val cardY = (held.center.y - card.height / 2f).roundToInt()
            .coerceIn(top, (bottom - total).coerceAtLeast(top))
        val menuX = if (rtl) cardX + cardWidth - menuWidth else cardX
        val menuY = cardY + card.height + gap

        // How far the card has to travel back to sit over the row, and how
        // much smaller it has to be to match it — a feed card is a fraction
        // of the width the lifted card takes.
        val fromDx = held.center.x - (cardX + cardWidth / 2f)
        val fromDy = held.center.y - (cardY + card.height / 2f)
        val fromScale = (held.width / cardWidth.coerceAtLeast(1)).coerceIn(0.3f, 1.1f)

        layout(width, height) {
            backdrop.place(0, 0)
            card.placeWithLayer(cardX, cardY) {
                val p = progress.value
                if (reduceMotion) {
                    alpha = p.coerceIn(0f, 1f)
                } else {
                    val scale = lerp(fromScale, 1f, p)
                    scaleX = scale
                    scaleY = scale
                    translationX = lerp(fromDx, 0f, p)
                    translationY = lerp(fromDy, 0f, p)
                    // Opaque almost at once: it starts over the row it copies,
                    // so fading it in slowly would show both at the same time.
                    alpha = (p * 4f).coerceIn(0f, 1f)
                }
            }
            menu.placeWithLayer(menuX, menuY) {
                val p = progress.value
                alpha = ((p - 0.1f) / 0.9f).coerceIn(0f, 1f)
                if (!reduceMotion) {
                    // Grows out of the card's corner and travels with it.
                    transformOrigin = TransformOrigin(if (rtl) 1f else 0f, 0f)
                    val scale = lerp(0.55f, 1f, p)
                    scaleX = scale
                    scaleY = scale
                    translationX = lerp(fromDx, 0f, p)
                    translationY = lerp(fromDy, 0f, p)
                }
            }
        }
    }
}

private suspend fun PointerInputScope.swallowAll() {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}

/**
 * The held row as it looks lifted off the page: artwork, title, credit and
 * album, on a card of its own. Always the same card whatever was held — a row,
 * a feed tile — because what the menu is about is the song, not the tile.
 */
@Composable
private fun PreviewCard(
    song: Song,
    shape: RoundedCornerShape,
    surface: Color,
    hairline: Color,
    elevated: Boolean,
    showChevron: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .then(if (elevated) Modifier.shadow(18.dp, shape, clip = false) else Modifier)
            .pointerInput(Unit) {}
            .clip(shape)
            .background(surface)
            .border(0.5.dp, hairline, shape)
            .then(if (showChevron) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val artShape = RoundedCornerShape(10.dp)
        AsyncImage(
            model = song.artworkAt(ROW_ART_PX),
            contentDescription = null,
            modifier = Modifier
                .size(64.dp)
                .clip(artShape)
                .thumbnailBorder(artShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            ExplicitSongTitle(
                song = song,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            song.albumName?.takeIf { it.isNotBlank() && it != song.title }?.let { album ->
                Text(
                    text = album,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (showChevron) {
            Spacer(Modifier.width(8.dp))
            Icon(
                BitChordIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
