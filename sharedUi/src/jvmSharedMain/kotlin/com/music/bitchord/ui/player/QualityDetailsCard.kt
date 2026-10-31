package com.music.bitchord.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * came from, how many of them there were, and what they are wrapped in. Shown
 * over the player rather than in a dialog: the badge is the thing that was
 * tapped, and the answer belongs next to the question.
 *
 * Text is deliberately NOT the album-art ink: on a bright sleeve the ink is
 * unreadable, so the card uses fixed high-contrast colors on its own dark
 * surface and pops in from the badge with a spring.
 */
@Composable
internal fun QualityDetailsCard(
    stats: NerdStats.Snapshot,
    modifier: Modifier = Modifier,
) {
    // Pop in from the badge: start small, spring to full.
    val scale = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) {
        scale.animateTo(1f, spring(stiffness = Spring.StiffnessMediumLow))
    }
    val labelColor = Color.White.copy(alpha = 0.55f)
    val valueColor = Color.White
    Column(
        modifier = modifier
            .scale(scale.value)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xF01C1C20))
            .border(0.5.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 13.dp)
            .width(252.dp),
    ) {
        QualityRow("Source", stats.sourceName?.takeIf { it.isNotBlank() } ?: "—", labelColor, valueColor)
        Spacer(Modifier.height(7.dp))
        QualityRow("Bitrate", stats.displayBitrateKbps()?.let { "$it kbps" } ?: "—", labelColor, valueColor)
        Spacer(Modifier.height(7.dp))
        QualityRow("Codec", stats.fileFormatLabel(), labelColor, valueColor)
        val depth = stats.bitDepth
        val rate = stats.sampleRateHz
        if (depth != null || rate != null) {
            Spacer(Modifier.height(7.dp))
            QualityRow(
                "Resolution",
                buildString {
                    if (depth != null) append("$depth-bit")
                    if (rate != null) {
                        if (isNotEmpty()) append(" · ")
                        append("${rate / 1000.0} kHz")
                    }
                },
                labelColor,
                valueColor,
            )
        }
        stats.channels?.let { ch ->
            Spacer(Modifier.height(7.dp))
            QualityRow("Channels", if (ch == 1) "Mono" else if (ch == 2) "Stereo" else "$ch (Surround)", labelColor, valueColor)
        }
    }
}

@Composable
private fun QualityRow(
    label: String,
    value: String,
    labelColor: Color,
    valueColor: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = labelColor,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = valueColor,
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
