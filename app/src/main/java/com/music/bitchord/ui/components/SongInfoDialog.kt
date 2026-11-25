package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.music.bitchord.R
import com.music.bitchord.data.SongCredit
import com.music.bitchord.data.SongCreditsRepository
import com.music.bitchord.data.model.Song

/**
 * The body's height, fixed: the credits arrive after the dialog is up, and a
 * card that grew under the finger when they did would move its own Done
 * button. Room for the four standard rows and a few credits; more scroll.
 */
private val INFO_BODY_HEIGHT = 340.dp

/** Where the credits lookup has got to. */
private sealed interface CreditsLoad {
    data object Loading : CreditsLoad
    data object Failed : CreditsLoad
    data class Loaded(val credits: List<SongCredit>) : CreditsLoad
}

/**
 * A track's details and credits, drawn as the app's own alert card — centred
 * title, hairline [AlertRule]s, full-width [AlertAction]s.
 *
 * In a window [Dialog] because it opens from the track menu, which can hang in
 * the player's own window; an in-tree overlay would be drawn underneath it.
 * A separate window can't sample the app's haze, so the card takes the plain
 * surface the other alerts fall back to under "reduce dynamic blur".
 *
 * Nothing is fetched until it opens. The standard rows are the song's own;
 * the credits are YouTube Music's credits page, kept per track by
 * [SongCreditsRepository], so a second opening on the same song is instant.
 *
 * Ported from BitChord 5d3597f6c.
 */
@Composable
internal fun SongInfoDialog(song: Song, onDismiss: () -> Unit) {
    var retry by remember(song.videoId) { mutableIntStateOf(0) }
    val credits by produceState<CreditsLoad>(CreditsLoad.Loading, song.videoId, retry) {
        value = CreditsLoad.Loading
        value = SongCreditsRepository.load(song)?.let { CreditsLoad.Loaded(it) } ?: CreditsLoad.Failed
    }
    val onSurface = MaterialTheme.colorScheme.onSurface
    val unavailable = stringResource(R.string.song_info_unavailable)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(ALERT_WIDTH)
                .clip(RoundedCornerShape(ALERT_CORNER))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Text(
                text = stringResource(R.string.song_info),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 19.dp),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
                color = onSurface,
                textAlign = TextAlign.Center,
            )
            AlertRule()
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(INFO_BODY_HEIGHT)
                    .verticalScroll(rememberScrollState()),
            ) {
                SongInfoRow(stringResource(R.string.song_info_title), song.title)
                AlertRule(Modifier.padding(start = 16.dp))
                SongInfoRow(stringResource(R.string.song_info_artist), song.artist.ifBlank { unavailable })
                AlertRule(Modifier.padding(start = 16.dp))
                SongInfoRow(stringResource(R.string.song_info_album), song.albumName?.ifBlank { null } ?: unavailable)
                AlertRule(Modifier.padding(start = 16.dp))
                SongInfoRow(stringResource(R.string.song_info_duration), song.durationText?.ifBlank { null } ?: unavailable)

                Text(
                    text = stringResource(R.string.song_info_credits).uppercase(),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, letterSpacing = 0.4.sp),
                    color = onSurface.copy(alpha = 0.6f),
                )
                AlertRule()
                when (val load = credits) {
                    CreditsLoad.Loading -> Box(
                        Modifier.fillMaxWidth().heightIn(min = ACTION_HEIGHT),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = onSurface.copy(alpha = 0.5f),
                            strokeWidth = 1.75.dp,
                        )
                    }
                    CreditsLoad.Failed -> CreditsNote(stringResource(R.string.song_info_credits_error), error = true)
                    is CreditsLoad.Loaded -> if (load.credits.isEmpty()) {
                        CreditsNote(stringResource(R.string.song_info_no_credits), error = false)
                    } else {
                        load.credits.forEachIndexed { index, credit ->
                            if (index > 0) AlertRule(Modifier.padding(start = 16.dp))
                            SongInfoRow(credit.role, credit.text)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            AlertRule()
            if (credits == CreditsLoad.Failed) {
                AlertAction(label = stringResource(R.string.song_info_retry), emphasised = false, onClick = { retry++ })
                AlertRule()
            }
            AlertAction(label = stringResource(R.string.done), emphasised = true, onClick = onDismiss)
        }
    }
}

/** Label on the left, value on the right; a long value wraps under its own column. */
@Composable
private fun SongInfoRow(label: String, value: String) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ACTION_HEIGHT)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
            color = onSurface.copy(alpha = 0.6f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.45f, fill = false),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
            color = onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.55f),
        )
    }
}

@Composable
private fun CreditsNote(text: String, error: Boolean) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 17.sp),
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    )
}
