package com.music.bitchord.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.layout
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.model.Song
import com.music.bitchord.sharedui.resources.*
import com.music.bitchord.ui.LyricsProviderState
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.flow.first

/**
 * Which of the drawers and dialogs the player raises over itself are up.
 *
 * One holder rather than a loose state apiece, so the player hands one object
 * to [PlayerOverlays] and to the gestures that have to stand down while one of
 * them is showing. Each flag is still a state of its own: setting one
 * invalidates only what reads that one.
 */
@Stable
internal class PlayerOverlayState {
    var audioOutput by mutableStateOf(false)
    var audioPipeline by mutableStateOf(false)
    var cast by mutableStateOf(false)
    var lyricsProviders by mutableStateOf(false)
    var listenTogetherMembers by mutableStateOf(false)

    /**
     * The queue's search. Open over the whole player rather than inside the
     * queue pane, so the listener searches and adds without ever leaving the
     * player — the music keeps playing underneath and the queue is one tap
     * back.
     */
    var queueSearch by mutableStateOf(false)

    /**
     * The drawer holding the finished lyrics card, set the moment Share is
     * confirmed. Nothing is drawn by the player: the picture is a bitmap and a
     * canvas, which is the phone's to make, so the request is handed down and
     * the sheet comes back through [PlayerHost.LyricsShareSheet].
     */
    var lyricsShare by mutableStateOf<LyricsShareRequest?>(null)

    /**
     * Who's actually in the party is worth a look before the settings page —
     * see [ListenTogetherMembersSheet]. Only meaningful once there is a party
     * to show, so the pill and the caption fall back to Listen Together itself
     * (create/join) when there isn't one.
     */
    val openListenTogetherMembers: () -> Unit = { listenTogetherMembers = true }
}

/**
 * The drawers and dialogs the player raises over itself. Mounted by whichever
 * layout is on screen — they are overlays over the player, not part of either
 * shape of it.
 */
@Composable
internal fun PlayerOverlays(
    overlays: PlayerOverlayState,
    hazeState: HazeState,
    song: Song,
    isPlaying: Boolean,
    accountName: String?,
    onListenTogether: () -> Unit,
    spatialAudioOn: Boolean,
    onToggleSpatial: () -> Unit,
    lyricsSource: LyricsSource?,
    lyricsProviderStates: Map<LyricsSource, LyricsProviderState>,
    onSelectLyricsProvider: (LyricsSource) -> Unit,
    lyricsOffsetOpen: Boolean,
    onDismissLyricsOffset: () -> Unit,
    onAddToQueue: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
) {
    if (overlays.audioOutput) {
        AudioOutputSheet(
            hazeState = hazeState,
            accountName = accountName,
            onDismiss = { overlays.audioOutput = false },
            onOpenPipeline = { overlays.audioPipeline = true },
            onOpenCast = { overlays.cast = true },
            onListenTogether = onListenTogether,
            onOpenListenTogetherMembers = overlays.openListenTogetherMembers,
            spatialOn = spatialAudioOn,
            onToggleSpatial = onToggleSpatial,
        )
    }
    if (overlays.lyricsProviders) {
        LyricsProviderSheet(
            hazeState = hazeState,
            currentSource = lyricsSource,
            states = lyricsProviderStates,
            onSelect = onSelectLyricsProvider,
            onDismiss = { overlays.lyricsProviders = false },
        )
    }
    if (overlays.audioPipeline) {
        AudioPipelineDialog(
            hazeState = hazeState,
            isPlaying = isPlaying,
            onDismiss = { overlays.audioPipeline = false },
        )
    }
    if (overlays.cast) {
        PlayerPlatform.host.CastDialog(
            hazeState = hazeState,
            onDismiss = { overlays.cast = false },
        )
    }
    if (overlays.listenTogetherMembers) {
        ListenTogetherMembersSheet(
            hazeState = hazeState,
            onDismiss = { overlays.listenTogetherMembers = false },
            onManage = {
                overlays.listenTogetherMembers = false
                onListenTogether()
            },
        )
    }
    if (lyricsOffsetOpen) {
        LyricsOffsetSheet(
            hazeState = hazeState,
            onDismiss = onDismissLyricsOffset,
        )
    }
    overlays.lyricsShare?.let { request ->
        PlayerPlatform.host.LyricsShareSheet(
            hazeState = hazeState,
            request = request,
            onDismiss = { overlays.lyricsShare = null },
        )
    }
    // Last, and drawn over everything: the search covers the whole player,
    // the queue behind it included. It is the one overlay here the listener
    // types into, so nothing may sit above it.
    //
    // A box, not a page: it arrives where it is going to be with a short
    // fade and a small settle, and the scrim beneath it comes up with it so
    // the player behind dims rather than disappearing. The field takes focus
    // as it composes, so nothing about the search waits for the animation.
    AnimatedVisibility(
        visible = overlays.queueSearch,
        enter = fadeIn(tween(160)) + scaleIn(tween(200), initialScale = 0.94f),
        exit = fadeOut(tween(120)) + scaleOut(tween(150), targetScale = 0.96f),
    ) {
        PlayerQueueSearchBox(
            currentSong = song,
            onDismiss = { overlays.queueSearch = false },
            onAddToQueue = onAddToQueue,
            onPlayNext = onPlayNext,
        )
    }
}

/**
 * Back, for everything the player raises over itself.
 *
 * The newest enabled handler is the one the next press reaches, so these are
 * registered bottom layer first: the panels, then the drawers and dialogs
 * drawn over them. The order here is the order back unwinds them in.
 */
@Composable
internal fun PlayerBackHandlers(
    panels: PlayerPanelState,
    overlays: PlayerOverlayState,
    lyricsOffsetOpen: Boolean,
    onDismissLyricsOffset: () -> Unit,
    lyricPicker: LyricsPicker,
) {
    // Back out of the lyrics panel to the player, and only from the player
    // itself out to the mini player.
    //
    // See [PlayerBackHandler] for why this needs more than a BackHandler.
    // Controls being visible must not insert an extra navigation level. Back
    // always leaves lyrics in one step, whether it starts over the lyrics list
    // or over the half-player at the bottom.
    PlayerBackHandler(enabled = panels.lyricsOpen, onBack = panels.closeLyrics)

    // Back out of the queue to the player.
    PlayerBackHandler(enabled = panels.queueOpen) { panels.queueOpen = false }

    // Back out of the queue's search to the queue. Registered after the
    // queue's own handler because that is the order back reads them in: the
    // newest enabled handler is the one the next press reaches, and the search
    // is drawn over the queue rather than instead of it.
    PlayerBackHandler(enabled = overlays.queueSearch) { overlays.queueSearch = false }

    // Registered ahead of the pipeline dialog's own handler below: the
    // pipeline is now only ever opened from the row at the bottom of this
    // drawer, so it is always the topmost of the two when both are up, and
    // back has to close it first rather than taking the drawer out from
    // under it.
    PlayerBackHandler(enabled = overlays.audioOutput) { overlays.audioOutput = false }

    PlayerBackHandler(enabled = overlays.lyricsProviders) { overlays.lyricsProviders = false }

    PlayerBackHandler(enabled = overlays.listenTogetherMembers) { overlays.listenTogetherMembers = false }

    PlayerBackHandler(enabled = overlays.audioPipeline) { overlays.audioPipeline = false }

    PlayerBackHandler(enabled = overlays.cast) { overlays.cast = false }

    PlayerBackHandler(enabled = lyricsOffsetOpen, onBack = onDismissLyricsOffset)

    // Both of these sit ahead of [lyricsOpen]'s own handler — see the note on
    // the queue above — because both are drawn *over* the panel rather than
    // instead of it: back should take away whichever of them is up and leave the
    // lyrics underneath exactly where the reader left them.
    PlayerBackHandler(enabled = overlays.lyricsShare != null) { overlays.lyricsShare = null }

    // Backing out of a pick drops the pick, not the panel: somebody who changed
    // their mind lands on the same verses they started from rather than having
    // to open the whole panel again.
    PlayerBackHandler(enabled = lyricPicker.picking) { lyricPicker.cancel() }
}
