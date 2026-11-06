package com.music.bitchord.ui.components

import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sing.SingModelManager
import com.music.bitchord.data.sing.SingModels
import com.music.bitchord.playback.SingMethod
import com.music.bitchord.playback.StudioDiagnostics
import com.music.bitchord.playback.StudioQuality
import com.music.bitchord.playback.outputRate
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlin.math.roundToInt

/**
 * The popup that asks for a model.
 *
 * It is the same shape as the app-update notice — a frosted card with the
 * message and the action under it — because it is the same kind of news:
 * something the app can do once a file has been fetched, and nothing it can
 * do until then.
 *
 * Studio is not part of the app: the model is thirty megabytes that most
 * listeners will never want, so it is fetched when somebody asks for it.
 * Once it is there, Studio runs.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun StudioModelDialog(
    hazeState: HazeState,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Whether the card can blur what is behind it.
     *
     * False inside a dialog: the blur samples a texture out of the window
     * the app is drawn in, and a dialog is a window of its own, so there is
     * nothing there to sample. The card is solid there instead.
     */
    blurred: Boolean = true,
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val states by SingModelManager.states.collectAsStateWithLifecycle()
    val selectedId by SingModelManager.selected.collectAsStateWithLifecycle()
    val model = SingModels.byId(selectedId) ?: SingModels.default
    val state = states[model.id] ?: SingModelManager.State.Absent
    val shape = RoundedCornerShape(ALERT_CORNER)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SCRIM_COLOR)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(ALERT_WIDTH)
                .clip(shape)
                .then(
                    if (!blurred || reduceDynamicBlur) {
                        Modifier.background(MaterialTheme.colorScheme.surface)
                    } else {
                        Modifier.optimizedHazeEffect(
                            state = hazeState,
                            style = HazeMaterials.regular(MaterialTheme.colorScheme.surface),
                        )
                    },
                )
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 19.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.studio_download_title),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.W600,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = when (state) {
                        is SingModelManager.State.Ready -> stringResource(R.string.studio_download_ready)
                        is SingModelManager.State.Failed -> stringResource(R.string.studio_download_failed)
                        is SingModelManager.State.Preparing -> stringResource(R.string.studio_model_state_preparing)
                        else -> stringResource(R.string.studio_download_body)
                    },
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )

                val downloading = state as? SingModelManager.State.Downloading
                if (downloading != null || state is SingModelManager.State.Failed) {
                    Box(
                        Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth()
                            .height(STUDIO_DOWNLOAD_ROW_HEIGHT)
                            .clip(RoundedCornerShape(STUDIO_DOWNLOAD_ROW_HEIGHT / 2))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)),
                    ) {
                        if (downloading != null && downloading.fraction > 0f) {
                            Box(
                                Modifier
                                    .fillMaxWidth(downloading.fraction)
                                    .height(STUDIO_DOWNLOAD_ROW_HEIGHT)
                                    .clip(RoundedCornerShape(STUDIO_DOWNLOAD_ROW_HEIGHT / 2))
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                        }
                    }
                }
                if (state is SingModelManager.State.Failed) {
                    Text(
                        text = state.message,
                        modifier = Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            AlertRule()
            when (state) {
                is SingModelManager.State.Downloading -> {
                    AlertAction(
                        label = stringResource(R.string.cancel),
                        emphasised = false,
                        onClick = { SingModelManager.cancel(model) },
                    )
                }
                is SingModelManager.State.Ready -> {
                    AlertAction(
                        label = stringResource(R.string.sing_method_studio),
                        emphasised = true,
                        onClick = {
                            AppSettings.setSingMethod(SingMethod.NEURAL)
                            onDismiss()
                        },
                    )
                    AlertRule()
                    AlertAction(
                        label = stringResource(R.string.studio_open_settings),
                        emphasised = false,
                        onClick = onOpenSettings,
                    )
                }
                is SingModelManager.State.Failed -> {
                    AlertAction(
                        label = stringResource(R.string.studio_model_download),
                        emphasised = true,
                        onClick = { SingModelManager.download(model) },
                    )
                    AlertRule()
                    AlertAction(
                        label = stringResource(R.string.studio_open_settings),
                        emphasised = false,
                        onClick = onOpenSettings,
                    )
                }
                is SingModelManager.State.Preparing -> {
                    AlertAction(
                        label = stringResource(R.string.studio_download_not_now),
                        emphasised = false,
                        onClick = onDismiss,
                    )
                }
                else -> {
                    AlertAction(
                        label = stringResource(R.string.studio_download_action),
                        emphasised = true,
                        onClick = { SingModelManager.download(model) },
                    )
                    AlertRule()
                    AlertAction(
                        label = stringResource(R.string.studio_open_settings),
                        emphasised = false,
                        onClick = onOpenSettings,
                    )
                }
            }
        }
    }
}

/** The height of the download's progress row, the same weight as the update notice's. */
private val STUDIO_DOWNLOAD_ROW_HEIGHT = 4.dp

/**
 * The popup, in a window of its own.
 *
 * The player is a modal bottom sheet, which is a window of its own: a sheet
 * is drawn over the activity, not inside it, and anything the activity
 * draws stays underneath it however the two are ordered in the composition.
 * A popup that has to float above the player therefore has to be a window
 * too — which is what this is.
 *
 * The cost of a window is the blur: the frosted card samples a texture out
 * of the app's window, and there is no such texture in a dialog's window.
 * So the card here is solid rather than frosted, which is the one thing
 * about it that differs from the update notice it was modelled on.
 */
@Composable
fun StudioModelPopup(
    hazeState: HazeState,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            // The card is its own width, not the platform's dialog width.
            usePlatformDefaultWidth = false,
            // The scrim behind the card is the way out of this popup, so
            // the platform must not also take a tap on it as one.
            dismissOnClickOutside = false,
        ),
    ) {
        // The platform dims the screen behind a dialog. This popup has a
        // scrim of its own — the same one the update notice uses — and two
        // of them make the card sit on a darker stage than it was drawn
        // for, so the platform's is turned off.
        val view = LocalView.current
        LaunchedEffect(view) {
            dialogWindowOf(view)?.let { window ->
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.setDimAmount(0f)
            }
        }
        StudioModelDialog(
            hazeState = hazeState,
            onDismiss = onDismiss,
            onOpenSettings = onOpenSettings,
            blurred = false,
        )
    }
}

/**
 * The window this content is drawn in, if it is a dialog's.
 *
 * A dialog's content lives in a view whose parent is not always the window
 * provider itself, so the parent chain is walked. There is no context-chain
 * fallback: the platform's dialog is not a context wrapper, it only holds
 * one, so looking for it there finds nothing.
 */
private fun dialogWindowOf(view: View): Window? {
    var parent = view.parent
    while (parent != null) {
        if (parent is DialogWindowProvider) return parent.window
        parent = parent.parent
    }
    return null
}

/**
 * The list of models, and everything one can do with them.
 *
 * Downloading, deleting and choosing are all here rather than spread across
 * the app: a model is a file on the phone, and the file's whole life is in
 * this one screen. The rate the song is played at lives here too, because
 * it is the same decision — how Studio runs — and it belongs where the
 * model that does the running is chosen.
 */
@Composable
fun StudioModelsList(
    modifier: Modifier = Modifier,
) {
    val states by SingModelManager.states.collectAsStateWithLifecycle()
    val selectedId by SingModelManager.selected.collectAsStateWithLifecycle()
    val quality by AppSettings.studioQuality.collectAsStateWithLifecycle()
    val diagnostics by StudioDiagnostics.current.collectAsStateWithLifecycle()
    val nerdStats by NerdStats.current.collectAsStateWithLifecycle()

    // The rate of the track that is playing: what the decoder actually
    // produced when Studio is separating, and what the stream says when it
    // is not. The first is the honest one — it is the rate the audio
    // arrived at, not the rate the file claims on its sleeve.
    val trackRateHz = diagnostics.inputRateHz.takeIf { it > 0 }
        ?: nerdStats?.sampleRateHz?.takeIf { it > 0 }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            text = stringResource(R.string.studio_models_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        StudioQualityCard(
            quality = quality,
            diagnostics = diagnostics,
            trackRateHz = trackRateHz,
            onQuality = { AppSettings.setStudioQuality(it) },
        )
        Spacer(Modifier.height(12.dp))
        StudioLogCard()
        Spacer(Modifier.height(12.dp))
        SingModels.catalogue.forEach { model ->
            StudioModelRow(
                model = model,
                state = states[model.id] ?: SingModelManager.State.Absent,
                selected = model.id == selectedId,
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

/**
 * The rate the song is played at, and what the separator is doing.
 *
 * The rate is a choice: the model reads 44.1 kHz, and a song can be played
 * there or put back at the rate it was made at. The line under the choice
 * says which of the two is happening to the song that is playing now, so
 * the setting is never a guess.
 *
 * The numbers under that are the separator's own: how long a window takes,
 * how many have been separated, and how much audio had to be thrown away.
 * The last one is the only number that can say the phone is not keeping up
 * with the song, and it is the reason this card is here at all.
 */
@Composable
private fun StudioQualityCard(
    quality: StudioQuality,
    diagnostics: StudioDiagnostics.Snapshot,
    trackRateHz: Int?,
    onQuality: (StudioQuality) -> Unit,
) {
    val outputRateHz = when {
        diagnostics.running && diagnostics.outputRateHz > 0 -> diagnostics.outputRateHz
        else -> quality.outputRate(trackRateHz ?: StudioQuality.MODEL_RATE_HZ)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.studio_quality_title),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        StudioQualityOption(
            selected = quality == StudioQuality.MODEL_RATE,
            title = stringResource(R.string.studio_quality_model),
            body = stringResource(R.string.studio_quality_model_body),
            onClick = { onQuality(StudioQuality.MODEL_RATE) },
        )
        StudioQualityOption(
            selected = quality == StudioQuality.MATCH_TRACK,
            title = stringResource(R.string.studio_quality_track),
            body = stringResource(R.string.studio_quality_track_body),
            onClick = { onQuality(StudioQuality.MATCH_TRACK) },
        )

        Spacer(Modifier.height(8.dp))
        Text(
            text = if (trackRateHz != null) {
                stringResource(
                    R.string.studio_quality_live,
                    rateLabel(trackRateHz),
                    rateLabel(outputRateHz),
                )
            } else {
                stringResource(R.string.studio_quality_live_unknown)
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.studio_diagnostics_title),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (diagnostics.running) {
                stringResource(
                    R.string.studio_diagnostics_detail,
                    diagnostics.windowMs,
                    diagnostics.windowsSeparated,
                )
            } else {
                stringResource(R.string.studio_diagnostics_idle)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.studio_diagnostics_model, diagnostics.modelState),
            style = MaterialTheme.typography.bodySmall,
            color = if (diagnostics.modelState == "open") {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        if (diagnostics.windowsFailed > 0) {
            Text(
                text = stringResource(R.string.studio_diagnostics_failed, diagnostics.windowsFailed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        diagnostics.lastError?.let { why ->
            Text(
                text = stringResource(R.string.studio_diagnostics_error, why),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = if (diagnostics.droppedFrames > 0) {
                stringResource(R.string.studio_diagnostics_dropped, diagnostics.droppedFrames)
            } else {
                stringResource(R.string.studio_diagnostics_keeping_up)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (diagnostics.droppedFrames > 0) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * Studio's own log, on the screen where the settings live.
 *
 * A release build has no logcat — the lines are stripped out of it — so
 * the lines that would have been printed are kept here instead, where they
 * can be read and copied. This is the logcat for this build of the app:
 * the same information, in the same shape, on the phone.
 */
@Composable
private fun StudioLogCard(modifier: Modifier = Modifier) {
    val lines by StudioDiagnostics.log.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.studio_log_title),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(lines.joinToString("\n")))
                },
                enabled = lines.isNotEmpty(),
            ) {
                Text(stringResource(R.string.studio_log_copy))
            }
        }
        Spacer(Modifier.height(4.dp))
        if (lines.isEmpty()) {
            Text(
                text = stringResource(R.string.studio_log_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                lines.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** One of the two rates, with what it means written under it. */
@Composable
private fun StudioQualityOption(
    selected: Boolean,
    title: String,
    body: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Icon(
            imageVector = if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(24.dp),
        )
    }
}

/** A sample rate, written the way a person would say it. */
private fun rateLabel(hz: Int): String =
    if (hz % 1000 == 0) {
        "${hz / 1000} kHz"
    } else {
        String.format(java.util.Locale.ROOT, "%.1f kHz", hz / 1000.0)
    }

/** One model: what it does, whether it is here, and what can be done about it. */
@Composable
private fun StudioModelRow(
    model: SingModels.Model,
    state: SingModelManager.State,
    selected: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = model.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = stringResource(R.string.studio_model_in_use),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = model.summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = model.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = when (state) {
                is SingModelManager.State.Ready -> stringResource(R.string.studio_model_state_ready)
                is SingModelManager.State.Downloading ->
                    stringResource(R.string.studio_model_downloading, (state.fraction * 100).roundToInt())
                is SingModelManager.State.Preparing -> stringResource(R.string.studio_model_state_preparing)
                is SingModelManager.State.Failed -> stringResource(R.string.studio_model_state_failed)
                else -> "${stringResource(R.string.studio_model_state_absent)} · ${model.sizeLabel}"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            when (state) {
                is SingModelManager.State.Ready -> {
                    if (!selected) {
                        TextButton(onClick = { SingModelManager.select(model) }) {
                            Text(stringResource(R.string.studio_model_use))
                        }
                    }
                    TextButton(onClick = { SingModelManager.delete(model) }) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.studio_model_delete))
                    }
                }
                is SingModelManager.State.Downloading -> {
                    TextButton(onClick = { SingModelManager.cancel(model) }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
                else -> {
                    TextButton(onClick = { SingModelManager.download(model) }) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.studio_model_download))
                    }
                }
            }
        }
    }
}
