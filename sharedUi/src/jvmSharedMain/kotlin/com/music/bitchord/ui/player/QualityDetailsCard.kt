package com.music.bitchord.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.NerdStats

/**
 * What the quality badge is actually telling you.
 *
 * "Lossless" is a claim; this is the measurement behind it: where the bytes
 * came from, how many of them there were, and what they are wrapped in. Three
 * lines, because three questions are being asked — is it really lossless,
 * how good is it, and what am I listening to.
 *
 * Shown over the player rather than in a dialog: the badge is the thing that
 * was tapped, and the answer belongs next to the question.
 */
@Composable
internal fun QualityDetailsCard(
    stats: NerdStats.Snapshot,
    modifier: Modifier = Modifier,
) {
    val ink = LocalPlayerAccent.current ?: LocalPlayerInk.current
    val alphas = LocalPlayerAlphas.current
    // The card grows out of the badge rather than appearing: the badge is the
    // thing that was pressed, and a card that flies in from nowhere reads as
    // an interruption.
    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(180),
        label = "qualityCardScale",
    )
    Column(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xE6101013))
            .padding(horizontal = 16.dp, vertical = 13.dp)
            .width(252.dp),
    ) {
        QualityRow(
            label = "Source",
            value = stats.sourceName?.takeIf { it.isNotBlank() } ?: "—",
            ink = ink,
            alphas = alphas,
        )
        Spacer(Modifier.height(7.dp))
        QualityRow(
            label = "Bitrate",
            value = stats.displayBitrateKbps()?.let { "$it kbps" } ?: "—",
            ink = ink,
            alphas = alphas,
        )
        Spacer(Modifier.height(7.dp))
        QualityRow(
            label = "File format",
            value = stats.fileFormatLabel(),
            ink = ink,
            alphas = alphas,
        )
        val depth = stats.bitDepth
        val rate = stats.sampleRateHz
        if (depth != null || rate != null) {
            Spacer(Modifier.height(7.dp))
            QualityRow(
                label = "Resolution",
                value = buildString {
                    if (depth != null) append("$depth-bit")
                    if (rate != null) {
                        if (isNotEmpty()) append(" · ")
                        append("${rate / 1000.0} kHz")
                    }
                },
                ink = ink,
                alphas = alphas,
            )
        }
    }
}

@Composable
private fun QualityRow(
    label: String,
    value: String,
    ink: Color,
    alphas: NowPlayingAlphas,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = ink.copy(alpha = alphas.tertiary),
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = ink.copy(alpha = alphas.primary),
        )
    }
}

/**
 * The container and the codec, as one string.
 *
 * The container is what the file is; the codec is what the audio inside it
 * is. "FLAC" alone says the first, and the second is what a listener comparing
 * a copy against a rip actually wants to know.
 */
private fun NerdStats.Snapshot.fileFormatLabel(): String {
    val container = container?.takeIf { it.isNotBlank() }
    val codec = NerdStats.codecLabel(mimeType)
        ?.takeIf { it.isNotBlank() && !it.equals(container, ignoreCase = true) }
    return when {
        container != null && codec != null -> "$container · $codec"
        container != null -> container
        codec != null -> codec
        else -> mimeType?.substringAfterLast('/')?.uppercase() ?: "—"
    }
}
