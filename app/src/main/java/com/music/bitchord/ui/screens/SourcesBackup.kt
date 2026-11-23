package com.music.bitchord.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.music.bitchord.BuildConfig
import com.music.bitchord.R
import com.music.bitchord.data.sources.backup.AddonBackup
import com.music.bitchord.data.sources.backup.AddonBackupFailure
import com.music.bitchord.data.sources.backup.AddonPreviewItem
import com.music.bitchord.ui.components.AddonBackupNoticeAlert
import com.music.bitchord.ui.components.AddonExportAlert
import com.music.bitchord.ui.components.AddonImportPreviewAlert
import com.music.bitchord.ui.components.AddonUnlockAlert
import com.music.bitchord.ui.components.addonBackupFailureText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Where the add-ons backup flow is. One dialog at a time, or none. */
private sealed interface BackupStep {
    data object Idle : BackupStep

    /** The password dialog. [working] once the file is chosen and encryption is running. */
    data class Export(
        val summary: AddonBackup.Summary,
        val working: Boolean = false,
        val failed: Boolean = false,
        val tooLarge: Boolean = false,
    ) : BackupStep

    data class Unlock(
        val sealed: ByteArray,
        val fileName: String,
        val working: Boolean = false,
        val wrongPassword: Boolean = false,
    ) : BackupStep {
        // Identity, not content: the file isn't compared byte by byte on every recomposition.
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    data class Preview(
        val unlocked: AddonBackup.Unlocked,
        val items: List<AddonPreviewItem>,
        val working: Boolean = false,
    ) : BackupStep

    /** A file that couldn't be opened. Null [reason] means it couldn't be read at all. */
    data class Failed(val reason: AddonBackupFailure?) : BackupStep

    data object NothingToExport : BackupStep
}

/**
 * The Sources screen's Backup group: Export add-ons and Import add-ons, and
 * the dialogs between them.
 *
 * Two static rows in the screen's column. Nothing here reads state during a
 * scroll, and no file is opened until a row is tapped. Key derivation,
 * encryption, zip and file I/O all run in [AddonBackup] off the main thread,
 * so the dialogs keep animating while a half-second PBKDF2 runs.
 *
 * Results show as each row's subtitle, as the settings backup in Settings →
 * Data does. There's no snackbar.
 */
@Composable
internal fun AddonBackupGroup() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<BackupStep>(BackupStep.Idle) }
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    // Held only between the password dialog and the Save dialog's answer, then cleared.
    var pendingPassword by remember { mutableStateOf<CharArray?>(null) }
    // Bumped by every file pick and every dialog change, so a slow read that
    // finishes late can tell it is stale and leave the newer dialog alone.
    var generation by remember { mutableStateOf(0) }

    val res = context.resources

    val savePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(AddonBackup.MIME_TYPE),
    ) { uri: Uri? ->
        val password = pendingPassword
        pendingPassword = null
        val current = step as? BackupStep.Export
        if (uri == null || password == null || current == null) {
            // Cancelled at the Save dialog: back to the password dialog, password cleared.
            password?.fill('\u0000')
            return@rememberLauncherForActivityResult
        }
        step = current.copy(working = true, failed = false)
        scope.launch {
            AddonBackup.export(context, uri, password, BuildConfig.VERSION_NAME)
                .onSuccess { summary ->
                    exportStatus = res.getString(R.string.addon_backup_exported, summary.addons + summary.extensions)
                    step = BackupStep.Idle
                }
                .onFailure { error ->
                    step = current.copy(
                        working = false,
                        failed = true,
                        tooLarge = AddonBackup.reasonOf(error) == AddonBackupFailure.TOO_LARGE,
                    )
                }
        }
    }

    val openPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val mine = ++generation
        scope.launch {
            val name = withContext(Dispatchers.IO) { displayName(context, uri) } ?: AddonBackup.suggestedName()
            val result = AddonBackup.read(context, uri)
            // Another pick, or another dialog, came along while this file was read.
            if (mine != generation || step != BackupStep.Idle) return@launch
            result
                .onSuccess { step = BackupStep.Unlock(it, name) }
                .onFailure { step = BackupStep.Failed(AddonBackup.reasonOf(it)) }
        }
    }

    SettingsGroup(
        header = stringResource(R.string.addon_backup_header),
        footer = stringResource(R.string.addon_backup_footer),
    ) {
        SettingsRow(
            icon = Icons.Rounded.FileUpload,
            title = stringResource(R.string.export_addons),
            subtitle = exportStatus ?: stringResource(R.string.export_addons_subtitle),
            onClick = {
                generation++
                val summary = AddonBackup.summary()
                step = if (summary.addons + summary.extensions == 0) {
                    BackupStep.NothingToExport
                } else {
                    BackupStep.Export(summary)
                }
            },
        )
        RowDivider()
        SettingsRow(
            icon = Icons.Rounded.FileDownload,
            title = stringResource(R.string.import_addons),
            subtitle = importStatus ?: stringResource(R.string.import_addons_subtitle),
            // Any type: Drive and most other providers don't know `.aurisaddons`
            // and would grey the file out under a narrower filter.
            onClick = { openPicker.launch(arrayOf("*/*")) },
        )
    }

    when (val current = step) {
        BackupStep.Idle -> Unit
        is BackupStep.Export -> AddonExportAlert(
            summary = current.summary,
            working = current.working,
            error = when {
                current.tooLarge -> stringResource(R.string.addon_backup_export_too_large)
                current.failed -> stringResource(R.string.addon_backup_export_failed)
                else -> null
            },
            onExport = { password ->
                pendingPassword?.fill('\u0000')
                pendingPassword = password
                savePicker.launch(AddonBackup.suggestedName())
            },
            onDismiss = { step = BackupStep.Idle },
        )
        is BackupStep.Unlock -> AddonUnlockAlert(
            fileName = current.fileName,
            working = current.working,
            wrongPassword = current.wrongPassword,
            onUnlock = { password ->
                step = current.copy(working = true, wrongPassword = false)
                scope.launch {
                    AddonBackup.unlock(current.sealed, password)
                        .onSuccess { unlocked ->
                            step = BackupStep.Preview(unlocked, AddonBackup.preview(unlocked))
                        }
                        .onFailure { error ->
                            val reason = AddonBackup.reasonOf(error)
                            step = if (reason == AddonBackupFailure.WRONG_PASSWORD) {
                                current.copy(working = false, wrongPassword = true)
                            } else {
                                BackupStep.Failed(reason)
                            }
                        }
                }
            },
            onDismiss = { step = BackupStep.Idle },
        )
        is BackupStep.Preview -> AddonImportPreviewAlert(
            items = current.items,
            missingSessions = current.unlocked.missingSessions,
            savedOn = current.unlocked.exportedAt.takeIf { it > 0 }
                ?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) },
            working = current.working,
            onImport = { mode ->
                step = current.copy(working = true)
                scope.launch {
                    val result = AddonBackup.apply(current.unlocked, mode)
                    importStatus = if (result.failed > 0) {
                        res.getString(R.string.addon_backup_imported_failures, result.imported, result.failed)
                    } else {
                        res.getString(R.string.addon_backup_imported, result.imported, result.skipped)
                    }
                    step = BackupStep.Idle
                }
            },
            onDismiss = { step = BackupStep.Idle },
        )
        is BackupStep.Failed -> {
            val (title, message) = addonBackupFailureText(current.reason)
            AddonBackupNoticeAlert(title, message, onDismiss = { step = BackupStep.Idle })
        }
        BackupStep.NothingToExport -> AddonBackupNoticeAlert(
            title = stringResource(R.string.export_addons),
            message = stringResource(R.string.addon_backup_nothing_to_export),
            onDismiss = { step = BackupStep.Idle },
        )
    }
}

/** The picked document's name, for the unlock dialog. Null when the provider won't say. */
private fun displayName(context: android.content.Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
