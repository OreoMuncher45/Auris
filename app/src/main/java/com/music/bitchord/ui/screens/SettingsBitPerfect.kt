package com.music.bitchord.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.components.menuOriginOf
import com.music.bitchord.ui.components.rememberMenuButtonOrigin
import com.music.bitchord.data.settings.BitPerfectPath
import com.music.bitchord.ui.components.AurisSlider
import java.util.Locale

/**
 * Wraps a row that bit-perfect output bypasses, fading it while bit-perfect is
 * ready. The fade is drawn, not laid out: the animated alpha is read only
 * inside [graphicsLayer], so the 250 ms fade recomposes nothing.
 */
@Composable
internal fun BypassedByBitPerfect(bypassed: Boolean, content: @Composable () -> Unit) {
    val fade = animateFloatAsState(if (bypassed) BYPASSED_ALPHA else 1f, label = "bypassed")
    Box(Modifier.graphicsLayer { alpha = fade.value }) { content() }
}

private const val BYPASSED_ALPHA = 0.38f

/** An indented row with a value and a chevron-free tap target, matching [SettingsSubRow]'s inset. */
@Composable
internal fun SettingsValueSubRow(
    title: String,
    value: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    // Where the picker it opens hangs from: kept by reference, read on the tap.
    val origin = rememberMenuButtonOrigin()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .menuOriginOf(origin)
            .clickable {
                origin.record()
                onClick()
            }
            .padding(start = ROW_INSET, end = ROW_INSET, top = 0.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The DAC's own volume while it carries the listener's level. The slider moves
 * the same level the volume keys and the player's bar move; the session sends
 * only the latest value to the DAC, one control transfer at a time.
 */
@Composable
internal fun DacVolumeSubRow(level: Float, outputDb: Double?, onLevel: (Float) -> Unit) {
    Column(Modifier.padding(start = ROW_INSET, end = ROW_INSET, top = 0.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.dac_volume),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            if (outputDb != null) {
                Text(
                    text = String.format(Locale.ROOT, "%.1f dB", outputDb),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AurisSlider(value = level.coerceIn(0f, 1f), onValueChange = onLevel)
        Text(
            text = stringResource(R.string.dac_volume_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun BitPerfectPath.localizedLabel(): String = stringResource(
    when (this) {
        BitPerfectPath.AUTO -> R.string.bit_perfect_path_auto
        BitPerfectPath.BUILT_IN_DAC -> R.string.bit_perfect_path_built_in
    },
)

@Composable
internal fun BitPerfectPath.localizedSubtitle(): String = stringResource(
    when (this) {
        BitPerfectPath.AUTO -> R.string.bit_perfect_path_auto_subtitle
        BitPerfectPath.BUILT_IN_DAC -> R.string.bit_perfect_path_built_in_subtitle
    },
)

/**
 * The bit-perfect path picker, opened on its row in the app's anchored menu:
 * the two paths, with what each one does.
 */
@Composable
internal fun ColumnScope.BitPerfectPathMenu(close: () -> Unit) {
    val selected by AppSettings.bitPerfectPath.collectAsStateWithLifecycle()
    SettingsChoiceMenu(
        title = stringResource(R.string.bit_perfect_path),
        subtitle = stringResource(R.string.bit_perfect_path_sheet_subtitle),
        choices = BitPerfectPath.entries.map { path ->
            SettingsChoice(
                label = path.localizedLabel(),
                detail = path.localizedSubtitle(),
                selected = path == selected,
            ) { AppSettings.setBitPerfectPath(path) }
        },
        close = close,
    )
}
