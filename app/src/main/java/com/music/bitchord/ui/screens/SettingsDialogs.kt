package com.music.bitchord.ui.screens

import androidx.compose.runtime.LaunchedEffect
import com.music.bitchord.ui.components.MenuOrigin
import com.music.bitchord.ui.components.menu.LocalMenuHost
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.AudioCache

/**
 * Which of the settings screen's sheets and dialogs is open.
 *
 * One holder rather than a dozen locals, so the category composables can open
 * a sheet without taking a setter for each, and so the dialogs can live in
 * [SettingsDialogs] while [SettingsScreen] keeps owning their state: a category
 * page leaves the composition when the search or the open category changes,
 * and the flags must not go with it.
 */
@Stable
internal class SettingsSheets {
    /** The connection whose quality ceiling is being picked, or null. */
    var pickingQuality by mutableStateOf<QualityTarget?>(null)
    var pickingDownloadQuality by mutableStateOf(false)
    var pickingAutomixPerformance by mutableStateOf(false)
    var pickingHomeProvider by mutableStateOf(false)
    var confirmImport by mutableStateOf(false)
    var confirmClearSongCache by mutableStateOf(false)
    var showBlockedArtists by mutableStateOf(false)
    var showJamesDsp by mutableStateOf(false)
    var showPerformanceWarning by mutableStateOf(false)
    var showPerformanceConfirmation by mutableStateOf(false)
    var showSeamlessUpgradeConfirmation by mutableStateOf(false)

    // The customisation sheets, opened from the rows that used to live on the
    // Customisation page.
    var showPlayerColors by mutableStateOf(false)
    var showShelfOrder by mutableStateOf(false)
    var showFontSheet by mutableStateOf(false)
}

/**
 * The sheets and dialogs the settings rows open, drawn after the page itself.
 *
 * Each picker reads the setting it edits only while it is open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsDialogs(
    sheets: SettingsSheets,
    homeShelfTitles: List<String>,
    importPicker: ActivityResultLauncher<Array<String>>,
    batterySettingsLauncher: ActivityResultLauncher<Intent>,
) {
    val context = LocalContext.current

    if (sheets.showBlockedArtists) {
        BlockedArtistsSheet(onDismiss = { sheets.showBlockedArtists = false })
    }

    if (sheets.showPlayerColors) {
        PlayerColorsSheet(onDismiss = { sheets.showPlayerColors = false })
    }

    if (sheets.showShelfOrder) {
        HomeShelfOrderSheet(
            homeShelfTitles = homeShelfTitles,
            onDismiss = { sheets.showShelfOrder = false },
        )
    }

    if (sheets.showFontSheet) {
        FontSheet(onDismiss = { sheets.showFontSheet = false })
    }

    if (sheets.showJamesDsp) {
        JamesDspSheet(onDismiss = { sheets.showJamesDsp = false })
    }

    // The option pickers open on the row that asked for them, in the app's
    // anchored menu, drawn over everything by the root's menu host. Each
    // flag is only the request: handed to the host here and cleared at once,
    // the host owns the menu from then on. The row recorded where it is as
    // it was tapped (see SettingsRow).
    val menuHost = LocalMenuHost.current
    LaunchedEffect(sheets.pickingQuality) {
        val target = sheets.pickingQuality ?: return@LaunchedEffect
        menuHost?.open(MenuOrigin.consume()) { close -> QualityMenu(target, close) }
        sheets.pickingQuality = null
    }
    LaunchedEffect(sheets.pickingDownloadQuality) {
        if (!sheets.pickingDownloadQuality) return@LaunchedEffect
        menuHost?.open(MenuOrigin.consume()) { close -> DownloadQualityMenu(close) }
        sheets.pickingDownloadQuality = false
    }
    if (sheets.pickingHomeProvider) {
        HomeProviderPicker(onDismiss = { sheets.pickingHomeProvider = false })
    }
    LaunchedEffect(sheets.pickingAutomixPerformance) {
        if (!sheets.pickingAutomixPerformance) return@LaunchedEffect
        menuHost?.open(MenuOrigin.consume()) { close -> AutomixPerformanceMenu(close) }
        sheets.pickingAutomixPerformance = false
    }

    // Everything in the Cached songs folder goes with it, so say so first.
    if (sheets.confirmClearSongCache) {
        AlertDialog(
            onDismissRequest = { sheets.confirmClearSongCache = false },
            title = { Text(stringResource(R.string.clear_song_cache_title)) },
            text = { Text(stringResource(R.string.clear_song_cache_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    sheets.confirmClearSongCache = false
                    AudioCache.clear {
                        Toast.makeText(context, context.getString(R.string.song_cache_cleared), Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text(stringResource(R.string.clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { sheets.confirmClearSongCache = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // Asked before the picker opens rather than after a file is chosen: the
    // thing being confirmed is that this device's own history is about to be
    // thrown away, and that is true whichever file gets picked.
    if (sheets.confirmImport) {
        AlertDialog(
            onDismissRequest = { sheets.confirmImport = false },
            title = { Text(stringResource(R.string.import_backup_title)) },
            text = {
                Text(stringResource(R.string.import_backup_warning))
            },
            confirmButton = {
                TextButton(onClick = {
                    sheets.confirmImport = false
                    importPicker.launch(arrayOf("application/json", "text/plain", "*/*"))
                }) {
                    Text(stringResource(R.string.choose_file))
                }
            },
            dismissButton = {
                TextButton(onClick = { sheets.confirmImport = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (sheets.showPerformanceWarning) {
        AlertDialog(
            onDismissRequest = { sheets.showPerformanceWarning = false },
            title = { Text(stringResource(R.string.high_performance_before_enabling)) },
            text = { Text(stringResource(R.string.high_performance_battery_warning)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        sheets.showPerformanceWarning = false
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        val settingsIntent = intent.takeIf {
                            it.resolveActivity(context.packageManager) != null
                        } ?: Intent(Settings.ACTION_SETTINGS)
                        batterySettingsLauncher.launch(settingsIntent)
                    },
                ) {
                    Text(stringResource(R.string.open_battery_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { sheets.showPerformanceWarning = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (sheets.showPerformanceConfirmation) {
        AlertDialog(
            onDismissRequest = { sheets.showPerformanceConfirmation = false },
            title = { Text(stringResource(R.string.enable_high_performance_title)) },
            text = { Text(stringResource(R.string.enable_high_performance_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        sheets.showPerformanceConfirmation = false
                        AppSettings.setHighPerformanceMode(true)
                    },
                ) {
                    Text(stringResource(R.string.enable))
                }
            },
            dismissButton = {
                TextButton(onClick = { sheets.showPerformanceConfirmation = false }) {
                    Text(stringResource(R.string.not_yet))
                }
            },
        )
    }

    if (sheets.showSeamlessUpgradeConfirmation) {
        AlertDialog(
            onDismissRequest = { sheets.showSeamlessUpgradeConfirmation = false },
            title = { Text(stringResource(R.string.enable_seamless_upgrades_title)) },
            text = { Text(stringResource(R.string.enable_seamless_upgrades_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        sheets.showSeamlessUpgradeConfirmation = false
                        AppSettings.setSeamlessQualityUpgrade(true)
                    },
                ) {
                    Text(stringResource(R.string.enable))
                }
            },
            dismissButton = {
                TextButton(onClick = { sheets.showSeamlessUpgradeConfirmation = false }) {
                    Text(stringResource(R.string.not_yet))
                }
            },
        )
    }
}
