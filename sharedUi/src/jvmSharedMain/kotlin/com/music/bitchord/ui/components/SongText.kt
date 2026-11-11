package com.music.bitchord.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.Song

fun Modifier.thumbnailBorder(shape: Shape): Modifier = composed {
    this.border(
        width = 1.dp,
        color = if (isSystemInDarkTheme()) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.15f),
        shape = shape
    )
}

/**
 * A song title with the catalogue-standard outlined E for explicit audio.
 *
 * It used to draw an outlined LOSSLESS chip beside the title on the lists that
 * asked for one. The chip is gone: it read as a claim on every row of a queue
 * rather than as the property of the copy that was playing, and the player's
 * own quality badge already says what is really being served.
 */
@Composable
fun ExplicitSongTitle(
    song: Song,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (song.isExplicit == true) {
            Text(
                text = "E",
                style = MaterialTheme.typography.labelSmall,
                color = color,
                modifier = Modifier
                    .border(1.dp, color.copy(alpha = 0.72f), RoundedCornerShape(2.dp))
                    .padding(horizontal = 3.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = song.title,
            style = style.forText(song.title),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (isRtlText(song.title)) TextAlign.End else TextAlign.Start,
            modifier = Modifier.weight(1f),
        )
    }
}
