package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.icons.BitChordIcons

/** The Performance category: high performance mode and its refresh rate. */
@Composable
internal fun PerformanceSettings(
    search: SettingsSearch,
    sheets: SettingsSheets,
    selectedPerformanceRefreshRate: Int,
    supportedRefreshRates: List<Int>,
) {
    val highPerformanceMode by AppSettings.highPerformanceMode.collectAsStateWithLifecycle()
    SearchableSettingsGroup(search, SettingsCategory.PERFORMANCE) {
        val highPerformanceModeTitle = stringResource(R.string.high_performance_mode)
        row(highPerformanceModeTitle, "frame rate", "refresh rate", "hz", "battery", "smooth") {
            SettingsRow(
                icon = BitChordIcons.Performance,
                title = highPerformanceModeTitle,
                subtitle = if (highPerformanceMode) {
                    stringResource(R.string.high_performance_active, selectedPerformanceRefreshRate)
                } else {
                    stringResource(R.string.high_performance_subtitle)
                },
                trailing = {
                    SettingsSwitch(
                        checked = highPerformanceMode,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                sheets.showPerformanceWarning = true
                            } else {
                                AppSettings.setHighPerformanceMode(false)
                            }
                        },
                    )
                },
                onClick = {
                    if (highPerformanceMode) {
                        AppSettings.setHighPerformanceMode(false)
                    } else {
                        sheets.showPerformanceWarning = true
                    }
                },
            )
        }
        if (highPerformanceMode) {
            val refreshRateTitle = stringResource(R.string.refresh_rate)
            row(refreshRateTitle, "hz", "frame rate") {
                SettingsRow(
                    icon = BitChordIcons.FrameRate,
                    title = refreshRateTitle,
                )
                SegmentedControl(
                    options = supportedRefreshRates.map { "$it Hz" },
                    selectedIndex = supportedRefreshRates.indexOf(selectedPerformanceRefreshRate),
                    onSelect = { index ->
                        AppSettings.setPerformanceRefreshRate(supportedRefreshRates[index])
                    },
                    modifier = Modifier.padding(
                        start = TEXT_INSET,
                        end = ROW_INSET,
                        bottom = 14.dp,
                    ),
                )
            }
        }
    }
}
