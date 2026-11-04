package com.music.bitchord.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.theme.AppFonts
import java.io.File

/**
 * The Customisation settings, as sheets.
 *
 * These used to be a hub page of their own — a half-dozen surfaces of colour,
 * order and type crowded into one screen and reachable only by leaving
 * Settings. They are settings like any other, so they live in the Settings
 * categories now: each is a row that opens a sheet, in the same shape as the
 * audio-quality and download-quality pickers next to it.
 */

/** The four player colour roles, with the player itself as the preview. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerColorsSheet(onDismiss: () -> Unit) {
    val accent by AppSettings.playerAccentColor.collectAsStateWithLifecycle()
    val seekbar by AppSettings.playerSeekbarColor.collectAsStateWithLifecycle()
    val text by AppSettings.playerTextColor.collectAsStateWithLifecycle()
    val lyrics by AppSettings.playerLyricsColor.collectAsStateWithLifecycle()
    val recents by AppSettings.recentPlayerColors.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf<AppSettings.PlayerColorTarget?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = stringResource(R.string.custom_now_playing),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.custom_now_playing_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            NowPlayingPreview(
                accent = accent,
                seekbar = seekbar,
                text = text,
                lyrics = lyrics,
                onPickAccent = { picking = AppSettings.PlayerColorTarget.ACCENT },
                onPickSeekbar = { picking = AppSettings.PlayerColorTarget.SEEKBAR },
                onPickText = { picking = AppSettings.PlayerColorTarget.TEXT },
                onPickLyrics = { picking = AppSettings.PlayerColorTarget.LYRICS },
            )
            Spacer(Modifier.height(10.dp))
            ColorSettingRow(
                label = stringResource(R.string.custom_buttons),
                value = accent,
                onPick = { picking = AppSettings.PlayerColorTarget.ACCENT },
            )
            ColorSettingRow(
                label = stringResource(R.string.custom_seekbar),
                value = seekbar,
                onPick = { picking = AppSettings.PlayerColorTarget.SEEKBAR },
            )
            ColorSettingRow(
                label = stringResource(R.string.custom_text),
                value = text,
                onPick = { picking = AppSettings.PlayerColorTarget.TEXT },
            )
            ColorSettingRow(
                label = stringResource(R.string.custom_lyrics),
                value = lyrics,
                onPick = { picking = AppSettings.PlayerColorTarget.LYRICS },
            )
            if (recents.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.custom_recent),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    recents.take(8).forEach { argb ->
                        Swatch(
                            color = Color(argb),
                            selected = argb == accent,
                            onClick = {
                                AppSettings.setPlayerColor(AppSettings.PlayerColorTarget.ACCENT, argb)
                            },
                        )
                    }
                }
            }
            TextButton(
                onClick = { AppSettings.resetPlayerColors() },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Icon(Icons.Rounded.RestartAlt, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.custom_reset_colors))
            }
        }
    }

    picking?.let { target ->
        ColorPickerDialog(
            title = when (target) {
                AppSettings.PlayerColorTarget.ACCENT -> stringResource(R.string.custom_buttons)
                AppSettings.PlayerColorTarget.SEEKBAR -> stringResource(R.string.custom_seekbar)
                AppSettings.PlayerColorTarget.TEXT -> stringResource(R.string.custom_text)
                AppSettings.PlayerColorTarget.LYRICS -> stringResource(R.string.custom_lyrics)
            },
            selected = when (target) {
                AppSettings.PlayerColorTarget.ACCENT -> accent
                AppSettings.PlayerColorTarget.SEEKBAR -> seekbar
                AppSettings.PlayerColorTarget.TEXT -> text
                AppSettings.PlayerColorTarget.LYRICS -> lyrics
            },
            onPick = {
                AppSettings.setPlayerColor(target, it)
                picking = null
            },
            onReset = {
                AppSettings.setPlayerColor(target, null)
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

/**
 * The Home shelves: the order they stand in, and which of them are on the
 * page at all.
 *
 * Quick Picks is one of them — it is a shelf like any other, and a listener
 * who wants it somewhere else, or nowhere, should be able to say so. Flow is
 * not: it is a card that rides in the Quick Picks row, not a row of its own,
 * so it has no place in this list.
 *
 * The order set here is the feed's order. A row moved to the top is loaded
 * at the top, and the page opens on it — not merely drawn there after the
 * feed has arrived.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeShelfOrderSheet(homeShelfTitles: List<String>, onDismiss: () -> Unit) {
    val saved by AppSettings.homeShelfOrder.collectAsStateWithLifecycle()
    val hidden by AppSettings.hiddenHomeShelves.collectAsStateWithLifecycle()
    val current = remember(homeShelfTitles, saved) {
        val rank = saved.withIndex().associate { (i, title) -> title to i }
        homeShelfTitles.distinct().sortedBy { rank[it] ?: Int.MAX_VALUE }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = stringResource(R.string.custom_home),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.custom_home_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            if (current.isEmpty()) {
                Text(
                    text = stringResource(R.string.custom_home_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                // The feed brings more shelves than a sheet can hold, and the
                // ones at the bottom are the ones a listener is most likely
                // to want to move: the list scrolls.
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                ) {
                itemsIndexed(current) { index, title ->
                    val isHidden = title in hidden
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isHidden) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onBackground
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                        )
                        // The eye: a shelf that is off the page stays in the
                        // list, so it can be put back.
                        IconButton(
                            onClick = { AppSettings.setHomeShelfHidden(title, !isHidden) },
                        ) {
                            Icon(
                                imageVector = if (isHidden) {
                                    Icons.Rounded.VisibilityOff
                                } else {
                                    Icons.Rounded.Visibility
                                },
                                contentDescription = stringResource(
                                    if (isHidden) R.string.custom_show_shelf else R.string.custom_hide_shelf,
                                ),
                                tint = if (isHidden) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onBackground
                                },
                            )
                        }
                        IconButton(
                            enabled = index > 0,
                            onClick = { AppSettings.setHomeShelfOrder(current.moved(index, index - 1)) },
                        ) {
                            Icon(Icons.Rounded.ArrowUpward, contentDescription = null)
                        }
                        IconButton(
                            enabled = index < current.lastIndex,
                            onClick = { AppSettings.setHomeShelfOrder(current.moved(index, index + 1)) },
                        ) {
                            Icon(Icons.Rounded.ArrowDownward, contentDescription = null)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                }
                }
            }
            TextButton(
                onClick = {
                    AppSettings.setHomeShelfOrder(emptyList())
                    AppSettings.showAllHomeShelves()
                },
                modifier = Modifier.padding(top = 6.dp),
            ) {
                Icon(Icons.Rounded.RestartAlt, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.custom_reset_order))
            }
        }
    }
}

/** The face the whole app is set in, the listener's own file included. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FontSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val choice by AppSettings.appFont.collectAsStateWithLifecycle()
    val customPath by AppSettings.customFontPath.collectAsStateWithLifecycle()

    // A copy in the app's own storage: the document the listener picked can be
    // moved, deleted, or live on storage that is not mounted tomorrow, and a
    // face that vanishes takes every label with it.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val target = File(context.filesDir, "fonts/auris_custom_font.ttf")
            target.parentFile?.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@runCatching
            AppFonts.forgetCustom()
            AppSettings.setCustomFontPath(target.absolutePath)
            AppSettings.setAppFont(AppFonts.CHOICE_CUSTOM)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = stringResource(R.string.custom_fonts),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.custom_fonts_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            val labels = listOf(
                stringResource(R.string.font_default),
                stringResource(R.string.font_system),
                stringResource(R.string.font_serif),
                stringResource(R.string.font_rounded),
                stringResource(R.string.font_custom),
            )
            AppFonts.CHOICES.forEachIndexed { index, value ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            if (value == AppFonts.CHOICE_CUSTOM) {
                                picker.launch(arrayOf("font/ttf", "font/otf", "application/x-font-ttf"))
                            } else {
                                AppSettings.setAppFont(value)
                            }
                        }
                        .padding(horizontal = 6.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = labels[index],
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                    )
                    if (choice == value) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            val chosenPath = customPath
            if (choice == AppFonts.CHOICE_CUSTOM && !chosenPath.isNullOrBlank()) {
                Text(
                    text = chosenPath.substringAfterLast('/'),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp, top = 4.dp),
                )
            }
        }
    }
}

/**
 * The Now Playing page, small, with every part of it live.
 *
 * The colours used to be four rows of swatches, which is a list of names for
 * four decisions and shows none of them: what "Buttons" means is only obvious
 * when you can see the button. Here the page is there, in the colours it is
 * set in, and each part of it is the thing to tap.
 */
@Composable
private fun NowPlayingPreview(
    accent: Int?,
    seekbar: Int?,
    text: Int?,
    lyrics: Int?,
    onPickAccent: () -> Unit,
    onPickSeekbar: () -> Unit,
    onPickText: () -> Unit,
    onPickLyrics: () -> Unit,
) {
    val accentColor = accent?.let { Color(it) } ?: Color(0xFFF2F2F7)
    val seekbarColor = seekbar?.let { Color(it) } ?: Color(0xFFF2F2F7)
    val textColor = text?.let { Color(it) } ?: Color(0xFFF2F2F7)
    val lyricsColor = lyrics?.let { Color(it) } ?: Color(0xFFB9B9C0)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF232327), Color(0xFF0B0B0D)),
                ),
            )
            .padding(18.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF3A3A42), Color(0xFF17171B)),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.16f))
                    .clickable(onClick = onPickAccent)
                    .padding(12.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.custom_preview_title),
            style = MaterialTheme.typography.titleLarge,
            color = textColor,
            maxLines = 1,
            modifier = Modifier.clickable(onClick = onPickText),
        )
        Text(
            text = stringResource(R.string.custom_preview_artist),
            style = MaterialTheme.typography.bodyMedium,
            color = textColor.copy(alpha = 0.65f),
            maxLines = 1,
            modifier = Modifier.clickable(onClick = onPickText),
        )
        Spacer(Modifier.height(14.dp))
        // The bar, in the seekbar's colour: the part of the page that is most
        // obviously a colour and most obviously a choice.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(seekbarColor.copy(alpha = 0.28f))
                .clickable(onClick = onPickSeekbar),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.42f)
                    .fillMaxHeight()
                    .background(seekbarColor),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.custom_preview_lyric),
            style = MaterialTheme.typography.bodyLarge,
            color = lyricsColor,
            maxLines = 2,
            modifier = Modifier.clickable(onClick = onPickLyrics),
        )
    }
}

@Composable
private fun ColorSettingRow(label: String, value: Int?, onPick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Palette,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(26.dp)
                .background(
                    color = value?.let { Color(it) } ?: MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape,
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape,
                ),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.change),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
}

@Composable
private fun ColorPickerDialog(
    title: String,
    selected: Int?,
    onPick: (Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val presets = remember {
        listOf(
            0xFFFFFFFF.toInt(), 0xFFE5E5E5.toInt(), 0xFF9E9E9E.toInt(),
            0xFF121212.toInt(), 0xFFFF5252.toInt(), 0xFFFF7043.toInt(),
            0xFFFFB300.toInt(), 0xFFFFEB3B.toInt(), 0xFF9CCC65.toInt(),
            0xFF26A69A.toInt(), 0xFF00BCD4.toInt(), 0xFF29B6F6.toInt(),
            0xFF5C6BC0.toInt(), 0xFFAB47BC.toInt(), 0xFFEC407A.toInt(),
            0xFFFF8A80.toInt(), 0xFF80CBC4.toInt(), 0xFFB39DDB.toInt(),
        )
    }
    val recents by AppSettings.recentPlayerColors.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(presets.chunked(6)) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { argb ->
                            Swatch(
                                color = Color(argb),
                                selected = argb == selected,
                                onClick = { onPick(argb) },
                            )
                        }
                    }
                }
                if (recents.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.custom_recent),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    items(recents.chunked(6)) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { argb ->
                                Swatch(
                                    color = Color(argb),
                                    selected = argb == selected,
                                    onClick = { onPick(argb) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onReset) { Text(stringResource(R.string.custom_use_artwork)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun Swatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .background(color, CircleShape)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    )
}

private fun List<String>.moved(from: Int, to: Int): List<String> {
    if (from == to || from !in indices || to !in indices) return this
    val out = toMutableList()
    val item = out.removeAt(from)
    out.add(to, item)
    return out
}
