package com.music.bitchord.ui.screens

import com.music.bitchord.ui.components.ConfirmationAlert
import com.music.bitchord.util.AppRelaunch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.ColumnScope
import android.media.AudioFormat
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
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AutomixPerformanceMode
import com.music.bitchord.data.settings.HomeProvider
import com.music.bitchord.data.sources.DeviceCodecs
import com.music.bitchord.playback.AudioOutputStatus
import kotlin.math.roundToInt

/** The Playback category: crossfade and Automix, the home provider, and how the queue behaves. */
@Composable
internal fun PlaybackSettings(
    search: SettingsSearch,
    sheets: SettingsSheets,
) {
    val crossfade by AppSettings.crossfadeSeconds.collectAsStateWithLifecycle()
    val smartFade by AppSettings.smartFadeEnabled.collectAsStateWithLifecycle()
    val automixPerformance by AppSettings.automixPerformanceMode.collectAsStateWithLifecycle()
    val skipSilence by AppSettings.skipSilence.collectAsStateWithLifecycle()
    val spatialAudio by AppSettings.spatialAudio.collectAsStateWithLifecycle()
    val simulatedAtmos by AppSettings.simulatedAtmos.collectAsStateWithLifecycle()
    val unsupportedDolbyAtmos by AppSettings.unsupportedDolbyAtmos.collectAsStateWithLifecycle()
    val softwareAtmosAvailable = remember {
        !DeviceCodecs.decodesAtmosInHardware && DeviceCodecs.playsDolbyAtmos
    }
    var showUnsupportedAtmosWarning by remember { mutableStateOf(false) }
    val outputStatus by AudioOutputStatus.current.collectAsStateWithLifecycle()
    val playingFormat by NerdStats.current.collectAsStateWithLifecycle()
    // Derived, so a format publish that leaves the answer as it was (most of
    // them: bitrate, decoder, the next track in the same format) does not
    // recompose the page. Read by the spatial audio row alone.
    val playingDolbyAtmos by remember { derivedStateOf { playingFormat?.isDolbyAtmos == true } }
    val stopOnTaskRemoved by AppSettings.stopOnTaskRemoved.collectAsStateWithLifecycle()
    val swipeToPlayNext by AppSettings.swipeToPlayNext.collectAsStateWithLifecycle()
    val dontRepeatSuggestions by AppSettings.dontRepeatSuggestions.collectAsStateWithLifecycle()
    val preferMusicOnly by AppSettings.preferMusicOnly.collectAsStateWithLifecycle()
    val smartVersionAlignment by AppSettings.smartVersionAlignment.collectAsStateWithLifecycle()
    SearchableSettingsGroup(search, SettingsCategory.PLAYBACK) {
        val preferMusicOnlyTitle = stringResource(R.string.prefer_music_only)
        row(preferMusicOnlyTitle, "video", "audio", "music video") {
            SettingsRow(
                icon = Icons.Rounded.SmartDisplay,
                title = preferMusicOnlyTitle,
                subtitle = stringResource(R.string.prefer_music_only_subtitle),
                trailing = {
                    SettingsSwitch(
                        checked = preferMusicOnly,
                        onCheckedChange = AppSettings::setPreferMusicOnly,
                    )
                },
                onClick = { AppSettings.setPreferMusicOnly(!preferMusicOnly) },
            )
        }
        // Automix decides its own length from each pair of tracks —
        // tempo, key, structure — so it replaces the manual slider rather
        // than needing it set to anything first.
        if (!smartFade) {
            val crossfadeTitle = stringResource(R.string.crossfade)
            row(crossfadeTitle, "fade", "gapless") {
                SliderRow(
                    icon = Icons.Rounded.Waves,
                    title = crossfadeTitle,
                    subtitle = stringResource(R.string.crossfade_subtitle),
                    value = if (crossfade == 0) stringResource(R.string.off) else "${crossfade}s",
                    sliderValue = crossfade.toFloat(),
                    onSliderValue = { AppSettings.setCrossfadeSeconds(it.roundToInt()) },
                    valueRange = 0f..12f,
                    steps = 11,
                )
            }
        }
        val automixTitle = stringResource(R.string.automix)
        row(automixTitle, "crossfade", "smart fade", "mix") {
            SettingsRow(
                icon = Icons.Rounded.AutoAwesome,
                title = automixTitle,
                subtitle = if (smartFade) {
                    stringResource(R.string.automix_enabled_subtitle)
                } else {
                    stringResource(R.string.automix_disabled_subtitle)
                },
                trailing = {
                    SettingsSwitch(
                        checked = smartFade,
                        onCheckedChange = AppSettings::setSmartFadeEnabled,
                    )
                },
                onClick = { AppSettings.setSmartFadeEnabled(!smartFade) },
            )
        }
        val automixPerformanceTitle = stringResource(R.string.automix_performance)
        row(automixPerformanceTitle, "cpu", "battery") {
            SettingsRow(
                icon = Icons.Rounded.Tune,
                title = automixPerformanceTitle,
                subtitle = stringResource(R.string.automix_performance_subtitle),
                value = automixPerformance.localizedLabel(),
                onClick = { sheets.pickingAutomixPerformance = true },
            )
        }
        val skipSilenceTitle = stringResource(R.string.skip_silence)
        row(skipSilenceTitle, "silence") {
            SettingsRow(
                icon = Icons.AutoMirrored.Rounded.VolumeOff,
                title = skipSilenceTitle,
                // Silence skipping is Media3's own processor, and
                // `DefaultAudioSink.configure` appends that processor list
                // only on its 16-bit branch — the float branch gets the
                // format converter and nothing else. So on a float track
                // this switch really does nothing, and the row says so
                // rather than letting someone toggle it and wonder. The
                // equaliser is unaffected: it runs upstream of that sink.
                subtitle = if (outputStatus.actualEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                    stringResource(R.string.skip_silence_float_subtitle)
                } else {
                    stringResource(R.string.skip_silence_subtitle)
                },
                trailing = {
                    SettingsSwitch(
                        checked = skipSilence,
                        onCheckedChange = AppSettings::setSkipSilence,
                    )
                },
                onClick = { AppSettings.setSkipSilence(!skipSilence) },
            )
        }
        val spatialAudioTitle = stringResource(R.string.spatial_audio)
        row(spatialAudioTitle, "surround", "3d") {
            SettingsRow(
                icon = Icons.Rounded.SurroundSound,
                title = spatialAudioTitle,
                // Widening a JOC stream would fight the object-based mix
                // Dolby has already spatialized, so the service holds this
                // off for the duration of an Atmos track — see
                // `PlaybackService.applySpatialAudioEnabled`. Said on the
                // row because the switch stays where the listener left it,
                // and a switch reading "on" over an effect that is not
                // running is the same silent lie the equaliser screen used
                // to tell.
                subtitle = if (playingDolbyAtmos) {
                    stringResource(R.string.spatial_audio_atmos_subtitle)
                } else {
                    stringResource(R.string.spatial_audio_subtitle)
                },
                trailing = {
                    SettingsSwitch(
                        checked = spatialAudio,
                        onCheckedChange = AppSettings::setSpatialAudio,
                    )
                },
                onClick = { AppSettings.setSpatialAudio(!spatialAudio) },
            )
        }
        val simAtmosTitle = stringResource(R.string.simulated_dolby_atmos)
        row(simAtmosTitle, "atmos", "simulated", "spatial", "widen") {
            SettingsRow(
                icon = Icons.Rounded.SurroundSound,
                title = simAtmosTitle,
                subtitle = stringResource(R.string.simulated_dolby_atmos_subtitle),
                trailing = {
                    SettingsSwitch(
                        checked = simulatedAtmos,
                        onCheckedChange = AppSettings::setSimulatedAtmos,
                    )
                },
                onClick = { AppSettings.setSimulatedAtmos(!simulatedAtmos) },
            )
        }
        if (softwareAtmosAvailable) {
            val unsupportedAtmosTitle = stringResource(R.string.unsupported_dolby_atmos)
            row(unsupportedAtmosTitle, "dolby", "atmos", "unsupported", "software", "battery") {
                SettingsRow(
                    icon = Icons.Rounded.SurroundSound,
                    title = unsupportedAtmosTitle,
                    subtitle = stringResource(R.string.unsupported_dolby_atmos_subtitle),
                    trailing = {
                        SettingsSwitch(
                            checked = unsupportedDolbyAtmos,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    showUnsupportedAtmosWarning = true
                                } else {
                                    AppSettings.setUnsupportedDolbyAtmos(false)
                                }
                            },
                        )
                    },
                    onClick = {
                        if (unsupportedDolbyAtmos) {
                            AppSettings.setUnsupportedDolbyAtmos(false)
                        } else {
                            showUnsupportedAtmosWarning = true
                        }
                    },
                )
            }
        }
        val smartAlignmentTitle = stringResource(R.string.smart_version_alignment)
        row(smartAlignmentTitle, "alignment", "sync", "waveform", "video", "audio", "skit", "intro") {
            SettingsRow(
                icon = Icons.Rounded.GraphicEq,
                title = smartAlignmentTitle,
                subtitle = stringResource(R.string.smart_version_alignment_subtitle),
                trailing = {
                    SettingsSwitch(
                        checked = smartVersionAlignment,
                        onCheckedChange = AppSettings::setSmartVersionAlignment,
                    )
                },
                onClick = { AppSettings.setSmartVersionAlignment(!smartVersionAlignment) },
            )
        }
        val playNextOnSwipeTitle = stringResource(R.string.play_next_on_swipe)
        row(playNextOnSwipeTitle, "swipe", "queue") {
            SettingsRow(
                icon = Icons.Rounded.PlaylistPlay,
                title = playNextOnSwipeTitle,
                subtitle = if (swipeToPlayNext) {
                    stringResource(R.string.swipe_plays_next)
                } else {
                    stringResource(R.string.swipe_adds_to_queue)
                },
                trailing = {
                    SettingsSwitch(
                        checked = swipeToPlayNext,
                        onCheckedChange = AppSettings::setSwipeToPlayNext,
                    )
                },
                onClick = { AppSettings.setSwipeToPlayNext(!swipeToPlayNext) },
            )
        }
        val dontRepeatSongsTitle = stringResource(R.string.dont_repeat_songs)
        row(dontRepeatSongsTitle, "radio", "suggestions") {
            SettingsRow(
                icon = Icons.Rounded.History,
                title = dontRepeatSongsTitle,
                subtitle = stringResource(R.string.dont_repeat_songs_subtitle),
                trailing = {
                    SettingsSwitch(
                        checked = dontRepeatSuggestions,
                        onCheckedChange = AppSettings::setDontRepeatSuggestions,
                    )
                },
                onClick = { AppSettings.setDontRepeatSuggestions(!dontRepeatSuggestions) },
            )
        }
        val stopMusicOnCloseTitle = stringResource(R.string.stop_music_on_close)
        row(stopMusicOnCloseTitle, "notification", "background") {
            SettingsRow(
                icon = Icons.Rounded.MusicOff,
                title = stopMusicOnCloseTitle,
                subtitle = stringResource(R.string.stop_music_on_close_subtitle),
                trailing = {
                    SettingsSwitch(
                        checked = stopOnTaskRemoved,
                        onCheckedChange = AppSettings::setStopOnTaskRemoved,
                    )
                },
                onClick = { AppSettings.setStopOnTaskRemoved(!stopOnTaskRemoved) },
            )
        }
    }

    if (showUnsupportedAtmosWarning) {
        ConfirmationAlert(
            title = stringResource(R.string.unsupported_dolby_atmos),
            description = stringResource(R.string.unsupported_dolby_atmos_warning),
            confirmLabel = stringResource(R.string.unsupported_dolby_atmos_enable),
            onConfirm = {
                AppSettings.setUnsupportedDolbyAtmos(true)
                showUnsupportedAtmosWarning = false
            },
            onDismiss = { showUnsupportedAtmosWarning = false },
        )
    }
}

@Composable
private fun AutomixPerformanceMode.localizedLabel(): String = stringResource(
    when (this) {
        AutomixPerformanceMode.EFFICIENT -> R.string.automix_mode_efficient
        AutomixPerformanceMode.BALANCED -> R.string.automix_mode_balanced
        AutomixPerformanceMode.PERFORMANCE -> R.string.automix_mode_performance
    },
)

/** The selected content provider, as the catalogue rows show it. */
@Composable
internal fun HomeProvider.localizedLabel(): String = stringResource(
    when (this) {
        HomeProvider.YOUTUBE -> R.string.home_provider_youtube
        HomeProvider.SPOTIFY -> R.string.spotify
    },
)

/**
 * The music-catalogue picker, opened on its row, with the restart it costs
 * said at the head.
 *
 * Choosing the other catalogue restarts the app. The choice is written
 * before the restart so the new process reads it, and the restart is what
 * makes it total: Flow's and Autoplay's learning were trained against the
 * catalogue being left behind. See [HomeProvider] and [AppRelaunch].
 */
@Composable
internal fun ColumnScope.HomeProviderMenu(close: () -> Unit) {
    val context = LocalContext.current
    val selected by AppSettings.homeProvider.collectAsStateWithLifecycle()
    SettingsChoiceMenu(
        title = stringResource(R.string.home_provider),
        subtitle = stringResource(R.string.home_provider_restart),
        choices = HomeProvider.entries.map { provider ->
            SettingsChoice(
                label = provider.localizedLabel(),
                detail = stringResource(
                    when (provider) {
                        HomeProvider.YOUTUBE -> R.string.home_provider_youtube_subtitle
                        HomeProvider.SPOTIFY -> R.string.home_provider_spotify_subtitle
                    },
                ),
                selected = provider == selected,
            ) {
                if (provider != selected) {
                    AppSettings.setHomeProvider(provider)
                    AppRelaunch.relaunch(context)
                }
            }
        },
        close = close,
    )
}


/** The Automix performance picker, opened on its row, with its warning kept whole at the head. */
@Composable
internal fun ColumnScope.AutomixPerformanceMenu(close: () -> Unit) {
    val selected by AppSettings.automixPerformanceMode.collectAsStateWithLifecycle()
    SettingsChoiceMenu(
        title = stringResource(R.string.automix_performance),
        subtitle = stringResource(R.string.automix_performance_warning),
        choices = AutomixPerformanceMode.entries.map { mode ->
            SettingsChoice(
                label = mode.localizedLabel(),
                detail = stringResource(
                    when (mode) {
                        AutomixPerformanceMode.EFFICIENT -> R.string.automix_mode_efficient_subtitle
                        AutomixPerformanceMode.BALANCED -> R.string.automix_mode_balanced_subtitle
                        AutomixPerformanceMode.PERFORMANCE -> R.string.automix_mode_performance_subtitle
                    },
                ),
                selected = mode == selected,
            ) { AppSettings.setAutomixPerformanceMode(mode) }
        },
        close = close,
    )
}

