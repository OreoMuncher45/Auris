package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.util.AppRelaunch

/**
 * What somebody might type looking for the catalogue switch.
 *
 * The service names first, because that is what people look for: they want
 * "Spotify" or "YouTube" and don't know the app calls the choice a catalogue.
 */
internal val MUSIC_CATALOGUE_KEYWORDS = arrayOf(
    "spotify", "youtube", "youtube music", "catalogue", "catalog", "provider", "source",
    "search", "library", "home",
)

/**
 * The catalogue switch, as its own card at the top of the settings index.
 *
 * It used to be a row under Playback, between two Automix rows and with the
 * same icon as one of them, and people looking for "Spotify or YouTube" did not
 * find it there. Where Home and Search come from is a bigger decision than any
 * one category, so the card sits above the categories and its header and title
 * name both services.
 *
 * Drawn on the index, and on the index while a search is running, so the
 * search still turns it up.
 */
@Composable
internal fun MusicCatalogueSettings(
    search: SettingsSearch,
    sheets: SettingsSheets,
) {
    val homeProvider by AppSettings.homeProvider.collectAsStateWithLifecycle()
    val header = stringResource(R.string.music_catalogue)
    val title = stringResource(R.string.music_catalogue_row)
    SearchableSettingsGroup(
        search = search,
        header = header,
        footer = stringResource(R.string.home_provider_subtitle),
    ) {
        row(title, stringResource(R.string.home_provider), *MUSIC_CATALOGUE_KEYWORDS) {
            // The catalogue in force goes under the title rather than on the
            // right, where it would be measured first and leave the title,
            // which names both services, to be cut short at large font sizes.
            val subtitle = stringResource(R.string.music_catalogue_row_subtitle)
            SettingsRow(
                icon = Icons.Rounded.Album,
                title = title,
                subtitleContent = {
                    Column {
                        Text(
                            text = homeProvider.localizedLabel(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 5,
                        )
                    }
                },
                onClick = { sheets.pickingHomeProvider = true },
                // Wraps rather than cutting off a service name at large font
                // sizes or on a narrow screen.
                titleMaxLines = 3,
            )
        }
    }
}

/**
 * The catalogue picker in its sheet, with the restart that applies a change.
 *
 * Shared by Settings and the account sheet, so both open the same
 * [HomeProviderSheet] and both restart the same way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeProviderPicker(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val homeProvider by AppSettings.homeProvider.collectAsStateWithLifecycle()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        HomeProviderSheet(
            selected = homeProvider,
            onSelect = { provider ->
                onDismiss()
                if (provider != homeProvider) {
                    // Written before the restart so the new process reads it,
                    // and the restart is what makes it total: Flow's and
                    // Autoplay's learning were trained against the provider
                    // being left behind. See [HomeProvider] and [AppRelaunch].
                    AppSettings.setHomeProvider(provider)
                    AppRelaunch.relaunch(context)
                }
            },
        )
    }
}
