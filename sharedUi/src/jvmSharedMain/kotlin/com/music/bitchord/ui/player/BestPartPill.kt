package com.music.bitchord.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.PlaybackPosition
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.best_part
import com.music.bitchord.sharedui.resources.best_part_description
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import com.music.bitchord.ui.icons.BitChordIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.stringResource

/**
 * "Best Part": a small pill on the artwork that skips to the part of the song
 * people replay most, or failing that to its chorus.
 *
 * Offered only while it is still ahead: once the playhead has reached the best
 * part, by the pill or by just listening, it goes for the rest of the track. A
 * track with no best part known (no replay curve, no synced lyrics to find a
 * chorus in), a local file, and the stretch while Automix is mixing into the
 * next song never show it.
 *
 * Sits over the artwork, so nothing under the artwork moves when it comes and
 * goes. A plain translucent fill and a hairline rim rather than a live blur:
 * the pill would otherwise pay for a blur of the artwork on every frame the
 * artwork moves.
 */
@Composable
internal fun BestPartPill(
    song: Song,
    /** Read only through derivedStateOf below: the pill recomposes on a crossing, never per tick. */
    position: PlaybackPosition,
    /** The stream is still resolving or buffering; the lookup waits for it. */
    isLoading: Boolean,
    durationMs: Long,
    /** The player's own lyrics, as they load. Read when the lookup needs them, never in composition. */
    lyrics: () -> List<LyricLine>?,
    /** The player has given up on lyrics for this track. */
    lyricsUnavailable: () -> Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled by PlayerSettings.showBestPart.collectAsStateWithLifecycle()
    if (!enabled || song.localUri != null) return
    val loading by rememberUpdatedState(isLoading)
    val duration by rememberUpdatedState(durationMs)
    val currentLyrics by rememberUpdatedState(lyrics)
    val currentUnavailable by rememberUpdatedState(lyricsUnavailable)
    val start by produceState<Long?>(null, song.videoId) {
        // Playback first: the lookup is a page from youtube.com, and a track
        // that is still starting has better uses for the connection. A quick
        // skip past this track cancels the lookup before it is made.
        snapshotFlow { loading }.first { !it }
        delay(LOOKUP_DELAY_MS)
        value = try {
            PlayerPlatform.host.bestPartMs(song, duration) {
                // The words the player is loading anyway, once they have
                // arrived or the player has given up on them.
                snapshotFlow { currentLyrics() to currentUnavailable() }
                    .first { (lines, unavailable) -> lines != null || unavailable }
                    .first
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
    // Remembered per track: having been heard is what puts the pill away.
    var heard by remember(song.videoId) { mutableStateOf(false) }
    val at = start
    // Read through derivedStateOf, so the pill recomposes as the playhead
    // crosses the best part, not on every tick of it.
    val ahead by remember(at, position) {
        derivedStateOf { at != null && position.positionMs < at - AHEAD_SLACK_MS }
    }
    val reaching by remember(at, position) {
        // Only a playhead inside the best part counts as hearing it. Further on
        // is a reading left over from the last track, or a skip past it, and
        // the pill should come back for a seek to before it.
        derivedStateOf { at != null && position.positionMs in (at - AHEAD_SLACK_MS)..(at + HEARD_WINDOW_MS) }
    }
    LaunchedEffect(reaching) { if (reaching) heard = true }

    // Not while Automix is mixing into the next track: what is ahead then is
    // the next song, not this one's best part.
    val mixing by PlayerSettings.smartMixInProgress.collectAsStateWithLifecycle()

    val haptics = rememberHaptics()
    AnimatedVisibility(
        visible = at != null && ahead && !heard && !mixing,
        enter = fadeIn(tween(BEST_PART_FADE_MS)),
        exit = fadeOut(tween(BEST_PART_FADE_MS)),
        modifier = modifier,
    ) {
        val description = stringResource(Res.string.best_part_description)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(CircleShape)
                .background(PILL_FILL)
                .border(1.dp, PILL_RIM, CircleShape)
                .clickable(role = Role.Button) {
                    val target = at ?: return@clickable
                    haptics.play(Haptic.Tap)
                    heard = true
                    onSeek(target)
                }
                .semantics { contentDescription = description }
                .padding(start = 12.dp, end = 15.dp, top = 7.dp, bottom = 7.dp),
        ) {
            Icon(
                imageVector = BitChordIcons.MusicNote,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(Res.string.best_part),
                style = MaterialTheme.typography.labelLarge,
                fontSize = 13.sp,
                fontWeight = FontWeight.W700,
                color = Color.White,
                maxLines = 1,
            )
        }
    }
}

/** Just short of the best part already counts as there, so the pill doesn't flash back on a late reading. */
private const val AHEAD_SLACK_MS = 400L

/** How far into the best part a playhead still counts as having heard it. */
private const val HEARD_WINDOW_MS = 20_000L

private const val BEST_PART_FADE_MS = 280

/** How long after the stream is ready the lookup waits, so it never competes with the start of playback. */
private const val LOOKUP_DELAY_MS = 1_500L

/** Dark enough to hold white text on a pale cover without a blur under it. */
private val PILL_FILL = Color(0xFF141416).copy(alpha = 0.62f)

private val PILL_RIM = Color.White.copy(alpha = 0.18f)
