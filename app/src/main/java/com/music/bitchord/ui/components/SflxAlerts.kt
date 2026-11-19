package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.sources.mornye.InstalledSflx
import com.music.bitchord.data.sources.mornye.SflxCatalog
import com.music.bitchord.data.sources.mornye.SflxRegistry
import com.music.bitchord.data.sources.mornye.SflxRegistryEntry
import com.music.bitchord.data.sources.mornye.SflxSetting
import com.music.bitchord.data.sources.mornye.SflxSignedSession
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Installing an extension from a link.
 *
 * One field, because the format is one link: the extension's own `.sflx`, or a
 * store's `registry.json`. Which of the two it is cannot be known until it has
 * been fetched, so the screen fetches first and then shows what it found — a
 * package to confirm, or the extensions that store offers, each with the name
 * and version it will install.
 *
 * Nothing is installed without being seen first. That is the point of the two
 * steps: a link typed from memory should be inspected before it becomes code
 * running on the device, and a store's entries should be chosen by name rather
 * than installed wholesale.
 */
@Composable
fun SflxInstallDialog(
    onDismiss: () -> Unit,
    onInstalled: () -> Unit,
) {
    // Empty on purpose, and staying that way.
    //
    // Nothing is bundled, suggested or pre-filled: an addon store is something
    // the listener adds by pasting its own link. A default is a recommendation
    // — "this is where addons come from" — and installing somebody else's code
    // has to be the listener's decision, made with their own link, not this
    // app's suggestion.
    var link by remember { mutableStateOf("") }
    var offer by remember { mutableStateOf<SflxCatalog.InstallOffer?>(null) }
    val state by SflxCatalog.install.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val fetching = state is SflxCatalog.InstallState.Working

    LaunchedEffect(state) {
        if (state is SflxCatalog.InstallState.Done) onInstalled()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Install an extension") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Paste a link to an extension package, or to a store's registry — or browse " +
                        "the Mornye store below. Extensions are separate downloads; nothing is " +
                        "bundled with Auris.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = link,
                    onValueChange = {
                        link = it
                        offer = null
                    },
                    label = { Text("https://example.com/thing.sflx") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )

                // The store Auris knows by name, one tap away.
                //
                // A button rather than a pre-filled field, because the point
                // of naming it is that the listener is shown where extensions
                // come from instead of being told to go and find out. Nothing
                // is fetched until the tap and nothing is installed until the
                // package is picked by name — the same two steps every other
                // store gets.
                TextButton(
                    enabled = !fetching && offer == null,
                    onClick = {
                        scope.launch {
                            SflxCatalog.inspect(SflxRegistry.MORNYE_STORE).onSuccess { offer = it }
                        }
                    },
                ) { Text("Browse the Mornye store") }

                when (val current = offer) {
                    null -> Unit
                    is SflxCatalog.InstallOffer.Package -> {
                        Text(
                            "${current.manifest.displayName} — v${current.manifest.version}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        current.manufacturerLine()?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                        current.manifest.minAppVersion.takeIf { it.isNotBlank() }?.let {
                            Text(
                                "Built for Mornye $it or newer.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (current.manifest.permissions.networkHosts.isNotEmpty()) {
                            Text(
                                "It may reach: " + current.manifest.permissions.networkHosts.joinToString(", "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(
                            onClick = {
                                SflxCatalog.install(current)
                                offer = null
                            },
                        ) { Text("Install ${current.manifest.displayName}") }
                    }
                    is SflxCatalog.InstallOffer.Store -> {
                        Text("Extensions in that store", style = MaterialTheme.typography.titleSmall)
                        current.entries.forEach { entry ->
                            StoreEntryRow(
                                entry = entry,
                                onInstall = {
                                    scope.launch {
                                        SflxCatalog.install(entry).onSuccess { onInstalled() }
                                    }
                                },
                            )
                        }
                    }
                }

                (state as? SflxCatalog.InstallState.Failed)?.let { failed ->
                    Text(failed.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (state is SflxCatalog.InstallState.Working) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            (state as? SflxCatalog.InstallState.Working)?.message ?: "Working…",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = link.isNotBlank() && !fetching && offer == null,
                onClick = { scope.launch { SflxCatalog.inspect(link).onSuccess { offer = it } } },
            ) { Text("Fetch") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun StoreEntryRow(entry: SflxRegistryEntry, onInstall: () -> Unit) {
    val installed = remember(entry.id) { SflxCatalog.installedOf(entry.id) }
    val update = installed != null && SflxRegistry.isUpdate(entry, installed)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    buildString {
                        append(entry.displayName)
                        append(' ')
                        append(entry.version)
                        // A newer version than the one on disk is worth saying
                        // out loud: the button is not "install", it is
                        // "update", and the listener should know which one they
                        // are about to do.
                        if (update) append("  ·  update")
                        else if (installed != null) append("  ·  installed")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (entry.description.isNotBlank()) {
                    Text(
                        entry.description,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                    )
                }
                SflxRegistry.hostNote(entry)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = onInstall) { Text(if (update) "Update" else "Install") }
        }
    }
}

/** The publisher's own line about a package, when it wrote one. */
private fun SflxCatalog.InstallOffer.Package.manufacturerLine(): String? =
    listOfNotNull(
        manifest.homepage.takeIf { it.isNotBlank() },
        manifest.description.takeIf { it.isNotBlank() },
    ).firstOrNull()

/**
 * An extension's own settings, as its manifest asked for them to be drawn.
 *
 * Rendered from the manifest rather than hand-built per extension, because the
 * settings belong to the extension: it declared the key, the label, the type and
 * whether the value is a secret, and the host's only job is to ask. A value
 * marked `secret` is drawn masked and stored under [SflxCatalog]'s credentials
 * namespace rather than beside ordinary preferences.
 */
@Composable
fun SflxSettingsDialog(
    installed: InstalledSflx,
    onDismiss: () -> Unit,
) {
    val settings = installed.manifest.settings
    val stored by remember { mutableStateOf(SflxCatalog.settingsOf(installed.id)) }
    val values = remember {
        mutableStateMapOf<String, String>().apply {
            settings.forEach { setting ->
                put(setting.key, stored.stringValue(setting.key) ?: setting.defaultString())
            }
        }
    }
    val statuses by SflxCatalog.statuses.collectAsStateWithLifecycle()
    val status = statuses[installed.id]
    val scope = rememberCoroutineScope()
    var verifying by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(installed.manifest.displayName) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "v${installed.manifest.version}" +
                        if (installed.manifest.homepage.isNotBlank()) " — ${installed.manifest.homepage}" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (settings.isEmpty()) {
                    Text("This extension has no settings of its own.", style = MaterialTheme.typography.bodyMedium)
                }
                // The signed session, when the extension has one. It sits above
                // the settings because it is the thing that decides whether any
                // of them matter: an unverified extension answers nothing.
                if (status != null) {
                    SessionStatusBlock(
                        status = status,
                        verifying = verifying,
                        onVerify = {
                            verifying = true
                            scope.launch {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    SflxCatalog.beginVerification(installed.id)
                                }
                                verifying = false
                            }
                        },
                        onForget = {
                            SflxCatalog.clearSession(installed.id)
                        },
                    )
                }
                settings.forEach { setting ->
                    SettingField(setting = setting, value = values[setting.key] ?: "") { values[setting.key] = it }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val next = JsonObject(
                        values.filterValues { it.isNotBlank() }.mapValues { (_, value) ->
                            kotlinx.serialization.json.JsonPrimitive(value)
                        },
                    )
                    SflxCatalog.saveSettings(installed.id, next)
                    onDismiss()
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                // The way out. An extension is the listener's own install, so
                // removing it belongs on its own sheet and not only in the
                // source list's editor.
                TextButton(
                    onClick = {
                        // Dismissed first: the sheet describes a package that is
                        // about to stop existing, and removal deletes files and
                        // retires a JavaScript engine. Doing that on the main
                        // thread is why uninstalling looked like a freeze.
                        onDismiss()
                        scope.launch {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                SflxCatalog.remove(installed.id)
                            }
                        }
                    },
                ) {
                    Text("Uninstall", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/**
 * The extension's signed session, drawn as a state and one button.
 *
 * Three things a listener can be in: verified (nothing to do), not verified or
 * needing verification (a page to visit), and a gateway that could not be
 * reached (nothing to do but try again). The button says which of them applies
 * and does the one thing that changes it.
 */
@Composable
private fun SessionStatusBlock(
    status: SflxSignedSession.Snapshot,
    verifying: Boolean,
    onVerify: () -> Unit,
    onForget: () -> Unit,
) {
    val expiry = SflxSignedSession.parseTime(status.expiresAt)
        ?.let {
            java.text.DateFormat
                .getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                .format(java.util.Date(it))
        }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Sign-in", style = MaterialTheme.typography.titleSmall)
        Text(
            when {
                status.authenticated && expiry != null -> "Verified, until $expiry."
                status.authenticated -> "Verified."
                status.error.isNotBlank() -> "The sign-in page could not be opened: ${status.error}"
                else -> "This addon wants a session with its service before it serves anything. Verify opens that page."
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(enabled = !verifying, onClick = onVerify) {
                if (verifying) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(16.dp).height(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (status.authenticated) "Verify again" else "Verify")
            }
            if (status.authenticated) {
                TextButton(onClick = onForget) { Text("Forget") }
            }
        }
    }
}

@Composable
private fun SettingField(setting: SflxSetting, value: String, onChange: (String) -> Unit) {    // A declared boolean is a switch rather than a text field: the manifest said
    // what it is, and asking somebody to type "true" is how a host turns a
    // setting into a puzzle.
    if (setting.type.equals("bool", ignoreCase = true) || setting.type.equals("boolean", ignoreCase = true)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(setting.label.ifBlank { setting.key }, style = MaterialTheme.typography.bodyMedium)
                if (setting.description.isNotBlank()) {
                    Text(setting.description, style = MaterialTheme.typography.bodySmall)
                }
            }
            Switch(
                checked = value.equals("true", ignoreCase = true),
                onCheckedChange = { onChange(if (it) "true" else "false") },
            )
        }
        return
    }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(setting.label.ifBlank { setting.key }) },
        supportingText = setting.description.takeIf { it.isNotBlank() }?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = if (setting.secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (setting.secret) KeyboardType.Password else KeyboardType.Text,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun JsonObject.stringValue(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

private fun SflxSetting.defaultString(): String =
    (default as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
