package com.music.bitchord.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.DownloadQuality

/** The Downloads category. */
@Composable
internal fun DownloadsSettings(
    search: SettingsSearch,
    sheets: SettingsSheets,
) {
    val metered by AppSettings.meteredConnection.collectAsStateWithLifecycle()
    val downloadQuality by AppSettings.downloadQuality.collectAsStateWithLifecycle()
    val concurrentDownloads by AppSettings.concurrentDownloads.collectAsStateWithLifecycle()
    val wifiOnlyDownloads by AppSettings.wifiOnlyDownloads.collectAsStateWithLifecycle()
    val exportDownloads by AppSettings.exportDownloads.collectAsStateWithLifecycle()
    SearchableSettingsGroup(search, SettingsCategory.DOWNLOADS) {
        // Keeping a playlist whole is asked for on the playlist's own page —
        // a switch beside its tracks, off unless the listener turns it on —
        // so there is no global row for it here. What is here is how a
        // download is made and where it is allowed to happen.
        val downloadQualityTitle = stringResource(R.string.download_quality)
        row(downloadQualityTitle, "offline", "lossless") {
            SettingsRow(
                icon = Icons.Rounded.Download,
                title = downloadQualityTitle,
                subtitle = stringResource(R.string.download_quality_subtitle, downloadQuality.perTrack),
                value = downloadQuality.localizedLabel(),
                onClick = { sheets.pickingDownloadQuality = true },
            )
        }
        // Reads as part of Download quality above it, not as a setting
        // of its own — same treatment as Play animated cover over
        // cellular gets under Animated cover art.
        val downloadWifiOnlyTitle = stringResource(R.string.download_wifi_only)
        row(downloadWifiOnlyTitle, "wi-fi", "cellular", divided = false) {
            SettingsSubRow(
                title = downloadWifiOnlyTitle,
                checked = wifiOnlyDownloads,
                onCheckedChange = AppSettings::setWifiOnlyDownloads,
                badge = stringResource(R.string.blocking).takeIf { wifiOnlyDownloads && metered == true },
            )
        }
        val exportDownloadsTitle = stringResource(R.string.export_compatible_downloads)
        row(exportDownloadsTitle, "music folder") {
            SettingsSubRow(
                title = exportDownloadsTitle,
                checked = exportDownloads,
                onCheckedChange = AppSettings::setExportDownloads,
                subtitle = "Music/Auris".takeIf { exportDownloads },
            )
        }
        // How many of them run at once. The width used to be a constant,
        // which made it a thing the app decided rather than the listener.
        val concurrentDownloadsTitle = stringResource(R.string.concurrent_downloads)
        row(concurrentDownloadsTitle, "one", "six", divided = false) {
            SliderRow(
                icon = Icons.Rounded.Layers,
                title = concurrentDownloadsTitle,
                subtitle = stringResource(R.string.concurrent_downloads_subtitle),
                value = concurrentDownloads.toString(),
                sliderValue = concurrentDownloads.toFloat(),
                onSliderValue = { AppSettings.setConcurrentDownloads(it.toInt()) },
                valueRange = AppSettings.MIN_CONCURRENT_DOWNLOADS.toFloat()..
                    AppSettings.MAX_CONCURRENT_DOWNLOADS.toFloat(),
                steps = AppSettings.MAX_CONCURRENT_DOWNLOADS - AppSettings.MIN_CONCURRENT_DOWNLOADS - 1,
            )
        }
    }
}

@Composable
private fun DownloadQuality.localizedLabel(): String = stringResource(
    when (this) {
        DownloadQuality.STANDARD -> R.string.standard
        DownloadQuality.HIGH -> R.string.high
        DownloadQuality.LOSSLESS -> R.string.lossless
        DownloadQuality.DOLBY -> R.string.dolby_atmos
    },
)

/**
 * What to keep when a track is saved, with what each rung costs on disk.
 *
 * Priced per track rather than per hour, the way [QualitySheet] is. That sheet
 * is answering "what will listening cost me this hour", because a stream is
 * spent again on every replay; this one is answering "what will keeping this
 * cost me", and the answer is charged once. Same widget, different question, so
 * the numbers beside the options are in different units on purpose.
 */
@Composable
internal fun DownloadQualitySheet(
    selected: DownloadQuality,
    onSelect: (DownloadQuality) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Row(
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Download,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = stringResource(R.string.download_quality),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(R.string.download_quality_dialog_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)

        // Scrolls, so the last option stays reachable in a short window or
        // with large text; the heading above stays put. `fill = false` keeps
        // a short list from stretching the sheet.
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
        ) {
            // Best first, matching [QualitySheet] — and here the best rung is also
            // the default, so the checkmark starts where the eye does.
            DownloadQuality.entries.reversed().forEach { quality ->
                val chosen = quality == selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(quality)
                        }
                        .padding(horizontal = 22.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = quality.localizedLabel(),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            text = stringResource(R.string.quality_per_track, quality.detail, quality.perTrack),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (chosen) {
                        Spacer(Modifier.width(12.dp))
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = stringResource(R.string.selected),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}
