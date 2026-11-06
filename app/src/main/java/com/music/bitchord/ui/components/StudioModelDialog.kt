package com.music.bitchord.ui.components

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sing.SingModelManager
import com.music.bitchord.data.sing.SingModels
import com.music.bitchord.playback.SingMethod
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
                    if (reduceDynamicBlur) {
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
                        text = (state as SingModelManager.State.Failed).message,
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
 * The list of models, and everything one can do with them.
 *
 * Downloading, deleting and choosing are all here rather than spread across
 * the app: a model is a file on the phone, and the file's whole life is in
 * this one screen.
 */
@Composable
fun StudioModelsList(
    modifier: Modifier = Modifier,
) {
    val states by SingModelManager.states.collectAsStateWithLifecycle()
    val selectedId by SingModelManager.selected.collectAsStateWithLifecycle()

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
