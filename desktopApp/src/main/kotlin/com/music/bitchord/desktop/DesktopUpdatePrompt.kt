package com.music.bitchord.desktop

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Asks once, when the window opens, whether a newer release is out, and offers it if so.
 *
 * Lifted out of [BitChordDesktopApp] with the state it owns, so the answer arriving recomposes
 * this prompt rather than the whole window.
 */
@Composable
internal fun DesktopUpdatePrompt() {
    var availableUpdate by remember { mutableStateOf<DesktopUpdateChecker.UpdateInfo?>(null) }
    LaunchedEffect(Unit) { availableUpdate = DesktopUpdateChecker.check() }
    availableUpdate?.let { update ->
        AlertDialog(
            onDismissRequest = { availableUpdate = null },
            title = { Text(DesktopStrings["d_update_available", "Update available"]) },
            text = {
                Text(
                    "BitChord ${update.version} is out — you have ${DesktopUpdateChecker.currentVersion}." +
                        (update.notes?.takeIf { it.isNotBlank() }?.let { "\n\n${it.take(600)}" } ?: ""),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    DesktopExternalLinks.open(update.downloadUrl ?: update.releaseUrl)
                    availableUpdate = null
                }) { Text(DesktopStrings["d_download", "Download"]) }
            },
            dismissButton = {
                TextButton(onClick = { availableUpdate = null }) {
                    Text(DesktopStrings["d_later", "Later"])
                }
            },
        )
    }
}
