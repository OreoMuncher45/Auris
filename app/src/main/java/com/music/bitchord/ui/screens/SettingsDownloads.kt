package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.rounded.Favorite
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.DownloadQuality
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.download.DownloadedPlaylistSync
import com.music.bitchord.download.Downloads

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
    val downloadLikedSongs by AppSettings.downloadLikedSongs.collectAsStateWithLifecycle()
    val collections by Downloads.collections.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SearchableSettingsGroup(search, SettingsCategory.DOWNLOADS) {
        // Keeping a playlist whole is asked for on the playlist's own page —
        // a switch beside its tracks, off unless the listener turns it on —
        // so there is no global row for it here. Liked Music is the one
        // exception, below. What else is here is how a download is made and
        // where it is allowed to happen.
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
        // The one playlist that gets a row here: it is the one every listener
        // has, and the one whose new songs arrive by a tap on a heart anywhere
        // in the app rather than on its page. On while either this or the
        // Liked Music page's own switch says so — they are the same promise.
        val likedRecord = Downloads.collectionFor(YtMusicRepository.LIKED_MUSIC, collections)
        val keepLiked = downloadLikedSongs || likedRecord?.autoDownload == true
        val downloadLikedTitle = stringResource(R.string.download_liked_songs)
        row(downloadLikedTitle, "liked", "favorites", "offline", "automatic") {
            SettingsRow(
                icon = Icons.Rounded.Favorite,
                title = downloadLikedTitle,
                subtitle = stringResource(R.string.download_liked_songs_subtitle),
                trailing = {
                    SettingsSwitch(
                        checked = keepLiked,
                        onCheckedChange = { DownloadedPlaylistSync.setKeepLiked(context, it) },
                    )
                },
                onClick = { DownloadedPlaylistSync.setKeepLiked(context, !keepLiked) },
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
 * The download-quality picker, opened on its row: priced per track rather
 * than per hour, the way [QualityMenu] is, because a download is a file of a
 * size, not a stream. Best first.
 */
@Composable
internal fun ColumnScope.DownloadQualityMenu(close: () -> Unit) {
    val selected by AppSettings.downloadQuality.collectAsStateWithLifecycle()
    SettingsChoiceMenu(
        title = stringResource(R.string.download_quality),
        subtitle = stringResource(R.string.download_quality_dialog_subtitle),
        choices = DownloadQuality.entries.reversed().map { quality ->
            SettingsChoice(
                label = quality.localizedLabel(),
                detail = stringResource(R.string.quality_per_track, quality.detail, quality.perTrack),
                selected = quality == selected,
            ) { AppSettings.setDownloadQuality(quality) }
        },
        close = close,
    )
}

