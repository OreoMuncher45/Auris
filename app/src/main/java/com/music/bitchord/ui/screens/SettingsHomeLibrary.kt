package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.LibrarySort

/** The Home & library category. */
@Composable
internal fun HomeLibrarySettings(
    search: SettingsSearch,
    sheets: SettingsSheets,
    onImportSpotifyPlaylist: () -> Unit,
) {
    val homeFlowVisible by AppSettings.homeFlowVisible.collectAsStateWithLifecycle()
    val homeReplayVisible by AppSettings.homeReplayVisible.collectAsStateWithLifecycle()
    val blockedArtists by AppSettings.blockedArtists.collectAsStateWithLifecycle()
    val blockAi by AppSettings.blockAiMusic.collectAsStateWithLifecycle()
    val librarySort by AppSettings.librarySort.collectAsStateWithLifecycle()
    val libraryReplayVisible by AppSettings.libraryReplayVisible.collectAsStateWithLifecycle()
    SearchableSettingsGroup(search, SettingsCategory.HOME_LIBRARY) {
        val homeFlowVisibleTitle = stringResource(R.string.show_flow_on_home)
        row(homeFlowVisibleTitle, "flow", "home", "hide") {
            SettingsRow(
                icon = Icons.Rounded.Home,
                title = homeFlowVisibleTitle,
                subtitle = stringResource(R.string.show_flow_on_home_subtitle),
                trailing = {
                    SettingsSwitch(
                        checked = homeFlowVisible,
                        onCheckedChange = AppSettings::setHomeFlowVisible,
                    )
                },
                onClick = { AppSettings.setHomeFlowVisible(!homeFlowVisible) },
            )
        }
        val importSpotifyTitle = stringResource(R.string.import_spotify)
        row(importSpotifyTitle, "spotify", "import", "playlist") {
            SettingsRow(
                icon = Icons.Rounded.Download,
                title = importSpotifyTitle,
                subtitle = stringResource(R.string.import_spotify_subtitle),
                onClick = onImportSpotifyPlaylist,
            )
        }
        val blockedArtistsTitle = stringResource(R.string.blocked_artists)
        row(blockedArtistsTitle, "block", "hide", "artist") {
            SettingsRow(
                icon = Icons.Rounded.VisibilityOff,
                title = blockedArtistsTitle,
                subtitle = if (blockedArtists.isEmpty()) {
                    stringResource(R.string.blocked_artists_subtitle)
                } else {
                    stringResource(R.string.blocked_artists_count, blockedArtists.size)
                },
                onClick = { sheets.showBlockedArtists = true },
            )
        }
        val blockAiTitle = stringResource(R.string.block_ai_music)
        row(blockAiTitle, "ai", "block") {
            SettingsRow(
                icon = Icons.Rounded.AutoAwesome,
                title = blockAiTitle,
                subtitle = stringResource(R.string.block_ai_music_subtitle),
                trailing = {
                    SettingsSwitch(
                        checked = blockAi,
                        onCheckedChange = AppSettings::setBlockAiMusic,
                    )
                },
                onClick = { AppSettings.setBlockAiMusic(!blockAi) },
            )
        }
        val homeReplayVisibleTitle = stringResource(R.string.show_replay_on_home)
        row(homeReplayVisibleTitle, "replay", "home", "hide", "card") {
            SettingsRow(
                icon = Icons.Rounded.Replay,
                title = homeReplayVisibleTitle,
                subtitle = stringResource(R.string.show_replay_on_home_subtitle),
                trailing = {
                    SettingsSwitch(
                        checked = homeReplayVisible,
                        onCheckedChange = AppSettings::setHomeReplayVisible,
                    )
                },
                onClick = { AppSettings.setHomeReplayVisible(!homeReplayVisible) },
            )
        }
        val shelfOrderTitle = stringResource(R.string.settings_home_order)
        row(shelfOrderTitle, "shelf", "shelves", "order", "reorder", "arrange", "home") {
            SettingsRow(
                icon = Icons.Rounded.ViewList,
                title = shelfOrderTitle,
                subtitle = stringResource(R.string.custom_home_help),
                onClick = { sheets.showShelfOrder = true },
            )
        }
        val showReplayTitle = stringResource(R.string.custom_show_replay)
        row(showReplayTitle, "replay", "library", "stats") {
            SettingsRow(
                icon = Icons.Rounded.BarChart,
                title = showReplayTitle,
                subtitle = stringResource(R.string.custom_show_replay_subtitle),
                trailing = {
                    SettingsSwitch(
                        checked = libraryReplayVisible,
                        onCheckedChange = AppSettings::setLibraryReplayVisible,
                    )
                },
                onClick = { AppSettings.setLibraryReplayVisible(!libraryReplayVisible) },
            )
        }
        val librarySortTitle = stringResource(R.string.custom_library_sort)
        row(librarySortTitle, "sort", "library", "order", "a-z") {
            SettingsRow(icon = Icons.Rounded.Sort, title = librarySortTitle)
            SegmentedControl(
                options = listOf(
                    stringResource(R.string.sort_default),
                    stringResource(R.string.sort_title_asc),
                    stringResource(R.string.sort_title_desc),
                ),
                selectedIndex = LibrarySort.entries.indexOf(librarySort),
                onSelect = { AppSettings.setLibrarySort(LibrarySort.entries[it]) },
                modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
            )
        }
    }
}
