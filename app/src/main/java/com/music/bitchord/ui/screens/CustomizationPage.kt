package com.music.bitchord.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Brush
import com.music.bitchord.ui.theme.AppFonts
import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Tune
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AppUi
import com.music.bitchord.data.settings.LibrarySort
import com.music.bitchord.data.settings.ThemeMode
import com.music.bitchord.ui.components.AurisSlider
import kotlin.math.roundToInt

/**
 * The Customisation hub: one sheet with the four surfaces that have colour or
 * order choices, rather than scattering them through the general settings list.
 * Kept in its own file because the sheet is already the largest composable in
 * the app and every row here would otherwise be compiled into its parent.
 */
/**
 * The Customisation page.
 *
 * It was a sheet, and it outgrew one: four surfaces of colour and order
 * choices, each with its own controls, fitted into a panel that had to be
 * scrolled to be usable and that covered the app it was customising. It is a
 * page now, with its own back, its own history entry and room for the two
 * sections that were missing — the app's face, and its motion.
 */
@Composable
fun CustomizationPage(
    homeShelfTitles: List<String>,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    val sections = listOf(
        stringResource(R.string.custom_now_playing),
        stringResource(R.string.custom_home),
        stringResource(R.string.custom_library),
        stringResource(R.string.custom_theme),
        stringResource(R.string.custom_fonts),
        stringResource(R.string.custom_motion),
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 20.dp, top = 8.dp, bottom = 6.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.customization),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
        }
        // Six sections do not fit a phone's width, so the bar scrolls rather
        // than shrinking: a segment label that has been squeezed to two
        // letters is no longer a label.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            sections.forEachIndexed { index, label ->
                if (index == section) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                } else {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .clickable { section = index }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            when (section) {
                0 -> NowPlayingCustomization()
                1 -> HomeCustomization(homeShelfTitles)
                2 -> LibraryCustomization()
                3 -> ThemeCustomization()
                4 -> FontsCustomization()
                else -> MotionCustomization()
            }
            Spacer(Modifier.height(36.dp))
        }
    }
}

@Composable
private fun NowPlayingCustomization() {
    val accent by AppSettings.playerAccentColor.collectAsStateWithLifecycle()
    val seekbar by AppSettings.playerSeekbarColor.collectAsStateWithLifecycle()
    val text by AppSettings.playerTextColor.collectAsStateWithLifecycle()
    val lyricsColor by AppSettings.playerLyricsColor.collectAsStateWithLifecycle()
    val recents by AppSettings.recentPlayerColors.collectAsStateWithLifecycle()
    val textGlow by AppSettings.textGlow.collectAsStateWithLifecycle()
    val showLyricLine by AppSettings.showNowPlayingLyrics.collectAsStateWithLifecycle()
    val nextStatus by AppSettings.showNextTrackStatus.collectAsStateWithLifecycle()
    val upgradeBadge by AppSettings.showUpgradeBadge.collectAsStateWithLifecycle()
    val losslessBadge by AppSettings.showLosslessBadge.collectAsStateWithLifecycle()
    val hiResBadge by AppSettings.showHiResBadge.collectAsStateWithLifecycle()
    val smoothness by AppSettings.transportSmoothness.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf<AppSettings.PlayerColorTarget?>(null) }

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.custom_now_playing_help),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
        )
        NowPlayingPreview(
            accent = accent,
            seekbar = seekbar,
            text = text,
            lyrics = lyricsColor,
            onPickAccent = { picking = AppSettings.PlayerColorTarget.ACCENT },
            onPickSeekbar = { picking = AppSettings.PlayerColorTarget.SEEKBAR },
            onPickText = { picking = AppSettings.PlayerColorTarget.TEXT },
            onPickLyrics = { picking = AppSettings.PlayerColorTarget.LYRICS },
        )
        Spacer(Modifier.height(14.dp))
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
            value = lyricsColor,
            onPick = { picking = AppSettings.PlayerColorTarget.LYRICS },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.custom_text_glow),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.custom_text_glow_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = textGlow, onCheckedChange = AppSettings::setTextGlow)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.custom_now_playing_lyrics),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.custom_now_playing_lyrics_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = showLyricLine,
                onCheckedChange = AppSettings::setShowNowPlayingLyrics,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        Text(
            text = stringResource(R.string.custom_indicators),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp, start = 4.dp),
        )
        // Every one defaults on; each is a separate claim the player makes, so
        // each gets its own switch rather than one "hide status" master.
        IndicatorToggle(
            title = stringResource(R.string.custom_next_track_status),
            help = stringResource(R.string.custom_next_track_status_help),
            checked = nextStatus,
            onCheckedChange = AppSettings::setShowNextTrackStatus,
        )
        IndicatorToggle(
            title = stringResource(R.string.custom_upgrade_badge),
            help = stringResource(R.string.custom_upgrade_badge_help),
            checked = upgradeBadge,
            onCheckedChange = AppSettings::setShowUpgradeBadge,
        )
        IndicatorToggle(
            title = stringResource(R.string.custom_lossless_badge),
            help = stringResource(R.string.custom_lossless_badge_help),
            checked = losslessBadge,
            onCheckedChange = AppSettings::setShowLosslessBadge,
        )
        IndicatorToggle(
            title = stringResource(R.string.custom_hires_badge),
            help = stringResource(R.string.custom_hires_badge_help),
            checked = hiResBadge,
            onCheckedChange = AppSettings::setShowHiResBadge,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        TransportSmoothnessSetting(smoothness)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        if (recents.isNotEmpty()) {
            Text(
                text = stringResource(R.string.custom_recent),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 18.dp, start = 4.dp, bottom = 8.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(horizontal = 4.dp),
            ) {
                recents.take(8).forEach { argb ->
                    Swatch(
                        color = Color(argb),
                        selected = argb == accent,
                        onClick = { AppSettings.setPlayerColor(AppSettings.PlayerColorTarget.ACCENT, argb) },
                    )
                }
            }
        }
        TextButton(
            onClick = { AppSettings.resetPlayerColors() },
            modifier = Modifier.padding(top = 14.dp),
        ) {
            Icon(Icons.Rounded.RestartAlt, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.custom_reset_colors))
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
                AppSettings.PlayerColorTarget.LYRICS -> lyricsColor
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
 * The Now Playing page, small, with every part of it live.
 *
 * The colours used to be four rows of swatches, which is a list of names for
 * four decisions and shows none of them: what "Buttons" means is only
 * obvious when you can see the button. Here the page is there, in the colours
 * it is set in, and each part of it is the thing to tap.
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
private fun IndicatorToggle(
    title: String,
    help: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = help,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun TransportSmoothnessSetting(value: Float) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.custom_transport_smoothness),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.custom_transport_smoothness_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = "${(value * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AurisSlider(
            value = value,
            onValueChange = AppSettings::setTransportSmoothness,
            valueRange = 0f..1f,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 38.dp, top = 2.dp),
        )
        TextButton(
            onClick = {
                AppSettings.setTransportSmoothness(AppSettings.DEFAULT_TRANSPORT_SMOOTHNESS)
            },
            modifier = Modifier.padding(start = 30.dp),
        ) {
            Icon(Icons.Rounded.RestartAlt, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.custom_smoothness_reset))
        }
    }
}

@Composable
private fun ColorSettingRow(label: String, value: Int?, onPick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(horizontal = 4.dp, vertical = 12.dp),
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
            Column {
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

@Composable
private fun HomeCustomization(homeShelfTitles: List<String>) {
    val saved by AppSettings.homeShelfOrder.collectAsStateWithLifecycle()
    val homeFlowVisible by AppSettings.homeFlowVisible.collectAsStateWithLifecycle()
    val current = remember(homeShelfTitles, saved) {
        val rank = saved.withIndex().associate { (i, title) -> title to i }
        homeShelfTitles.distinct().sortedBy { rank[it] ?: Int.MAX_VALUE }
    }

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.custom_home_help),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.show_flow_on_home),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = homeFlowVisible,
                onCheckedChange = AppSettings::setHomeFlowVisible,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        if (current.isEmpty()) {
            Text(
                text = stringResource(R.string.custom_home_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(4.dp, 16.dp),
            )
        } else {
            current.forEachIndexed { index, title ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "$index. $title",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                    )
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
            TextButton(onClick = { AppSettings.setHomeShelfOrder(emptyList()) }) {
                Icon(Icons.Rounded.RestartAlt, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.custom_reset_order))
            }
        }
    }
}

@Composable
private fun LibraryCustomization() {
    val sort by AppSettings.librarySort.collectAsStateWithLifecycle()
    val replayVisible by AppSettings.libraryReplayVisible.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.custom_library_help),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.custom_show_replay),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.custom_show_replay_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = replayVisible,
                onCheckedChange = AppSettings::setLibraryReplayVisible,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        Text(
            text = stringResource(R.string.custom_library_sort),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(4.dp, 16.dp, 4.dp, 8.dp),
        )
        SegmentedControl(
            options = listOf(
                stringResource(R.string.sort_default),
                stringResource(R.string.sort_title_asc),
                stringResource(R.string.sort_title_desc),
            ),
            selectedIndex = LibrarySort.entries.indexOf(sort),
            onSelect = { AppSettings.setLibrarySort(LibrarySort.entries[it]) },
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun ThemeCustomization() {
    val appUi by AppSettings.appUi.collectAsStateWithLifecycle()
    val theme by AppSettings.themeMode.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.custom_theme_help),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
        )
        Text(
            text = stringResource(R.string.app_ui),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(4.dp, 4.dp, 4.dp, 8.dp),
        )
        SegmentedControl(
            options = AppUi.entries.map { it.label },
            selectedIndex = AppUi.entries.indexOf(appUi),
            onSelect = { AppSettings.setAppUi(AppUi.entries[it]) },
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.size(18.dp))
        Text(
            text = stringResource(R.string.theme),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(4.dp, 4.dp, 4.dp, 8.dp),
        )
        SegmentedControl(
            options = listOf(
                stringResource(R.string.system),
                stringResource(R.string.light),
                stringResource(R.string.dark),
            ),
            selectedIndex = ThemeMode.entries.indexOf(theme),
            onSelect = { AppSettings.setThemeMode(ThemeMode.entries[it]) },
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/**
 * The app's face.
 *
 * The default is the one Apple Music sets its text in. The others are the
 * listener's to pick, and a face of their own from anywhere on the device is
 * the one worth having: a face that exists nowhere else is the one they will
 * recognise.
 */
@Composable
private fun FontsCustomization() {
    val context = LocalContext.current
    val choice by AppSettings.appFont.collectAsStateWithLifecycle()
    val customPath by AppSettings.customFontPath.collectAsStateWithLifecycle()
    val scriptFonts by AppSettings.scriptFonts.collectAsStateWithLifecycle()

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

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.custom_fonts_help),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
        )
        val labels = listOf(
            stringResource(R.string.font_default),
            stringResource(R.string.font_system),
            stringResource(R.string.font_serif),
            stringResource(R.string.font_rounded),
            stringResource(R.string.font_custom),
        )
        val values = AppFonts.CHOICES
        values.forEachIndexed { index, value ->
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
                    .padding(horizontal = 6.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = labels[index],
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                if (choice == value) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
            modifier = Modifier.padding(vertical = 14.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.custom_script_fonts),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(R.string.custom_script_fonts_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = scriptFonts,
                onCheckedChange = AppSettings::setScriptFonts,
            )
        }
    }
}

/**
 * Motion.
 *
 * One switch that stops everything moving, and the two that soften the
 * heaviest of it: the blur behind the glass, which is the app's most
 * expensive frame, and the fold of the navigation bar.
 */
@Composable
private fun MotionCustomization() {
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val liquidGlass by AppSettings.liquidGlass.collectAsStateWithLifecycle()
    val foldingNavBar by AppSettings.foldingNavBar.collectAsStateWithLifecycle()
    val highPerformance by AppSettings.highPerformanceMode.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.custom_motion_help),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
        )
        MotionRow(
            title = stringResource(R.string.reduce_animation),
            subtitle = stringResource(R.string.reduce_animation_subtitle),
            checked = reduceAnimation,
            onCheckedChange = AppSettings::setReduceAnimation,
        )
        MotionRow(
            title = stringResource(R.string.reduce_dynamic_blur),
            subtitle = stringResource(R.string.reduce_dynamic_blur_subtitle),
            checked = reduceDynamicBlur,
            onCheckedChange = AppSettings::setReduceDynamicBlur,
        )
        MotionRow(
            title = stringResource(R.string.liquid_glass),
            subtitle = stringResource(R.string.liquid_glass_subtitle),
            checked = liquidGlass,
            onCheckedChange = AppSettings::setLiquidGlass,
        )
        MotionRow(
            title = stringResource(R.string.folding_nav_bar),
            subtitle = stringResource(R.string.folding_nav_bar_subtitle),
            checked = foldingNavBar,
            onCheckedChange = AppSettings::setFoldingNavBar,
        )
        MotionRow(
            title = stringResource(R.string.high_performance_mode),
            subtitle = stringResource(R.string.high_performance_mode_subtitle),
            checked = highPerformance,
            onCheckedChange = AppSettings::setHighPerformanceMode,
        )
    }
}

@Composable
private fun MotionRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun List<String>.moved(from: Int, to: Int): List<String> {
    if (from == to || from !in indices || to !in indices) return this
    val out = toMutableList()
    val item = out.removeAt(from)
    out.add(to, item)
    return out
}
