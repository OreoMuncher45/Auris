package com.music.bitchord.ui.player

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import com.music.bitchord.ui.replay.ShareAction
import com.music.bitchord.ui.replay.cacheForSharing
import com.music.bitchord.ui.replay.saveToGallery
import com.music.bitchord.ui.replay.sendIntent
import com.music.bitchord.ui.screens.SettingsSwitch
import dev.chrisbanes.haze.HazeState
import java.io.File
import kotlinx.coroutines.launch

/**
 * The picked lines, drawn, with the things you can do to the picture or the
 * video it becomes.
 *
 * Built on [PlayerDrawer] rather than on a `ModalBottomSheet` for the same reason
 * as every other sheet in this package: it is inside the player, which is itself
 * already a modal sheet, and nesting the two leaves the inner one with nothing
 * to be modal over. It also means this drawer gets the player's own frosted
 * material, the drag-to-dismiss gesture and the scrim fade for free.
 *
 * The card is rendered once by [lyricsCardPainter], and the same painter draws
 * every frame of the video, so the still and the moving picture cannot disagree.
 * The card wears the record's own colour, carried in on the [request].
 */
@Composable
internal fun AndroidLyricsShareSheet(
    hazeState: HazeState,
    request: LyricsShareRequest,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()

    var image by remember(request) { mutableStateOf<Bitmap?>(null) }
    var painter by remember(request) { mutableStateOf<LyricsCardPainter?>(null) }
    var failed by remember(request) { mutableStateOf(false) }
    var saved by remember(request) { mutableStateOf(false) }
    // Same picture, cut to the 9:16 frame a story wants instead of to its lines.
    var story by remember(request) { mutableStateOf(false) }
    // Remembered for the session: someone who takes the mark off, or keeps the
    // instrumentals, means it for the next share too.
    var showLogo by remember { mutableStateOf(true) }
    var includeInstrumentals by remember { mutableStateOf(true) }
    // The moving picture is the point of a synced verse; unsynced lines have
    // nothing to move, so the sheet opens on the card there.
    val timelinePossible = remember(request) {
        request.lines.any { !it.isGap && it.startMs != null }
    }
    var asVideo by remember(request) { mutableStateOf(timelinePossible) }
    val isVideo = asVideo && timelinePossible

    // Saving/exporting runs from the tap until the file is in the gallery.
    var exporting by remember(request) { mutableStateOf(false) }
    var saving by remember(request) { mutableStateOf(false) }
    var exported by remember(request) { mutableStateOf<Pair<String, File>?>(null) }

    LaunchedEffect(request, story, showLogo, includeInstrumentals) {
        image = null
        painter = null
        failed = false
        saved = false
        runCatching {
            val paint = lyricsCardPainter(
                context = context,
                card = request,
                story = story,
                showLogo = showLogo,
                includeInstrumentals = includeInstrumentals,
            )
            image = paint.render(null)
            painter = paint
        }.onFailure { failed = true }
    }

    // One export per look, reused by Save and Share alike.
    suspend fun videoFile(): File? {
        val paint = painter ?: return null
        val look = "$story|$showLogo|$includeInstrumentals"
        exported?.takeIf { it.first == look && it.second.exists() }?.let { return it.second }
        exporting = true
        return try {
            // The export lays its own backdrop, so the card it is handed is bare.
            val overlay = lyricsCardPainter(
                context = context,
                card = request,
                story = story,
                showLogo = showLogo,
                transparent = true,
                includeInstrumentals = includeInstrumentals,
            )
            exportLyricsVideo(context, request, overlay, includeInstrumentals)
                ?.also { exported = look to it }
        } finally {
            exporting = false
        }
    }

    PlayerDrawer(
        hazeState = hazeState,
        title = stringResource(R.string.lyrics_share_title),
        onDismiss = onDismiss,
        modifier = modifier,
        titleGap = 2.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.lyrics_share_description),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
            )

            ShareSegments(
                options = listOf(
                    stringResource(R.string.lyrics_share_video),
                    stringResource(R.string.lyrics_share_image),
                ),
                selected = if (isVideo) 0 else 1,
                disabled = if (timelinePossible) emptySet() else setOf(0),
                onSelect = {
                    haptics.play(Haptic.Tap)
                    asVideo = it == 0
                },
            )

            val bitmap = image
            // The card is only as tall as its lines, so the frame follows it:
            // a strip comes out wide, a full verse narrow, both about the same
            // height on screen rather than a fixed box with bands to spare.
            val ratio = bitmap?.let { it.width.toFloat() / it.height } ?: (9f / 16f)
            val preview = Modifier
                .fillMaxWidth((0.72f * ratio).coerceIn(0.36f, 0.92f))
                .aspectRatio(ratio)
                .clip(RoundedCornerShape(18.dp))
            Box(contentAlignment = Alignment.Center) {
                if (bitmap == null) {
                    Box(
                        modifier = preview.background(Color.White.copy(alpha = 0.06f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(26.dp),
                            strokeWidth = 2.5.dp,
                            color = Color.White.copy(alpha = 0.75f),
                        )
                    }
                } else {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.your_lyrics_card),
                        modifier = preview,
                    )
                }
                if (exporting) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(26.dp))
                            .background(Color.Black.copy(alpha = 0.35f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(26.dp),
                            strokeWidth = 2.5.dp,
                            color = Color.White.copy(alpha = 0.85f),
                        )
                    }
                }
            }
            if (isVideo) {
                Text(
                    text = stringResource(R.string.lyrics_share_video_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                )
            }

            ShareAction(
                label = stringResource(if (story) R.string.lyrics_share_story_revert else R.string.lyrics_share_story),
                icon = if (story) Icons.Rounded.Undo else Icons.Rounded.PhoneIphone,
                accent = false,
                enabled = image != null,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    haptics.play(Haptic.Tap)
                    story = !story
                },
            )

            ShareToggleRow(
                label = stringResource(R.string.lyrics_share_show_logo),
                checked = showLogo,
                onCheckedChange = {
                    haptics.play(Haptic.Tap)
                    showLogo = it
                },
            )

            ShareToggleRow(
                label = stringResource(R.string.lyrics_share_includes_instrumentals),
                checked = includeInstrumentals,
                onCheckedChange = {
                    haptics.play(Haptic.Tap)
                    includeInstrumentals = it
                },
            )

            if (failed) {
                Text(
                    text = stringResource(R.string.couldnt_draw_picture),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ShareAction(
                    label = stringResource(R.string.save),
                    icon = Icons.Rounded.Download,
                    accent = false,
                    enabled = image != null && !exporting && !saving,
                    modifier = Modifier.weight(1f),
                    // Once the file is in the gallery the button says so
                    // itself — a check and "Saved!" — so the line under the
                    // card does not have to.
                    saved = saved,
                    savedLabel = stringResource(R.string.saved_bang),
                    onClick = {
                        val drawn = image
                        if (drawn != null) {
                            haptics.play(Haptic.Tap)
                            saved = false
                            saving = true
                            scope.launch {
                                val done = try {
                                    if (isVideo) {
                                        val video = videoFile()
                                        video != null && saveVideoToGallery(context, video, request.song.title)
                                    } else {
                                        saveToGallery(context, drawn, request.song.title, LYRICS_FILE_PREFIX)
                                    }
                                } finally {
                                    saving = false
                                }
                                if (!done) failed = true
                                saved = done
                            }
                        }
                    },
                )
                ShareAction(
                    label = stringResource(R.string.share),
                    icon = Icons.Rounded.IosShare,
                    accent = true,
                    enabled = image != null && !exporting && !saving,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val drawn = image
                        if (drawn != null) {
                            haptics.play(Haptic.Tap)
                            scope.launch {
                                val send = if (isVideo) {
                                    videoFile()?.let { shareVideoIntent(context, it) }
                                } else {
                                    cacheForSharing(context, drawn, LYRICS_FILE_NAME)?.let(::sendIntent)
                                }
                                if (send == null) {
                                    failed = true
                                    saved = false
                                } else {
                                    context.startActivity(
                                        Intent.createChooser(send, context.getString(R.string.share_your_lyrics)),
                                    )
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

/**
 * The two shapes a share can take, as a segmented control in the player's own
 * white alphas. A video needs synced lines; when it can't be made the option
 * stays visible but dim, which says why without a line of explanation.
 */
@Composable
private fun ShareSegments(
    options: List<String>,
    selected: Int,
    disabled: Set<Int>,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val chosen = index == selected
            val enabled = index !in disabled
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (chosen) Color.White.copy(alpha = 0.20f) else Color.Transparent)
                    .clickable(enabled = enabled) { if (!chosen) onSelect(index) }
                    .alpha(if (enabled) 1f else 0.35f)
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (chosen) FontWeight.W700 else FontWeight.W500,
                    color = Color.White,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * One label-and-switch row of the sheet.
 *
 * The whole row is the target, not just the switch: a control this size is
 * looked at, not aimed at, and the switch only shows the state the row holds.
 */
@Composable
private fun ShareToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(start = 18.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            modifier = Modifier.weight(1f),
        )
        // The row is the toggle; the switch only shows its state.
        SettingsSwitch(checked = checked, onCheckedChange = null)
    }
}

/**
 * Two names, deliberately not the Replay's.
 *
 * The cache name so a replay share and a lyrics share cannot overwrite each
 * other while the receiving app is still reading the first, and the gallery
 * prefix so a saved card in the pictures folder says which feature produced it.
 */
private const val LYRICS_FILE_NAME = "lyrics.png"
private const val LYRICS_FILE_PREFIX = "auris-lyrics"