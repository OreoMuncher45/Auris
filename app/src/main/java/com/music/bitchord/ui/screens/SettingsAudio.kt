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
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Upgrade
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.settings.OutputPcmMode
import com.music.bitchord.playback.AudioOutputStatus
import java.util.Locale

/** The Audio category: the source, streaming quality, output and the effects. */
@Composable
internal fun AudioSettings(
    search: SettingsSearch,
    sheets: SettingsSheets,
    onSources: () -> Unit,
    onEqualizer: () -> Unit,
) {
    val wifiQuality by AppSettings.audioQualityWifi.collectAsStateWithLifecycle()
    val cellularQuality by AppSettings.audioQualityCellular.collectAsStateWithLifecycle()
    val metered by AppSettings.meteredConnection.collectAsStateWithLifecycle()
    val seamlessQualityUpgrade by AppSettings.seamlessQualityUpgrade.collectAsStateWithLifecycle()
    val outputPcmMode by AppSettings.outputPcmMode.collectAsStateWithLifecycle()
    val bitPerfect by AppSettings.bitPerfect.collectAsStateWithLifecycle()
    val preferUsbDac by AppSettings.preferUsbDac.collectAsStateWithLifecycle()
    val loudnessNormalization by AppSettings.loudnessNormalization.collectAsStateWithLifecycle()
    val loudnessOffOnSpeaker by AppSettings.loudnessOffOnSpeaker.collectAsStateWithLifecycle()
    val outputStatus by AudioOutputStatus.current.collectAsStateWithLifecycle()
    SearchableSettingsGroup(search, SettingsCategory.AUDIO) {
        val sourceTitle = stringResource(R.string.source)
        val sourceSubtitle = stringResource(R.string.sources_subtitle)
        row(sourceTitle, sourceSubtitle, "addon", "lossless") {
            SettingsRow(
                icon = Icons.Rounded.Extension,
                title = sourceTitle,
                subtitle = sourceSubtitle,
                onClick = onSources,
            )
        }
        // Sits here because it overrides what the two rows beneath decide,
        // and next to them so the relationship is visible: this says which
        // copy plays, they say how good the streamed one may be.
        //
        // The badge carries the reason it is on — the switch, or the radio —
        // because "Offline mode" alone leaves open the question that
        // matters when a stream is not playing: did I ask for this, or did
        // the app decide?
        val offlineTitle = stringResource(R.string.offline_mode)
        val offlineSubtitle = stringResource(R.string.offline_mode_subtitle)
        val offlineOn by AppSettings.offlineMode.collectAsStateWithLifecycle()
        val detectedOffline by AppSettings.effectiveOffline.collectAsStateWithLifecycle()
        row(offlineTitle, offlineSubtitle, "downloads", "no internet", "local files") {
            SettingsRow(
                icon = Icons.Rounded.CloudOff,
                title = offlineTitle,
                subtitle = offlineSubtitle,
                badge = when {
                    offlineOn -> stringResource(R.string.in_use)
                    detectedOffline -> stringResource(R.string.offline_mode_detected)
                    else -> null
                },
                trailing = {
                    SettingsSwitch(
                        checked = offlineOn,
                        onCheckedChange = AppSettings::setOfflineMode,
                    )
                },
            )
        }
        val onWifiTitle = stringResource(R.string.on_wifi)
        row(onWifiTitle, "wi-fi", "streaming quality") {
            SettingsRow(
                icon = Icons.Rounded.Wifi,
                title = onWifiTitle,
                badge = stringResource(R.string.in_use).takeIf { metered == false },
                value = wifiQuality.localizedLabel(),
                onClick = { sheets.pickingQuality = QualityTarget.WIFI },
            )
        }
        val onMobileDataTitle = stringResource(R.string.on_mobile_data)
        row(onMobileDataTitle, "cellular", "streaming quality") {
            SettingsRow(
                icon = Icons.Rounded.SignalCellularAlt,
                title = onMobileDataTitle,
                badge = stringResource(R.string.in_use).takeIf { metered == true },
                value = cellularQuality.localizedLabel(),
                onClick = { sheets.pickingQuality = QualityTarget.CELLULAR },
            )
        }
        val outputPrecisionTitle = stringResource(R.string.output_precision)
        row(outputPrecisionTitle, "pcm", "bit depth", "sample rate", "dac") {
            SettingsRow(
                icon = Icons.Rounded.GraphicEq,
                title = outputPrecisionTitle,
                subtitle = buildString {
                    append(outputStatus.sink)
                    append(" · ")
                    append(outputStatus.deviceName)
                    (outputStatus.actualSampleRateHz ?: outputStatus.sampleRatesHz.firstOrNull())
                        ?.let { append(" · ${it / 1000.0} kHz") }
                    append(" · ")
                    append(AudioOutputStatus.encodingLabel(outputStatus))
                },
            )
            SegmentedControl(
                options = OutputPcmMode.entries.map(OutputPcmMode::label),
                selectedIndex = OutputPcmMode.entries.indexOf(outputPcmMode),
                onSelect = { AppSettings.setOutputPcmMode(OutputPcmMode.entries[it]) },
                modifier = Modifier.padding(start = TEXT_INSET, end = ROW_INSET, bottom = 14.dp),
            )
        }
        val bitPerfectTitle = stringResource(R.string.bit_perfect)
        row(
            bitPerfectTitle,
            "bit perfect",
            "bypass",
            "dsp",
            "crossfade",
            "automix",
            "lossless",
            divided = false,
        ) {
            SettingsSubRow(
                title = bitPerfectTitle,
                checked = bitPerfect,
                onCheckedChange = AppSettings::setBitPerfect,
                subtitle = stringResource(R.string.bit_perfect_subtitle),
            )
        }
        val preferUsbDacTitle = stringResource(R.string.prefer_usb_dac)
        row(preferUsbDacTitle, "headphone", "output") {
            SettingsSubRow(
                title = preferUsbDacTitle,
                checked = preferUsbDac,
                onCheckedChange = AppSettings::setPreferUsbDac,
                badge = stringResource(R.string.connected).takeIf { outputStatus.isUsb },
            )
        }
        val loudnessTitle = stringResource(R.string.loudness_normalization)
        row(loudnessTitle, "loudness", "volume", "normalize", "replaygain", "lufs") {
            SettingsRow(
                icon = Icons.Rounded.VolumeUp,
                title = loudnessTitle,
                subtitle = stringResource(R.string.loudness_normalization_subtitle),
                trailing = {
                    SettingsSwitch(
                        checked = loudnessNormalization,
                        onCheckedChange = AppSettings::setLoudnessNormalization,
                    )
                },
                onClick = { AppSettings.setLoudnessNormalization(!loudnessNormalization) },
            )
        }
        val loudnessSpeakerTitle = stringResource(R.string.loudness_off_on_speaker)
        row(loudnessSpeakerTitle, "loudness", "speaker", "normalize") {
            SettingsSubRow(
                title = loudnessSpeakerTitle,
                checked = loudnessOffOnSpeaker,
                onCheckedChange = AppSettings::setLoudnessOffOnSpeaker,
            )
        }
        // Off by default and behind a confirmation, because the blend can
        // be heard as doubling when the two masters differ. The switch only
        // asks; nothing is written until the dialog is confirmed.
        val seamlessUpgradeTitle = stringResource(R.string.seamless_quality_upgrades)
        row(seamlessUpgradeTitle, "lossless", "flac", "quality", "upgrade", "crossfade", "gapless") {
            SettingsRow(
                icon = Icons.Rounded.SwapHoriz,
                title = seamlessUpgradeTitle,
                subtitle = stringResource(R.string.seamless_quality_upgrades_subtitle),
                trailing = {
                    SettingsSwitch(
                        checked = seamlessQualityUpgrade,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                sheets.showSeamlessUpgradeConfirmation = true
                            } else {
                                AppSettings.setSeamlessQualityUpgrade(false)
                            }
                        },
                    )
                },
                onClick = {
                    if (seamlessQualityUpgrade) {
                        AppSettings.setSeamlessQualityUpgrade(false)
                    } else {
                        sheets.showSeamlessUpgradeConfirmation = true
                    }
                },
            )
        }
        // The songs kept on the device are upgraded to a better copy
        // while they play, unless the app cannot fetch one.
        //
        // The switch shows the effective answer, so it goes off with the
        // network and comes back with it — an upgrade is a fetch, and an
        // offline app cannot make one whatever the switch says. The
        // listener's own answer is kept meanwhile: coming back online
        // restores the upgrade rather than forgetting the choice.
        val autoUpgradeLocalTitle = stringResource(R.string.auto_upgrade_local_storage)
        val autoUpgradeLocal by AppSettings.effectiveAutoUpgradeLocalStorage
            .collectAsStateWithLifecycle()
        row(
            autoUpgradeLocalTitle,
            "local storage",
            "downloads",
            "saved",
            "upgrade",
            "quality",
            "lossless",
        ) {
            SettingsRow(
                icon = Icons.Rounded.Upgrade,
                title = autoUpgradeLocalTitle,
                subtitle = stringResource(
                    if (detectedOffline) {
                        R.string.auto_upgrade_local_storage_offline_subtitle
                    } else {
                        R.string.auto_upgrade_local_storage_subtitle
                    },
                ),
                enabled = !detectedOffline,
                trailing = {
                    SettingsSwitch(
                        checked = autoUpgradeLocal,
                        onCheckedChange = AppSettings::setAutoUpgradeLocalStorage,
                        enabled = !detectedOffline,
                    )
                },
                onClick = {
                    if (!detectedOffline) {
                        AppSettings.setAutoUpgradeLocalStorage(!autoUpgradeLocal)
                    }
                },
            )
        }
        // The system panel is not listed here as well. A device with a
        // Dolby or Dirac panel has something BitChord cannot reproduce and
        // keeps its row — but one level in, at the foot of the equaliser
        // screen, rather than as a second equaliser entry alongside ours.
        val equalizerTitle = stringResource(R.string.equalizer)
        row(equalizerTitle, "eq", "bass", "treble") {
            SettingsRow(
                icon = Icons.Rounded.Tune,
                title = equalizerTitle,
                subtitle = stringResource(R.string.equalizer_subtitle),
                onClick = onEqualizer,
            )
        }
        val jamesDspTitle = stringResource(R.string.jamesdsp)
        row(jamesDspTitle, "jamesdsp", "dsp", "reverb", "bass", "tube") {
            SettingsRow(
                icon = Icons.Rounded.GraphicEq,
                title = jamesDspTitle,
                subtitle = stringResource(R.string.jamesdsp_subtitle),
                onClick = { sheets.showJamesDsp = true },
            )
        }
    }
}

/** Which ceiling the open picker is editing. */
internal enum class QualityTarget(val icon: ImageVector) {
    WIFI(Icons.Rounded.Wifi),
    CELLULAR(Icons.Rounded.SignalCellularAlt),
}

@Composable
private fun QualityTarget.localizedTitle(): String = stringResource(
    if (this == QualityTarget.WIFI) R.string.wifi else R.string.mobile_data,
)

@Composable
private fun AudioQuality.localizedLabel(): String = stringResource(
    when (this) {
        AudioQuality.LOW -> R.string.low
        AudioQuality.MEDIUM -> R.string.medium
        AudioQuality.HIGH -> R.string.high
        AudioQuality.LOSSLESS -> R.string.lossless
        AudioQuality.DOLBY -> R.string.dolby_atmos
    },
)

/**
 * The streaming-quality picker for one connection, opened on its row: the
 * best rung first, each with what it costs an hour. Reads the ceiling itself,
 * so the check follows a choice the moment it's made.
 *
 * Writes the one ceiling being edited and nothing else. Which sources a rung
 * consults is read per stream off the connection in force; see
 * [AudioQuality.permits].
 */
@Composable
internal fun ColumnScope.QualityMenu(target: QualityTarget, close: () -> Unit) {
    val wifiQuality by AppSettings.audioQualityWifi.collectAsStateWithLifecycle()
    val cellularQuality by AppSettings.audioQualityCellular.collectAsStateWithLifecycle()
    val selected = when (target) {
        QualityTarget.WIFI -> wifiQuality
        QualityTarget.CELLULAR -> cellularQuality
    }
    SettingsChoiceMenu(
        title = stringResource(R.string.audio_quality),
        subtitle = stringResource(
            R.string.audio_quality_connection,
            target.localizedTitle().lowercase(Locale.getDefault()),
        ),
        // Best first — the option most people want shouldn't be last.
        choices = AudioQuality.entries.reversed().map { quality ->
            SettingsChoice(
                label = quality.localizedLabel(),
                detail = stringResource(R.string.quality_hourly, quality.detail, quality.hourly),
                selected = quality == selected,
            ) {
                when (target) {
                    QualityTarget.WIFI -> AppSettings.setAudioQualityWifi(quality)
                    QualityTarget.CELLULAR -> AppSettings.setAudioQualityCellular(quality)
                }
            }
        },
        close = close,
    )
}

