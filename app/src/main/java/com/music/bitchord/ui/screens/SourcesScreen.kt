package com.music.bitchord.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Where the app is allowed to get audio from.
 *
 * The order is fixed rather than something to argue with: a module source,
 * when one is configured, is tried first — it's the one the user pointed at
 * on purpose — and YouTube Music is tried second, since it needs no setup and
 * has the full catalogue behind it. Nothing on this screen downloads code,
 * and nothing on it can teach the app a new way to behave after it has
 * shipped — a module supplies audio, not instructions.
 */
@Composable
fun SourcesScreen(
    contentPadding: PaddingValues,
    /**
     * Asks the activity to put the custom-module alert up. Raised rather than
     * shown here so its scrim covers the tab bar and the mini player, the same
     * way every other alert in the app is hosted — see [DiscordDialogHost].
     *
     * Unused for now: the row that called it is temporarily hidden, see the
     * source list below. Kept so bringing it back is a one-line change.
     */
    onEditCustomModule: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val configs by SourceRegistry.configs.collectAsStateWithLifecycle()
    val wifiQuality by AppSettings.audioQualityWifi.collectAsStateWithLifecycle()
    val cellularQuality by AppSettings.audioQualityCellular.collectAsStateWithLifecycle()
    val metered by AppSettings.meteredConnection.collectAsStateWithLifecycle()

    /** Last known reachability per source, filled in as the probes come back. */
    val health = remember { mutableStateMapOf<String, SourceHealth>() }
    var editing by remember { mutableStateOf<SourceConfig?>(null) }
    val scope = rememberCoroutineScope()

    // Only still singled out because it is the one kind that can be *added* —
    // every other use of it below now goes through the list as a whole.
    val module = configs.firstOrNull { it.kind == SourceKind.MODULE }

    // Every source that has a server to reach is probed, not just the built-in
    // module, so a custom index gets the same reachability line — which is the
    // only feedback that a URL just typed in was any good.
    val probeKey = configs.filter { it.kind.needsServer }.joinToString { "${it.id}@${it.baseUrl}" }
    LaunchedEffect(probeKey) {
        configs.filter { it.kind.needsServer && it.isComplete }.forEach { config ->
            val source = SourceRegistry.instance(config.id) ?: return@forEach
            health[config.id] = withContext(Dispatchers.IO) {
                runCatching { source.health() }
                    .getOrElse { SourceHealth.Unreachable(it.message ?: "Failed") }
            }
        }
    }

    // The ceiling in force *right now*, which is the only one this screen can
    // speak for: the rows below are switches the user set once, and whether a
    // given source is reached also depends on which connection is up. Reading
    // it here rather than baking it into the switches is deliberate — the
    // preset write that used to do that is what made a mobile-data rung follow
    // the user onto Wi-Fi. See [AudioQuality.permits].
    val ceiling = if (metered == true) cellularQuality else wifiQuality

    /** Whether the ceiling in force right now would cap a lossless stream anyway. */
    val cappedByQuality = ceiling != AudioQuality.LOSSLESS
    // Asked of the kinds themselves rather than of the module specifically, so
    // a source added later answers this on its own terms instead of being
    // invisible to it.
    val anyLosslessSource = configs.any { it.enabled && it.isComplete && it.kind.canServeLossless }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
    ) {
        Text(
            text = stringResource(R.string.source),
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
        )

        SettingsGroup(
            header = stringResource(R.string.sources_order_header),
            footer = buildString {
                append(stringResource(R.string.sources_order_footer))
                if (cappedByQuality) {
                    append("\n\n")
                    append(stringResource(R.string.sources_quality_capped))
                } else if (!anyLosslessSource) {
                    append("\n\n")
                    append(stringResource(R.string.sources_no_lossless))
                }
            },
        ) {
            // One loop over the configured sources in the order they are
            // actually tried, rather than a row per kind with its position
            // worked out by hand. Every source is listed, numbered, probed and
            // toggled on identical terms; a kind added later appears here
            // without this block having to learn about it.
            // TEMPORARY: the custom module is hidden from this screen — only the
            // three built-in sources are listed. An already-configured custom
            // module is left alone in the registry and still resolves; this
            // hides the row and the add/replace entry, nothing more. Drop the
            // `filterNot` and restore the add row below to bring it back.
            val ordered = configs
                .filterNot { it.kind == SourceKind.CUSTOM_MODULE }
                .sortedBy { it.kind.ordinal }
            ordered.forEachIndexed { index, config ->
                if (index > 0) RowDivider()
                SourceRow(
                    position = index + 1,
                    config = config,
                    health = health[config.id],
                    // Switched on, but not reached on this connection. Said on
                    // the row rather than by moving the switch, so the switch
                    // keeps meaning "I want this source" and the connection
                    // keeps meaning "…and here is what it costs today".
                    skippedByQuality = config.enabled && !ceiling.permits(config.kind),
                    onMetered = metered == true,
                    ceiling = ceiling,
                    // Only the custom module is editable here. The built-in
                    // module's URL is baked in from a build secret — see
                    // [BuildConfig.MODULE_INDEX_URL] via [SourceRegistry.init] —
                    // and is never shown or renamed on screen.
                    onClick = if (config.kind == SourceKind.CUSTOM_MODULE) ({ editing = config }) else null,
                    // YouTube gets no switch at all — see
                    // [SourceRegistry.setEnabled] for why one would be a lie.
                    onToggle = if (config.kind == SourceKind.YOUTUBE) {
                        null
                    } else {
                        ({ SourceRegistry.setEnabled(config.id, it) })
                    },
                )
            }
        }

        Spacer(Modifier.height(32.dp))
    }

    editing?.let { config ->
        ServerEditorDialog(
            config = config,
            onDismiss = { editing = null },
            onSave = { saved ->
                if (SourceRegistry.config(saved.id) == null) {
                    SourceRegistry.add(saved)
                } else {
                    SourceRegistry.update(saved)
                }
                editing = null
            },
            onDelete = {
                SourceRegistry.remove(config.id)
                health.remove(config.id)
                editing = null
            },
            probe = { candidate ->
                // Probed through a throwaway instance rather than the stored
                // one: the point of Test is to check what has been *typed*,
                // which is not yet what is saved, and testing the saved copy
                // would cheerfully report success for the old URL.
                withContext(Dispatchers.IO) {
                    runCatching { SourceRegistry.probeCandidate(candidate) }
                        .getOrElse { SourceHealth.Unreachable(it.message ?: "Failed") }
                }
            },
            scope = scope,
        )
    }

}

@Composable
private fun SourceRow(
    position: Int,
    config: SourceConfig,
    health: SourceHealth?,
    onClick: (() -> Unit)?,
    /** Null for a source that cannot be switched off, which gets a label instead. */
    onToggle: ((Boolean) -> Unit)?,
    /** On, but skipped by the ceiling the current connection is set to. */
    skippedByQuality: Boolean = false,
    /** Which of the two ceilings [ceiling] is, so the row can name it. */
    onMetered: Boolean = false,
    ceiling: AudioQuality = AudioQuality.LOSSLESS,
) {
    // Dimmed for the same reason an off source is: it is not in the walk. The
    // switch stays where the user left it, so the row reads "on, but not
    // today" rather than "off".
    val dimmed = !config.enabled || skippedByQuality
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .heightIn(min = 60.dp)
            .padding(horizontal = ROW_INSET, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$position",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .width(18.dp)
                .alpha(if (dimmed) 0.4f else 1f),
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            imageVector = when (config.kind) {
                SourceKind.CUSTOM_MODULE -> Icons.Rounded.Extension
                SourceKind.MODULE -> Icons.Rounded.Extension
                SourceKind.JIOSAAVN -> Icons.Rounded.GraphicEq // or some other icon
                SourceKind.YOUTUBE -> Icons.Rounded.PlayCircle
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .size(ICON_SIZE)
                .alpha(if (dimmed) 0.4f else 1f),
        )
        Spacer(Modifier.width(ICON_GAP))
        Column(
            Modifier
                .weight(1f)
                .alpha(if (dimmed) 0.4f else 1f),
        ) {
            Text(
                text = config.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // The ceiling outranks the health line: a source that isn't
                // going to be asked at all is not usefully described by
                // whether its server answered a probe.
                text = if (skippedByQuality) {
                    stringResource(
                        R.string.source_skipped_by_quality,
                        stringResource(if (onMetered) R.string.mobile_data else R.string.wifi),
                        ceiling.localizedLabel(),
                    )
                } else {
                    config.statusLine(health)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    // Only a rejection is coloured, and only when it is what
                    // the line actually says. A server that is merely down
                    // will be up again without anyone doing anything, and
                    // painting that red trains people to ignore the colour by
                    // the time it means something.
                    skippedByQuality -> MaterialTheme.colorScheme.onSurfaceVariant
                    health is SourceHealth.Rejected -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (onToggle == null) {
            Text(
                text = stringResource(R.string.always_on),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Switch(
                checked = config.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
    }
}

@Composable
private fun AudioQuality.localizedLabel(): String = stringResource(
    when (this) {
        AudioQuality.LOW -> R.string.low
        AudioQuality.MEDIUM -> R.string.medium
        AudioQuality.HIGH -> R.string.high
        AudioQuality.LOSSLESS -> R.string.lossless
    },
)

/** The second line of a row: what this source is, or what is wrong with it. */
@Composable
private fun SourceConfig.statusLine(health: SourceHealth?): String = when {
    !isComplete -> stringResource(R.string.source_setup_required)
    health is SourceHealth.Ok -> listOfNotNull(
        health.detail,
        kind.labels.take(3).joinToString(" · "),
    ).joinToString(" · ")
    health is SourceHealth.Rejected -> health.reason
    health is SourceHealth.Unreachable -> stringResource(R.string.source_unreachable, health.reason)
    kind.needsServer -> stringResource(R.string.checking)
    else -> kind.labels.take(3).joinToString(" · ")
}

/**
 * Add or edit the module source.
 *
 * Test is offered rather than required: an index that happens to be asleep is
 * still worth saving, and refusing to store it until it answers would make
 * setting one up from a coffee shop impossible.
 */
@Composable
private fun ServerEditorDialog(
    config: SourceConfig,
    onDismiss: () -> Unit,
    onSave: (SourceConfig) -> Unit,
    onDelete: () -> Unit,
    probe: suspend (SourceConfig) -> SourceHealth,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val isNew = SourceRegistry.config(config.id) == null
    var label by remember { mutableStateOf(config.label) }
    var baseUrl by remember { mutableStateOf(config.baseUrl) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SourceHealth?>(null) }

    val candidate = config.copy(label = label.trim(), baseUrl = baseUrl.trim())

    AlertDialog(
        onDismissRequest = { if (!testing) onDismiss() },
        title = {
            Text(
                if (isNew) stringResource(R.string.add_named_source, config.kind.label.lowercase(Locale.ROOT))
                else config.displayName,
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it; result = null },
                    label = { Text(stringResource(R.string.link)) },
                    placeholder = { Text("https://example.com/modules/index.json") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.module_index_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.name_optional)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                result?.let { health ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = when (health) {
                            is SourceHealth.Ok ->
                                listOfNotNull(stringResource(R.string.connected), health.detail).joinToString(" · ")
                            is SourceHealth.Rejected -> health.reason
                            is SourceHealth.Unreachable -> health.reason
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (health.isOk) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }

                if (!isNew) {
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = onDelete, modifier = Modifier.align(Alignment.Start)) {
                        Text(stringResource(R.string.remove_source), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        testing = true
                        result = null
                        scope.launch {
                            result = probe(candidate)
                            testing = false
                        }
                    },
                    enabled = !testing && candidate.isComplete,
                ) {
                    Text(stringResource(if (testing) R.string.testing else R.string.test))
                }
                TextButton(
                    onClick = { onSave(candidate) },
                    enabled = !testing && candidate.isComplete,
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !testing) { Text(stringResource(R.string.cancel)) }
        },
    )
}
