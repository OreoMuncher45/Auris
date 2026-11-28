package com.music.bitchord.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.lyrics.translationLanguageName
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.player.fullBleedArtworkAvailable
import java.io.IOException
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Now Playing category: the player's look, its artwork and its lyrics. */
@Composable
internal fun NowPlayingSettings(
    search: SettingsSearch,
    sheets: SettingsSheets,
    windowWidth: Dp,
    onSpotifyCanvasAuth: () -> Unit,
    onLyricsSources: () -> Unit,
    onTranslationLanguage: () -> Unit,
) {
    val context = LocalContext.current
    val lyricsBlur by AppSettings.lyricsBlur.collectAsStateWithLifecycle()
    val animatedCanvas by AppSettings.animatedCanvas.collectAsStateWithLifecycle()
    val canvasOverCellular by AppSettings.canvasOverCellular.collectAsStateWithLifecycle()
    val fullBleedArtwork by AppSettings.fullBleedArtwork.collectAsStateWithLifecycle()
    val zoomOutAlbumArt by AppSettings.zoomOutAlbumArt.collectAsStateWithLifecycle()
    val showTotalDuration by AppSettings.showTotalDuration.collectAsStateWithLifecycle()
    val legacyMeshGradient by AppSettings.legacyMeshGradient.collectAsStateWithLifecycle()
    val syncedLyrics by AppSettings.syncedLyrics.collectAsStateWithLifecycle()
    val lyricsSources by AppSettings.lyricsSources.collectAsStateWithLifecycle()
    val translationLanguage by AppSettings.translationLanguage.collectAsStateWithLifecycle()
    val hideVolumeBar by AppSettings.hideVolumeBar.collectAsStateWithLifecycle()
    val hideSongStatus by AppSettings.hideSongStatus.collectAsStateWithLifecycle()
    val textGlow by AppSettings.textGlow.collectAsStateWithLifecycle()
    val liveBlur by AppSettings.liveBlur.collectAsStateWithLifecycle()
    val perfMonitor by AppSettings.perfMonitor.collectAsStateWithLifecycle()
    val showNowPlayingLyrics by AppSettings.showNowPlayingLyrics.collectAsStateWithLifecycle()
    val showNextTrackStatus by AppSettings.showNextTrackStatus.collectAsStateWithLifecycle()
    val showBestPart by AppSettings.showBestPart.collectAsStateWithLifecycle()
    val showUpgradeBadge by AppSettings.showUpgradeBadge.collectAsStateWithLifecycle()
    val showLosslessBadge by AppSettings.showLosslessBadge.collectAsStateWithLifecycle()
    val showHiResBadge by AppSettings.showHiResBadge.collectAsStateWithLifecycle()
    val transportSmoothness by AppSettings.transportSmoothness.collectAsStateWithLifecycle()
    val saveScope = rememberCoroutineScope()
    SearchableSettingsGroup(search, SettingsCategory.NOW_PLAYING) {
        val nowPlayingColorsTitle = stringResource(R.string.custom_now_playing)
        row(nowPlayingColorsTitle, "colour", "color", "buttons", "seekbar", "text", "lyrics", "artwork") {
            SettingsRow(
                icon = Icons.Rounded.Palette,
                title = nowPlayingColorsTitle,
                subtitle = stringResource(R.string.custom_now_playing_help),
                onClick = { sheets.showPlayerColors = true },
            )
        }
        val showTotalDurationTitle = stringResource(R.string.show_total_duration)
        row(showTotalDurationTitle, "seek bar", "remaining time", "duration", "song length") {
            SettingsRow(
                icon = Icons.Rounded.Timer,
                title = showTotalDurationTitle,
                subtitle = stringResource(R.string.show_total_duration_subtitle),
                trailing = {
                    SettingsSwitch(
                        checked = showTotalDuration,
                        onCheckedChange = AppSettings::setShowTotalDuration,
                    )
                },
                onClick = { AppSettings.setShowTotalDuration(!showTotalDuration) },
            )
        }
        val textGlowTitle = stringResource(R.string.custom_text_glow)
        row(textGlowTitle, "glow", "shadow", "text") {
            SettingsRow(
                icon = Icons.Rounded.AutoAwesome,
                title = textGlowTitle,
                subtitle = stringResource(R.string.custom_text_glow_help),
                trailing = {
                    SettingsSwitch(
                        checked = textGlow,
                        onCheckedChange = AppSettings::setTextGlow,
                    )
                },
                onClick = { AppSettings.setTextGlow(!textGlow) },
            )
        }
        val nowPlayingLyricsTitle = stringResource(R.string.custom_now_playing_lyrics)
        row(nowPlayingLyricsTitle, "lyrics", "line", "clean") {
            SettingsRow(
                icon = Icons.AutoMirrored.Rounded.Notes,
                title = nowPlayingLyricsTitle,
                subtitle = stringResource(R.string.custom_now_playing_lyrics_help),
                trailing = {
                    SettingsSwitch(
                        checked = showNowPlayingLyrics,
                        onCheckedChange = AppSettings::setShowNowPlayingLyrics,
                    )
                },
                onClick = { AppSettings.setShowNowPlayingLyrics(!showNowPlayingLyrics) },
            )
        }
        val nextTrackStatusTitle = stringResource(R.string.custom_next_track_status)
        row(nextTrackStatusTitle, "next track", "indicator", "status") {
            SettingsRow(
                icon = Icons.Rounded.PlayArrow,
                title = nextTrackStatusTitle,
                subtitle = stringResource(R.string.custom_next_track_status_help),
                trailing = {
                    SettingsSwitch(
                        checked = showNextTrackStatus,
                        onCheckedChange = AppSettings::setShowNextTrackStatus,
                    )
                },
                onClick = { AppSettings.setShowNextTrackStatus(!showNextTrackStatus) },
            )
        }
        val bestPartTitle = stringResource(R.string.show_best_part)
        row(bestPartTitle, "best part", "chorus", "skip", "replayed", "highlight") {
            SettingsRow(
                icon = Icons.Rounded.MusicNote,
                title = bestPartTitle,
                subtitle = stringResource(R.string.show_best_part_subtitle),
                trailing = {
                    SettingsSwitch(
                        checked = showBestPart,
                        onCheckedChange = AppSettings::setShowBestPart,
                    )
                },
                onClick = { AppSettings.setShowBestPart(!showBestPart) },
            )
        }
        val upgradeBadgeTitle = stringResource(R.string.custom_upgrade_badge)
        row(upgradeBadgeTitle, "upgrade", "badge", "indicator") {
            SettingsRow(
                icon = Icons.Rounded.AutoAwesome,
                title = upgradeBadgeTitle,
                subtitle = stringResource(R.string.custom_upgrade_badge_help),
                trailing = {
                    SettingsSwitch(
                        checked = showUpgradeBadge,
                        onCheckedChange = AppSettings::setShowUpgradeBadge,
                    )
                },
                onClick = { AppSettings.setShowUpgradeBadge(!showUpgradeBadge) },
            )
        }
        val losslessBadgeTitle = stringResource(R.string.custom_lossless_badge)
        row(losslessBadgeTitle, "lossless", "badge", "indicator") {
            SettingsRow(
                icon = Icons.Rounded.GraphicEq,
                title = losslessBadgeTitle,
                subtitle = stringResource(R.string.custom_lossless_badge_help),
                trailing = {
                    SettingsSwitch(
                        checked = showLosslessBadge,
                        onCheckedChange = AppSettings::setShowLosslessBadge,
                    )
                },
                onClick = { AppSettings.setShowLosslessBadge(!showLosslessBadge) },
            )
        }
        val hiresBadgeTitle = stringResource(R.string.custom_hires_badge)
        row(hiresBadgeTitle, "hires", "hi-res", "badge", "indicator") {
            SettingsRow(
                icon = Icons.Rounded.GraphicEq,
                title = hiresBadgeTitle,
                subtitle = stringResource(R.string.custom_hires_badge_help),
                trailing = {
                    SettingsSwitch(
                        checked = showHiResBadge,
                        onCheckedChange = AppSettings::setShowHiResBadge,
                    )
                },
                onClick = { AppSettings.setShowHiResBadge(!showHiResBadge) },
            )
        }
        val transportSmoothnessTitle = stringResource(R.string.custom_transport_smoothness)
        row(transportSmoothnessTitle, "smoothness", "animation", "controls") {
            SliderRow(
                icon = Icons.Rounded.Tune,
                title = transportSmoothnessTitle,
                subtitle = stringResource(R.string.custom_transport_smoothness_help),
                value = "${(transportSmoothness * 100).roundToInt()}%",
                sliderValue = transportSmoothness,
                onSliderValue = { AppSettings.setTransportSmoothness(it) },
                valueRange = 0f..1f,
                steps = 0,
            )
        }
        val liveBlurTitle = stringResource(R.string.custom_live_blur)
        row(liveBlurTitle, "blur", "artwork", "player", "battery") {
            SettingsRow(
                icon = Icons.Rounded.BlurOn,
                title = liveBlurTitle,
                subtitle = stringResource(R.string.custom_live_blur_help),
                trailing = {
                    SettingsSwitch(
                        checked = liveBlur,
                        onCheckedChange = AppSettings::setLiveBlur,
                    )
                },
                onClick = { AppSettings.setLiveBlur(!liveBlur) },
            )
        }
        val perfMonitorTitle = stringResource(R.string.perf_monitor)
        row(perfMonitorTitle, "performance", "cpu", "debug", "log") {
            SettingsRow(
                icon = Icons.Rounded.Speed,
                title = perfMonitorTitle,
                subtitle = stringResource(R.string.perf_monitor_help),
                trailing = {
                    SettingsSwitch(
                        checked = perfMonitor,
                        onCheckedChange = AppSettings::setPerfMonitor,
                    )
                },
                onClick = { AppSettings.setPerfMonitor(!perfMonitor) },
            )
        }
        if (perfMonitor) {
            // Collected only while the monitor is on: with it off the reading
            // is a constant zero and the row it feeds is not shown.
            val perfCpu by com.music.bitchord.perf.PerfMonitor.cpuPercent.collectAsStateWithLifecycle()
            row(
                stringResource(R.string.perf_save_log),
                "performance",
                "log",
                "save",
            ) {
                SettingsRow(
                    icon = Icons.Rounded.FileDownload,
                    title = stringResource(R.string.perf_save_log),
                    subtitle = stringResource(
                        R.string.perf_save_log_help,
                        perfCpu.roundToInt(),
                    ),
                    onClick = {
                        // Writing the report is file I/O, so not on the main
                        // thread; the toast with its path waits for it.
                        //
                        // A failed write (a full disk, storage that went away)
                        // is said in the toast rather than thrown out of the
                        // coroutine. Only IOException is caught: anything else
                        // is a bug, and is still thrown.
                        saveScope.launch {
                            val message = try {
                                withContext(Dispatchers.IO) {
                                    com.music.bitchord.perf.PerfMonitor.save(context)
                                }.absolutePath
                            } catch (e: IOException) {
                                context.getString(
                                    R.string.perf_save_log_failed,
                                    e.message ?: context.getString(R.string.unknown_error),
                                )
                            }
                            android.widget.Toast.makeText(
                                context,
                                message,
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                )
            }
        }
        // Left out where the player won't honour it: a window too wide for
        // the player to fill and too narrow to stand a page beside it keeps
        // the sleeve either way. A docked pane is a phone's width, so it does
        // honour it — see [fullBleedArtworkAvailable].
        if (fullBleedArtworkAvailable(windowWidth)) {
            val fullScreenCoverArtTitle = stringResource(R.string.full_screen_cover_art)
            row(fullScreenCoverArtTitle, "artwork", "player") {
                SettingsRow(
                    icon = Icons.Rounded.Fullscreen,
                    title = fullScreenCoverArtTitle,
                    subtitle = stringResource(R.string.full_screen_cover_art_subtitle),
                    trailing = {
                        SettingsSwitch(
                            checked = fullBleedArtwork,
                            onCheckedChange = AppSettings::setFullBleedArtwork,
                        )
                    },
                    onClick = { AppSettings.setFullBleedArtwork(!fullBleedArtwork) },
                )
            }
        }
        // The picture inside the card, rather than the card itself: the
        // other way of looking at a cover that is cropped too tightly. A row
        // of its own, because it is a choice about the picture and not about
        // the treatment the picture sits in, and outside the full-bleed gate
        // for the same reason: the player zooms the card's picture at every
        // width, including the ones where full bleed is not offered.
        val zoomOutAlbumArtTitle = stringResource(R.string.zoom_out_album_art)
        row(zoomOutAlbumArtTitle, "artwork", "player", "zoom") {
            SettingsRow(
                icon = Icons.Rounded.ZoomOut,
                title = zoomOutAlbumArtTitle,
                subtitle = stringResource(R.string.zoom_out_album_art_subtitle),
                trailing = {
                    SettingsSwitch(
                        checked = zoomOutAlbumArt,
                        onCheckedChange = AppSettings::setZoomOutAlbumArt,
                    )
                },
                onClick = { AppSettings.setZoomOutAlbumArt(!zoomOutAlbumArt) },
            )
        }
        val legacyMeshGradientTitle = stringResource(R.string.legacy_mesh_gradient)
        row(legacyMeshGradientTitle, "background", "player") {
            SettingsRow(
                icon = Icons.Rounded.Gradient,
                title = legacyMeshGradientTitle,
                subtitle = stringResource(R.string.legacy_mesh_gradient_subtitle),
                trailing = {
                    SettingsSwitch(
                        checked = legacyMeshGradient,
                        onCheckedChange = AppSettings::setLegacyMeshGradient,
                    )
                },
                onClick = { AppSettings.setLegacyMeshGradient(!legacyMeshGradient) },
            )
        }
        val animatedCoverArtTitle = stringResource(R.string.animated_cover_art)
        // Carries the keywords of the rows that only appear once it is on,
        // so searching "spotify" with animated covers switched off turns up
        // the switch that brings the Spotify row back rather than nothing.
        row(animatedCoverArtTitle, "canvas", "video", "spotify") {
            SettingsRow(
                icon = Icons.Rounded.Animation,
                title = animatedCoverArtTitle,
                subtitle = stringResource(R.string.animated_cover_art_subtitle),
                trailing = {
                    SettingsSwitch(
                        checked = animatedCanvas,
                        onCheckedChange = AppSettings::setAnimatedCanvas,
                    )
                },
                onClick = { AppSettings.setAnimatedCanvas(!animatedCanvas) },
            )
        }
        // Reads as part of the Animated cover art option above it, not
        // as a separate setting. Nothing to narrow while the clip itself
        // is off. Defaults to off: a clip loops for as long as its track
        // plays, so on cellular this is not a one-time video cost but
        // that cost repeated on every loop — see AppSettings.canvasOverCellular.
        if (animatedCanvas) {
            val coverCellularTitle = stringResource(R.string.animated_cover_cellular)
            row(coverCellularTitle, "canvas", "cellular", divided = false) {
                SettingsSubRow(
                    title = coverCellularTitle,
                    checked = canvasOverCellular,
                    onCheckedChange = AppSettings::setCanvasOverCellular,
                )
            }
            val spotifyCanvasTitle = stringResource(R.string.integrate_spotify_canvas)
            row(spotifyCanvasTitle, "spotify", "canvas", divided = false) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onSpotifyCanvasAuth)
                        .padding(start = ROW_INSET, end = ROW_INSET, top = 4.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = spotifyCanvasTitle,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Chevron()
                }
            }
        }
        val syncedLyricsTitle = stringResource(R.string.synced_lyrics)
        // Same reasoning as Animated cover art above: the lyrics source and
        // translation rows are only here while this is on.
        row(syncedLyricsTitle, "lyrics", "translation", "lrclib", "musixmatch") {
            SettingsRow(
                icon = Icons.AutoMirrored.Rounded.Notes,
                title = syncedLyricsTitle,
                subtitle = stringResource(R.string.synced_lyrics_subtitle),
                trailing = {
                    SettingsSwitch(
                        checked = syncedLyrics,
                        onCheckedChange = AppSettings::setSyncedLyrics,
                    )
                },
                onClick = { AppSettings.setSyncedLyrics(!syncedLyrics) },
            )
        }
        // Nothing to choose between while the feature is off, and the
        // sources are third-party services being reached on the user's
        // connection — which is the part worth being able to narrow.
        if (syncedLyrics) {
            val lyricsBlurTitle = stringResource(R.string.blur_unfocused_lyrics)
            row(lyricsBlurTitle, "lyrics", "blur") {
                SettingsRow(
                    icon = Icons.Rounded.BlurOn,
                    title = lyricsBlurTitle,
                    subtitle = stringResource(R.string.blur_unfocused_lyrics_subtitle),
                    trailing = {
                        SettingsSwitch(
                            checked = lyricsBlur,
                            onCheckedChange = AppSettings::setLyricsBlur,
                        )
                    },
                    onClick = { AppSettings.setLyricsBlur(!lyricsBlur) },
                )
            }
            val lyricsSourcesTitle = stringResource(R.string.lyrics_sources)
            row(lyricsSourcesTitle, "lyrics", "lrclib", "musixmatch") {
                SettingsRow(
                    icon = Icons.Rounded.Language,
                    title = lyricsSourcesTitle,
                    subtitle = remember(lyricsSources) {
                        lyricsSources
                            .sortedBy { it.ordinal }
                            .joinToString(", ") { it.label }
                    }.ifEmpty { stringResource(R.string.no_lyrics_sources_enabled) },
                    trailing = { Chevron() },
                    onClick = onLyricsSources,
                )
            }
            val translationLanguageTitle = stringResource(R.string.translation_language)
            row(translationLanguageTitle, "lyrics", "translate") {
                SettingsRow(
                    icon = Icons.Rounded.Translate,
                    title = translationLanguageTitle,
                    subtitle = if (translationLanguage.isBlank()) {
                        stringResource(R.string.translation_language_subtitle)
                    } else {
                        translationLanguageName(
                            translationLanguage,
                            rememberAppLocale() ?: Locale.getDefault(),
                        )
                    },
                    trailing = { Chevron() },
                    onClick = onTranslationLanguage,
                )
            }
        }
        val hideVolumeBarTitle = stringResource(R.string.hide_volume_bar)
        row(hideVolumeBarTitle, "volume", "player") {
            SettingsRow(
                icon = Icons.Rounded.VolumeOff,
                title = hideVolumeBarTitle,
                subtitle = stringResource(R.string.hide_volume_bar_subtitle),
                trailing = {
                    SettingsSwitch(
                        checked = hideVolumeBar,
                        onCheckedChange = AppSettings::setHideVolumeBar,
                    )
                },
                onClick = { AppSettings.setHideVolumeBar(!hideVolumeBar) },
            )
        }
        val hideSongStatusTitle = stringResource(R.string.hide_song_status)
        row(hideSongStatusTitle, "player", "playing from", "played by") {
            SettingsRow(
                icon = Icons.Rounded.VisibilityOff,
                title = hideSongStatusTitle,
                subtitle = stringResource(R.string.hide_song_status_subtitle),
                trailing = {
                    SettingsSwitch(
                        checked = hideSongStatus,
                        onCheckedChange = AppSettings::setHideSongStatus,
                    )
                },
                onClick = { AppSettings.setHideSongStatus(!hideSongStatus) },
            )
        }
    }
}
