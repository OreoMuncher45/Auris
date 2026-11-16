package com.music.bitchord.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.onboarding.OnboardingState

/** The Advanced category. */
@Composable
internal fun AdvancedSettings(
    search: SettingsSearch,
) {
    val nerdStats by AppSettings.showNerdStats.collectAsStateWithLifecycle()
    SearchableSettingsGroup(search, SettingsCategory.ADVANCED) {
        // The way back into the first-run flow, for anybody who wants to
        // see it again — a tester, or a listener showing somebody what the
        // app does on the first launch. It forgets the flow has been seen
        // and then asks the activity to raise it: settings cannot show a
        // full-screen flow itself, and the activity is the thing that owns
        // the composition it would sit on.
        val replayContext = LocalContext.current
        val replayOnboardingTitle = "Replay onboarding"
        row(replayOnboardingTitle, "debug", "onboarding", "setup", "first run") {
            SettingsRow(
                icon = Icons.Rounded.AutoAwesome,
                title = replayOnboardingTitle,
                subtitle = "Show the first-run flow again",
                onClick = {
                    OnboardingState.reset(replayContext)
                    OnboardingState.requestReplay()
                },
            )
        }
        val showNerdStatsTitle = stringResource(R.string.show_nerd_stats)
        row(showNerdStatsTitle, "debug", "bitrate", "codec") {
            SettingsRow(
                icon = Icons.Rounded.BarChart,
                title = showNerdStatsTitle,
                subtitle = stringResource(R.string.show_nerd_stats_subtitle),
                trailing = {
                    SettingsSwitch(
                        checked = nerdStats,
                        onCheckedChange = AppSettings::setShowNerdStats,
                    )
                },
                onClick = { AppSettings.setShowNerdStats(!nerdStats) },
            )
        }
    }
}
