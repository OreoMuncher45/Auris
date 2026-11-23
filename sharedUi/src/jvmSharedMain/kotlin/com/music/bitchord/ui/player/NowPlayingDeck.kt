package com.music.bitchord.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.PlaybackPosition
import com.music.bitchord.sharedui.resources.*
import kotlinx.coroutines.delay

/**
 * Lyrics, output and queue, with the output name under them — the row both
 * shapes of the player end on, and the only thing in the landscape player's
 * left column besides the sleeve.
 *
 * [showOutputLine] is about the portrait player: the reference player has no
 * line under its controls at all, and the AirPlay mark already says whether
 * the sound is on a receiver. So portrait passes false and drops the plain
 * device name, keeping it only while a Listen Together party is live — the one
 * state nothing else on that screen shows. Landscape has room under the row
 * and keeps it.
 */
@Composable
internal fun PlayerActionsFooter(
    showOutputLine: Boolean,
    panels: PlayerPanelState,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    autoplayEnabled: Boolean,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleAutoplay: () -> Unit,
    onOpenOutput: () -> Unit,
    outputActive: Boolean,
    accountName: String?,
    onOpenMembers: () -> Unit,
) {
    PlayerActionRow(
        lyricsOpen = panels.lyricsOpen,
        queueOpen = panels.queueOpen,
        shuffleEnabled = shuffleEnabled,
        repeatMode = repeatMode,
        autoplayEnabled = autoplayEnabled,
        onToggleLyrics = panels.toggleLyrics,
        onToggleQueue = panels.toggleQueue,
        onToggleShuffle = onToggleShuffle,
        onCycleRepeat = onCycleRepeat,
        onToggleAutoplay = onToggleAutoplay,
        onOpenOutput = onOpenOutput,
        outputActive = outputActive,
    )
    if (showOutputLine || rememberPartyBadge().inParty) {
        Spacer(Modifier.height(18.dp))
        Box(
            modifier = Modifier.fillMaxWidth().height(20.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            OutputCaption(
                accountName = accountName,
                onOpenOutput = onOpenOutput,
                onOpenMembers = onOpenMembers,
            )
        }
    }
}

/**
 * The foot of the portrait player: the lyric strip, the scrubber, the
 * transport, the volume bar and the action row.
 *
 * One block, measured at its natural height and pinned to the foot of the
 * player. Whatever is left over above it is the artwork's, which is what keeps
 * this row of controls in the same place on every screen instead of being
 * shoved off the bottom of a tall one.
 *
 * A scope of its own: the panel and scrub state it reads recomposes the deck,
 * not the player around it.
 */
@Composable
internal fun PortraitControlDeck(
    panels: PlayerPanelState,
    /** The deck's measured height, for Spotify's credits placement. */
    onSpotifyDeckHeight: (Dp) -> Unit,
    showLyricLine: Boolean,
    lyricsTranslation: LyricsTranslationUi,
    song: Song,
    lyricsPlayhead: LyricPlayhead,
    isPlaying: Boolean,
    position: PlaybackPosition,
    durationMs: Long,
    lyricsUnavailable: Boolean,
    lyricsLoadingText: String,
    onShowLyricsProviders: () -> Unit,
    shown: () -> Float,
    versionSwitching: Boolean,
    mixing: Boolean,
    scrub: PlayerScrub,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    isLoading: Boolean,
    controlSpread: Dp,
    audioVersionSwitching: Boolean,
    previousEnabled: Boolean,
    nextEnabled: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    transportSmoothness: Float,
    hideVolumeBar: Boolean,
    volume: PlayerVolume,
    onVolumeChange: (Float) -> Unit,
    onVolumeChangeFinished: () -> Unit,
    actions: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.onSizeChanged { size ->
            onSpotifyDeckHeight(with(density) { size.height.toDp() })
        },
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = PLAYER_MAX_WIDTH)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Current lyric, one line, directly above the scrubber. It stays in
            // the layout — and stays fully visible — whether or not the queue
            // is open: dropping it would shorten this block and the controls
            // under it would jump the moment the queue started sliding in, and
            // fading it away behind the queue left this the one place in the
            // player where the current line simply vanished.
            //
            // Off means off: with synced lyrics disabled in Settings, or the
            // line switched off in Customization, the slot goes with it. A
            // placeholder kept "for the layout" is exactly the empty row this
            // preference exists to remove, and the deck is anchored to the foot
            // of the player, so it closes up rather than leaving a hole. The
            // dedicated lyrics button in the bottom row is still the way into
            // the full panel.
            if (!panels.lyricsOpen && showLyricLine) {
                CurrentLyricStrip(
                    lines = lyricsTranslation.displayedLyrics,
                    trackKey = song.videoId,
                    playhead = lyricsPlayhead,
                    isPlaying = isPlaying && position.advancing,
                    durationMs = durationMs,
                    lyricsUnavailable = lyricsUnavailable,
                    loadingText = lyricsLoadingText,
                    // Still visible over the queue, so still a valid way in:
                    // opens the same full lyrics panel it always has, closing
                    // the queue behind it the same way the "Up next" glyph
                    // closes lyrics behind the queue.
                    onClick = panels.openLyrics,
                )
            }
            if (panels.lyricsOpen) {
                LyricsStatusWithChange(
                    status = lyricsTranslation.status,
                    onStatusClick = onShowLyricsProviders,
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(y = 6.dp)
                        .padding(vertical = 4.dp),
                )
            }
            val transitionWindow by PlayerSettings.smartTransitionWindow.collectAsStateWithLifecycle()
            PlayerScrubber(
                shown = shown,
                durationMs = durationMs,
                loading = versionSwitching,
                mixing = mixing && !scrub.scrubbing,
                // Hidden while scrubbing: the planner is still describing
                // where the transition *would* be, and a marker sitting under
                // a finger that is moving the playhead invites reading it as
                // a drag target.
                transitionWindow = transitionWindow
                    ?.takeIf { !scrub.scrubbing && it.end > it.start }
                    ?.let { it.start..it.end },
                onScrub = onScrub,
                onScrubFinished = onScrubFinished,
            ) {
                PlaybackQualityLabel(
                    song = song,
                    isLoading = isLoading,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 8.dp),
                )
            }

            // The transport rides midway between the two blocks it separates:
            // the scrubber above it, and the volume bar and toggle row below,
            // which sit close enough together to read as one. Both of its own
            // gaps take half the spread, so on a tall screen it holds the
            // centre rather than drifting up under the seek bar.
            //
            // The gap above it also gives up the scrubber's drop: the lyric
            // line and the scrubber go down by that much, and the transport
            // stays where it is. See [SCRUBBER_DROP]. It also gives up the
            // badge slot's extra height. See [scrubberToTransportGap].
            Spacer(Modifier.height(scrubberToTransportGap(controlSpread)))

            TransportRow(
                isPlaying = isPlaying,
                isLoading = isLoading || audioVersionSwitching,
                previousEnabled = previousEnabled,
                nextEnabled = nextEnabled,
                onPrevious = onPrevious,
                onPlayPause = onPlayPause,
                onNext = onNext,
                smoothness = transportSmoothness,
            )

            Spacer(Modifier.height(12.dp - DECK_ROWS_LIFT + controlSpread / 2))

            // Hidden means gone: no placeholder slot. The deck is anchored to
            // the foot of the player, so the rows above it close down into the
            // space instead of leaving the empty band a hidden bar used to sit
            // in — which read as a gap between the transport and the action row.
            if (!hideVolumeBar) {
                VolumeRow(
                    value = { volume.level.value },
                    onValueChange = onVolumeChange,
                    onValueChangeFinished = onVolumeChangeFinished,
                )
            }

            // The volume slider already has 13dp below its drawn track.
            // Balance that invisible inset with the caption gap below the icons.
            Spacer(Modifier.height(6.dp))

            actions()
            // The deck is pinned to the foot of the player, so every dp taken
            // off this gap moves the whole deck down by a dp: the lyric strip,
            // the scrubber, the times, the transport and the action row all
            // ride a little lower, and the space comes out of the bottom
            // margin instead.
            //
            // Which is where the reference player's own deck sits: its scrubber
            // is at seventy per cent of the screen and its transport at
            // eighty, where this one had them three per cent higher. It is
            // the smallest move on the screen and the one that makes the
            // bottom of it line up with the reference's.
            //
            // The lift comes out of the gap above the volume row and not out of
            // here, so the row has air under it while the rows above it stay
            // put — see [DECK_ROWS_LIFT].
            Spacer(Modifier.height(6.dp + DECK_ROWS_LIFT))
        }
    }
}

/**
 * When Spotify Canvas's control deck stands down over its video and when it
 * comes back.
 */
@Composable
internal fun SpotifyCanvasDeckEffects(
    clipState: CanvasClipState,
    presentation: Boolean,
    autoHide: Boolean,
    isPlaying: Boolean,
    /** Read inside: a scrub or volume drag starting re-keys only these effects. */
    scrub: PlayerScrub,
    volume: PlayerVolume,
    lyricsOpen: Boolean,
    queueOpen: Boolean,
    mixing: Boolean,
) {
    // The setting defaults on, restoring the five-second stand-down, but the
    // listener can keep the deck open indefinitely from Spotify integration.
    // Pausing or interacting with a continuous control suspends the countdown;
    // it starts fresh once playback/interaction resumes.
    LaunchedEffect(
        presentation,
        clipState.spotifyControlsOpen,
        autoHide,
        isPlaying,
        scrub.scrubbing,
        volume.dragging,
        lyricsOpen,
        queueOpen,
        mixing,
    ) {
        if (!presentation || !clipState.spotifyControlsOpen ||
            !autoHide || !isPlaying || scrub.scrubbing || volume.dragging ||
            lyricsOpen || queueOpen || mixing
        ) return@LaunchedEffect
        delay(SPOTIFY_CANVAS_CONTROLS_IDLE_MS)
        clipState.spotifyControlsOpen = false
    }

    // Returning from a subview, or regaining the TextureView after backgrounding,
    // is a fresh visit, so show the deck again before any optional countdown.
    LaunchedEffect(presentation, lyricsOpen, queueOpen) {
        if (presentation && !lyricsOpen && !queueOpen) {
            clipState.spotifyControlsOpen = true
        }
    }
    // A transition can begin while Canvas is standing alone. Restore and pin
    // the compact deck so both the outgoing and incoming song are identified;
    // normal tap/idle collapsing resumes as soon as the mix finishes.
    LaunchedEffect(presentation, mixing) {
        if (presentation && mixing) clipState.spotifyControlsOpen = true
    }
}
