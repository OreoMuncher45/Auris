package com.music.bitchord.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings

/** The Podcasts category. */
@Composable
internal fun PodcastsSettings(
    search: SettingsSearch,
) {
    val podcastsEnabled by AppSettings.podcastsEnabled.collectAsStateWithLifecycle()
    SearchableSettingsGroup(search, SettingsCategory.PODCASTS) {
        val podcastsTitle = stringResource(R.string.podcasts)
        row(podcastsTitle, "podcast", "episodes", "episodes for later", "continue listening", "hide", "off") {
            SettingsRow(
                icon = Icons.Rounded.Podcasts,
                title = podcastsTitle,
                subtitle = stringResource(R.string.podcasts_enabled_subtitle),
                trailing = {
                    SettingsSwitch(
                        checked = podcastsEnabled,
                        onCheckedChange = AppSettings::setPodcastsEnabled,
                    )
                },
                onClick = { AppSettings.setPodcastsEnabled(!podcastsEnabled) },
            )
        }
    }
}
