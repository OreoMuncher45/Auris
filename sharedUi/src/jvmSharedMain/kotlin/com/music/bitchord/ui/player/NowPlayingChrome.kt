package com.music.bitchord.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.zIndex
import com.music.bitchord.data.model.Song
import com.music.bitchord.sharedui.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The strip across the top of the portrait player: the status-bar scrim, the
 * chevron that closes the player and the "Playing from…" caption.
 *
 * Everything that moves here is asked for at draw time — [playerHeight],
 * [chromeAlpha] and [captionAlpha] — so the strip never recomposes for a frame
 * of the collapse or of Spotify's chrome fading.
 */
@Composable
internal fun PlayerTopStrip(
    height: Dp,
    statusBarTop: Dp,
    playerHeight: () -> Int,
    chromeAlpha: () -> Float,
    onClose: () -> Unit,
    caption: Boolean,
    captionShown: () -> Boolean,
    captionAlpha: () -> Float,
    song: Song,
    playedBy: String?,
    onOpenCaption: () -> Unit,
) {
    val closeLabel = stringResource(Res.string.close_player)
    // The top of the screen is always shaded, whatever the cover: 40% black
    // at the very top, gone by 14% of the screen. It keeps the system icons
    // and the "Playing from..." line readable over a bright sleeve, and it
    // is the same treatment on every cover so the player does not flicker
    // brighter when the album changes.
    val topScrimBrush = remember {
        Brush.verticalGradient(
            0f to Color.Black.copy(alpha = TOP_SCRIM_ALPHA),
            1f to Color.Transparent,
        )
    }

    // The only strip that passes drags through to the sheet, so the
    // player closes from the handle and the space around it — not from
    // a stray downward swipe on the artwork or the controls.
    //
    // Drawn above the column under it, and the status-bar scrim with
    // it: the full-bleed sleeve lives in that column and runs up behind
    // both, and the handle, the caption and the scrim all belong on
    // top of the artwork. The scrim reaches up past the strip's own
    // top to the screen's, the band it always covered.
    Box(
        modifier = Modifier
            .zIndex(1f)
            .fillMaxWidth()
            .height(height)
            .drawBehind {
                // Translated rather than offset: the gradient is laid
                // out from the canvas origin, not from the rect's. It
                // runs past this strip to 14% of the player, over the
                // top of the artwork, which is what the caption and
                // the system icons actually sit on.
                //
                // Up by the whole top inset rather than by the status
                // bar alone: the strip now starts below the cutout as
                // well, and the scrim still has to reach the screen's
                // own top edge to darken the camera's corner.
                val above = statusBarTop.toPx()
                val scrimHeight = playerHeight()
                    .takeIf { it > 0 }
                    ?.let { it * TOP_SCRIM_FRACTION }
                    ?: (size.height + above)
                translate(top = -above) {
                    drawRect(
                        brush = topScrimBrush,
                        size = Size(size.width, scrimHeight),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // The way out, at the top left where the reference player puts
        // it: a chevron that reads as "down to the rest of the app".
        // It replaces the grab handle — a pill says "drag me", and a
        // mark that looks like a button should do what a button does.
        // The strip still hands drags to the sheet, so the gesture is
        // untouched; the chevron is simply the tap for it.
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                // Centred on the caption block rather than the strip's
                // top: the reference player draws it level with the
                // two caption lines, not floating above them.
                .offset(x = PLAYER_GUTTER - 10.dp, y = 24.dp)
                .size(44.dp)
                .graphicsLayer { alpha = chromeAlpha() }
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onClose() }
                .semantics { contentDescription = closeLabel },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = PlayerIcons.ChevronDown.painter(),
                contentDescription = null,
                tint = LocalPlayerInk.current.value.copy(alpha = 0.92f),
                modifier = Modifier.size(24.dp),
            )
        }
        // [p] is the shared album-to-panel transition. Keeping this in
        // composition until its final frame gives the caption a real
        // fade on both entry and exit, but removes its click target
        // entirely once lyrics or the queue owns the player.
        if (caption) {
            WhileShown(captionShown) {
                PlaybackOriginCaptionStacked(
                    label = if (playedBy != null) {
                        stringResource(Res.string.played_by_label)
                    } else {
                        stringResource(Res.string.playing_from_label)
                    },
                    title = playedBy
                        ?: song.radioName
                        ?: song.playbackSource
                        ?: song.albumName
                        ?: stringResource(Res.string.queue),
                    onClick = onOpenCaption,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .graphicsLayer { alpha = captionAlpha() * chromeAlpha() },
                )
            }
        }
    }
}
