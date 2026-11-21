package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.R
import com.music.bitchord.data.sources.backup.AddonBackup
import com.music.bitchord.data.sources.backup.AddonBackupFailure
import com.music.bitchord.data.sources.backup.AddonMergeMode
import com.music.bitchord.data.sources.backup.AddonPreviewItem
import com.music.bitchord.ui.components.menu.LocalMenuInteractive

/*
 * The add-ons backup dialogs. Each one is the shared frosted alert
 * (AlertScaffold): title, a description line that a status can replace,
 * PillTextFields, and stacked AlertActions. They match the addon editor next
 * to them on the Sources screen.
 *
 * A dialog that is [working] ignores dismissal and dims its actions, as the
 * addon editor does while it tests an address.
 */

/** Rows the preview lists before it folds the rest into "N more". */
private const val PREVIEW_ROWS = 3

@Composable
private fun AlertHeading(title: String, line: String?, lineIsError: Boolean = false, working: Boolean = false) {
    Text(
        text = title,
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
    )
    if (line != null) {
        Row(
            modifier = Modifier.padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (working) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                color = if (lineIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    shown: Boolean,
    onToggleShown: (() -> Unit)?,
    enabled: Boolean,
    last: Boolean,
    onDone: () -> Unit,
) {
    PillTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        enabled = enabled,
        isPassword = !shown,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (shown) KeyboardType.Text else KeyboardType.Password,
            autoCorrectEnabled = false,
            imeAction = if (last) ImeAction.Done else ImeAction.Next,
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone() }),
        trailing = onToggleShown?.let { toggle ->
            {
                Text(
                    text = stringResource(if (shown) R.string.addon_backup_hide else R.string.addon_backup_show),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = enabled, onClick = toggle)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        },
    )
}

/**
 * Choose a password, then [onExport] opens the system Save dialog.
 *
 * Export stays dimmed until both fields match and the password is at least
 * [AddonBackup.MIN_PASSWORD_LENGTH] long. Once the two fields agree, the
 * description line says why it's still dimmed.
 */
@Composable
fun AddonExportAlert(
    summary: AddonBackup.Summary,
    working: Boolean,
    error: String?,
    onExport: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    val longEnough = password.length >= AddonBackup.MIN_PASSWORD_LENGTH
    val matches = password == confirm
    val canSubmit = longEnough && matches && !working
    val submit = { if (canSubmit) onExport(password.toCharArray()) }
    val hint = when {
        working -> stringResource(R.string.addon_backup_encrypting)
        error != null -> error
        confirm.isNotEmpty() && !matches -> stringResource(R.string.addon_backup_password_mismatch)
        password.isNotEmpty() && !longEnough ->
            stringResource(R.string.addon_backup_password_short, AddonBackup.MIN_PASSWORD_LENGTH)
        else -> stringResource(R.string.addon_backup_export_description, summary.addons, summary.extensions)
    }
    val hintIsError = !working && (error != null || (confirm.isNotEmpty() && !matches))
    AlertScaffold(hazeState = null, onDismiss = { if (!working) onDismiss() }) {
        // Read where the dialog is drawn: a closing or covered dialog ignores the IME's Done too,
        // the way AlertAction already ignores taps.
        val interactiveNow = LocalMenuInteractive.current
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 19.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AlertHeading(stringResource(R.string.export_addons), hint, hintIsError, working)
            Spacer(Modifier.height(14.dp))
            PasswordField(
                value = password,
                onValueChange = { password = it },
                placeholder = stringResource(R.string.addon_backup_password),
                shown = shown,
                onToggleShown = { shown = !shown },
                enabled = !working,
                last = false,
                onDone = {},
            )
            Spacer(Modifier.height(8.dp))
            PasswordField(
                value = confirm,
                onValueChange = { confirm = it },
                placeholder = stringResource(R.string.addon_backup_confirm_password),
                shown = shown,
                onToggleShown = null,
                enabled = !working,
                last = true,
                onDone = { if (interactiveNow) submit() },
            )
            Text(
                text = stringResource(R.string.addon_backup_password_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp),
            )
        }
        AlertRule()
        AlertAction(
            label = stringResource(R.string.addon_backup_export_action),
            emphasised = true,
            onClick = submit,
            enabled = canSubmit,
        )
        AlertRule()
        AlertAction(
            label = stringResource(R.string.cancel),
            emphasised = false,
            onClick = onDismiss,
            enabled = !working,
        )
    }
}

/** Unlock a picked file. [wrongPassword] replaces the file name with the error line and keeps the typed text. */
@Composable
fun AddonUnlockAlert(
    fileName: String,
    working: Boolean,
    wrongPassword: Boolean,
    onUnlock: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    val canSubmit = password.isNotEmpty() && !working
    val submit = { if (canSubmit) onUnlock(password.toCharArray()) }
    AlertScaffold(hazeState = null, onDismiss = { if (!working) onDismiss() }) {
        // Read where the dialog is drawn: a closing or covered dialog ignores the IME's Done too,
        // the way AlertAction already ignores taps.
        val interactiveNow = LocalMenuInteractive.current
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 19.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AlertHeading(
                title = stringResource(R.string.import_addons),
                line = when {
                    working -> stringResource(R.string.addon_backup_unlocking)
                    wrongPassword -> stringResource(R.string.addon_backup_wrong_password)
                    else -> fileName
                },
                lineIsError = wrongPassword && !working,
                working = working,
            )
            Spacer(Modifier.height(14.dp))
            PasswordField(
                value = password,
                onValueChange = { password = it },
                placeholder = stringResource(R.string.addon_backup_password),
                shown = shown,
                onToggleShown = { shown = !shown },
                enabled = !working,
                last = true,
                onDone = { if (interactiveNow) submit() },
            )
        }
        AlertRule()
        AlertAction(
            label = stringResource(R.string.addon_backup_unlock),
            emphasised = true,
            onClick = submit,
            enabled = canSubmit,
        )
        AlertRule()
        AlertAction(
            label = stringResource(R.string.cancel),
            emphasised = false,
            onClick = onDismiss,
            enabled = !working,
        )
    }
}

/**
 * What the file holds against what's here, and how to merge. Nothing is
 * written until [onImport].
 */
@Composable
fun AddonImportPreviewAlert(
    items: List<AddonPreviewItem>,
    savedOn: String?,
    /** Session records in the file this device lacks: restored in either mode. */
    missingSessions: Int,
    working: Boolean,
    onImport: (AddonMergeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableStateOf(AddonMergeMode.SKIP_EXISTING) }
    val newCount = items.count { !it.installed }
    val importCount = newCount + if (mode == AddonMergeMode.REPLACE_EXISTING) items.size - newCount else 0
    val anyInstalled = newCount < items.size
    AlertScaffold(hazeState = null, onDismiss = { if (!working) onDismiss() }) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 19.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AlertHeading(
                title = stringResource(R.string.addon_backup_preview_title, items.size),
                line = if (working) {
                    stringResource(R.string.addon_backup_importing)
                } else {
                    savedOn?.let { stringResource(R.string.addon_backup_saved_on, it) }
                },
                working = working,
            )
            Spacer(Modifier.height(14.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(11.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                val shown = if (items.size > PREVIEW_ROWS + 1) items.take(PREVIEW_ROWS) else items
                shown.forEachIndexed { index, item ->
                    if (index > 0) AlertRule()
                    PreviewRow(
                        name = item.name,
                        kind = stringResource(
                            if (item.kind == AddonPreviewItem.Kind.ADDON) {
                                R.string.addon_backup_kind_addon
                            } else {
                                R.string.addon_backup_kind_extension
                            },
                        ),
                        state = stringResource(
                            if (item.installed) R.string.addon_backup_already_here else R.string.addon_backup_new,
                        ),
                    )
                }
                val rest = items.drop(shown.size)
                if (rest.isNotEmpty()) {
                    AlertRule()
                    PreviewRow(
                        name = stringResource(R.string.addon_backup_more, rest.size),
                        kind = null,
                        state = stringResource(R.string.addon_backup_more_new, rest.count { !it.installed }),
                    )
                }
            }
        }
        if (anyInstalled) {
            AlertRule()
            ChoiceRow(
                label = stringResource(R.string.addon_backup_skip_existing),
                detail = stringResource(R.string.addon_backup_skip_existing_detail),
                checked = mode == AddonMergeMode.SKIP_EXISTING,
                onClick = { if (!working) mode = AddonMergeMode.SKIP_EXISTING },
            )
            AlertRule()
            ChoiceRow(
                label = stringResource(R.string.addon_backup_replace_existing),
                detail = stringResource(R.string.addon_backup_replace_existing_detail),
                checked = mode == AddonMergeMode.REPLACE_EXISTING,
                onClick = { if (!working) mode = AddonMergeMode.REPLACE_EXISTING },
            )
        }
        AlertRule()
        AlertAction(
            label = when {
                importCount > 0 -> stringResource(R.string.addon_backup_import_action, importCount)
                // Everything is already here, but a sign-in this device lost can still come back.
                missingSessions > 0 -> stringResource(R.string.addon_backup_restore_signins)
                else -> stringResource(R.string.addon_backup_nothing_new)
            },
            emphasised = true,
            onClick = { onImport(mode) },
            enabled = (importCount > 0 || missingSessions > 0) && !working,
        )
        AlertRule()
        AlertAction(
            label = stringResource(R.string.cancel),
            emphasised = false,
            onClick = onDismiss,
            enabled = !working,
        )
    }
}

@Composable
private fun PreviewRow(name: String, kind: String?, state: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (kind != null) {
                Text(
                    text = " · $kind",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = state,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** A file that couldn't be opened, or nothing to export: one line and OK. Nothing has changed. */
@Composable
fun AddonBackupNoticeAlert(title: String, message: String, onDismiss: () -> Unit) {
    AlertScaffold(hazeState = null, onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 19.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AlertHeading(title, message)
        }
        AlertRule()
        AlertAction(label = stringResource(R.string.addon_backup_ok), emphasised = true, onClick = onDismiss)
    }
}

/** The title and message for a failure reason. Null reason means the file couldn't be read at all. */
@Composable
fun addonBackupFailureText(reason: AddonBackupFailure?): Pair<String, String> = when (reason) {
    AddonBackupFailure.DAMAGED ->
        stringResource(R.string.addon_backup_damaged_title) to stringResource(R.string.addon_backup_damaged_message)
    AddonBackupFailure.NOT_A_BACKUP ->
        stringResource(R.string.addon_backup_not_backup_title) to stringResource(R.string.addon_backup_not_backup_message)
    AddonBackupFailure.NEWER_VERSION ->
        stringResource(R.string.addon_backup_newer_title) to stringResource(R.string.addon_backup_newer_message)
    AddonBackupFailure.TOO_LARGE ->
        stringResource(R.string.addon_backup_too_large_title) to stringResource(R.string.addon_backup_too_large_message)
    // A wrong password is shown inline in the unlock dialog, not here.
    AddonBackupFailure.WRONG_PASSWORD, null ->
        stringResource(R.string.addon_backup_read_failed_title) to stringResource(R.string.addon_backup_read_failed_message)
}
