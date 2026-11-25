package com.music.bitchord.ui.player

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.offset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.PlaybackPosition
import com.music.bitchord.sharedui.resources.*
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import org.jetbrains.compose.resources.stringResource

/**
 * The player in a landscape window — a tablet, or a phone on its side: two
 * columns rather than one, see [landscapePlayerAvailable] and
 * [LandscapePlayerLayout].
 *
 * A separate shape rather than something woven into the portrait player: the
 * portrait player's collapsing sleeve, hero banner and vertical drag gesture
 * exist to let one tall column be the player, the lyrics and the queue in
 * turn, and in landscape none of them has anything to do — the sleeve never
 * has to get out of anything's way.
 *
 * The settings and thresholds handed in as functions are read in the slot
 * that uses them, as they were when this was part of [NowPlayingScreen], so a
 * change recomposes that slot and not the player.
 */
@Composable
internal fun LandscapeNowPlaying(
    song: Song,
    playedBy: String?,
    transportSmoothness: Float,
    isPlaying: Boolean,
    isLoading: Boolean,
    position: PlaybackPosition,
    durationMs: Long,
    audioVersionSwitching: Boolean,
    queue: List<Song>,
    queueIndex: Int,
    hasPrevious: Boolean,
    hasNext: Boolean,
    autoplayEnabled: Boolean,
    signedIn: Boolean,
    likeStatus: LikeStatus,
    onToggleLike: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onJumpTo: (Int) -> Unit,
    onRemoveFromQueue: (Int) -> Unit,
    onMoveInQueue: (Int, Int) -> Unit,
    onQueueDragActiveChange: (Boolean) -> Unit,
    onClearQueue: () -> Unit,
    onOpenMenu: () -> Unit,
    onMenuAnchor: ((IntRect) -> Unit)?,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (browseId: String?, name: String) -> Unit,
    lyrics: List<LyricLine>?,
    lyricsUnavailable: Boolean,
    playerHaze: HazeState,
    overlays: PlayerOverlayState,
    controlsLocked: Boolean,
    showLyricStrip: () -> Boolean,
    lyricsPlayhead: LyricPlayhead,
    seekToLyric: (Long) -> Unit,
    hideVolumeBar: () -> Boolean,
    hideSongStatus: () -> Boolean,
    mixing: () -> Boolean,
    canvas: CanvasArtwork?,
    clipState: CanvasClipState,
    scrub: PlayerScrub,
    shown: () -> Float,
    pastRestartPoint: () -> Boolean,
    panels: PlayerPanelState,
    dock: PlayerDock?,
    openQueueSearch: (() -> Unit)?,
    lyricsLoadingText: String,
    lyricsTranslation: LyricsTranslationUi,
    lyricsShareEnabled: Boolean,
    lyricPicker: LyricsPicker,
    showRevertCue: () -> Boolean,
    trackMotion: TrackChangeMotion,
    volume: PlayerVolume,
    art: PlayerArtwork,
    openPlaybackOrigin: () -> Unit,
    skipSwipeGesture: Modifier,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onVolumeChangeFinished: () -> Unit,
    playerActions: @Composable (showOutputLine: Boolean) -> Unit,
    playerOverlays: @Composable () -> Unit,
    fullArtworkBlurContent: @Composable (Modifier) -> Unit,
    dockFrame: DockFrame,
    dockT: () -> Float,
    docking: () -> Boolean,
    dockMoving: () -> Boolean,
    dockFade: () -> Float,
    sleeveLayer: GraphicsLayer,
    dockHost: Modifier,
    modifier: Modifier = Modifier,
    /** The Best Part pill, over the foot of the artwork; see [BestPartPill]. */
    bestPart: @Composable (Modifier) -> Unit = {},
) {
    // Paused always wins outright over a scrub in progress — see
    // [ARTWORK_PAUSE_SHRINK_SCALE].
    val landscapeArtScale by animateFloatAsState(
        targetValue = when {
            !isPlaying -> ARTWORK_PAUSE_SHRINK_SCALE
            scrub.scrubbing -> ARTWORK_DRAG_SHRINK_SCALE
            else -> ARTWORK_EXPANDED_SCALE
        },
        animationSpec = tween(durationMillis = ARTWORK_SCALE_DURATION_MS, easing = ArtworkScaleEasing),
        label = "landscapeArtworkScale",
    )
    val versionAligning by PlayerSettings.versionAlignmentInProgress.collectAsStateWithLifecycle()
    val transitionWindow by PlayerSettings.smartTransitionWindow.collectAsStateWithLifecycle()
    val panelOpen = panels.lyricsOpen || panels.queueOpen

    // Where the sleeve is drawn this frame on its way to or from the mini
    // player's cover, or null when it can't be worked out.
    //
    // Unlike the portrait sleeve, this one keeps its layout and is carried:
    // it is square and so is the cover, so a move and a scale take it the
    // whole way, with nothing about the picture's crop to re-lay out. It
    // rides as much of the sheet's travel as it can without passing the
    // cover — the same share, for the same reason, as the portrait
    // sleeve's `dockRide`.
    fun landscapeDockPose(): LandscapeDockPose? {
        if (dock == null) return null
        val t = dockT()
        val mini = dock.miniArtOnScreen() ?: return null
        val anchor = dockFrame.anchor ?: return null
        val host = dockFrame.host?.takeIf { it.isAttached } ?: return null
        val sleeve = dockFrame.landscapeArt?.takeIf { it.isAttached } ?: return null
        val offset = (dock.offset() ?: return null).toInt().toFloat()
        val travel = dock.sheetTravel.takeIf { it > 0f } ?: return null
        if (sleeve.size.width <= 0) return null
        val open = Rect(
            offset = anchor + host.localPositionOf(sleeve, Offset.Zero),
            size = Size(sleeve.size.width.toFloat(), sleeve.size.height.toFloat()),
        )
        val ride = ((mini.top - open.top) / travel).coerceIn(0f, 1f)
        val rect = lerp(mini, open.translate(0f, offset * ride), t)
        // In the player's own pixels, where it sits this frame.
        return LandscapeDockPose(
            left = rect.left - anchor.x,
            top = rect.top - anchor.y - offset,
            scale = rect.width / open.width,
        )
    }
    // The sleeve's corner as laid out, rounding off into the cover's as it
    // lands. Laid out at full size and drawn scaled, so asked for as the
    // radius that comes out right once it has been scaled.
    val landscapeArtShape: () -> Shape = {
        val t = dockT()
        if (dock == null || t >= 1f) {
            dockFrame.cornerShape(LANDSCAPE_ART_CORNER)
        } else {
            val scale = landscapeDockPose()?.scale?.takeIf { it > 0f } ?: 1f
            dockFrame.cornerShape(lerp(dock.miniArtCorner(), LANDSCAPE_ART_CORNER, t) / scale)
        }
    }

    Box(modifier = modifier.fillMaxSize().then(dockHost)) {
        LandscapePlayerLayout(
            pane = when {
                panels.lyricsOpen -> PlayerPane.Lyrics
                panels.queueOpen -> PlayerPane.Queue
                else -> PlayerPane.Main
            },
            background = { backgroundModifier ->
                // The whole screen is the sheets' frost source: there is
                // no full-bleed banner here to be it instead.
                fullArtworkBlurContent(backgroundModifier.hazeSource(playerHaze))
            },
            artwork = { artworkModifier ->
                // The outgoing cover until it has left — see
                // [TrackChangeMotion.shownKey]. Read in here, so the swap
                // recomposes the artwork and not the player around it.
                val shownArt = trackMotion.sleeveArt(song.videoId, art)
                LandscapeArtwork(
                    artRequest = shownArt.request,
                    artLoaded = shownArt.loaded,
                    onArtState = shownArt::onState,
                    canvas = canvas,
                    canvasRendered = clipState.rendered,
                    isPlaying = isPlaying,
                    onCanvasRenderedChange = { clipState.rendered = it },
                    // A clip decoding behind a player on its way to the
                    // bar is work nobody sees — see [dockMoving].
                    pausedForTransition = dockMoving(),
                    sleeveShape = landscapeArtShape,
                    // Let go of on the way to the mini player, whose
                    // cover sits flat in its bar.
                    shadowFraction = dockT,
                    modifier = artworkModifier
                        .then(
                            if (dock != null) {
                                Modifier.onGloballyPositioned { dockFrame.landscapeArt = it }
                            } else {
                                Modifier
                            },
                        )
                        // While it docks the sleeve is drawn by the player
                        // itself, over everything and outside the player's
                        // fade — see [dockHost] — at the place and size
                        // [landscapeDockPose] gives it.
                        .drawWithContent {
                            val pose = if (docking()) landscapeDockPose() else null
                            if (pose != null) {
                                dockFrame.portalLeft = pose.left
                                dockFrame.portalTop = pose.top
                                dockFrame.portalScale = pose.scale
                                sleeveLayer.record { this@drawWithContent.drawContent() }
                            } else {
                                if (docking()) {
                                    // Nowhere to fly to this frame: the
                                    // portal draws nothing rather than a
                                    // stale copy.
                                    dockFrame.portalScale = 0f
                                }
                                drawContent()
                            }
                        }
                        .then(skipSwipeGesture)
                        .graphicsLayer {
                            // The paused shrink and the swipe nudge are
                            // let go of on the way, as the portrait
                            // sleeve's are: the cover does neither.
                            val t = dockT()
                            val scale = if (t >= 1f) landscapeArtScale else 1f + (landscapeArtScale - 1f) * t
                            scaleX = scale
                            scaleY = scale
                            translationX = if (t >= 1f) trackMotion.offset() else trackMotion.offset() * t
                            alpha = trackMotion.alpha()
                        }
                        // With a panel up the sleeve is the way back to
                        // the player, as the portrait thumbnail is.
                        .clickable(
                            enabled = panelOpen,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            panels.closePanels()
                        },
                ) {
                    // The stats belong to the player, not to the sleeve, so
                    // they leave it along with the rest of the player.
                    // Faded with the player rather than flown with the
                    // sleeve: the cover in the bar has none. The Best Part
                    // pill stacks above the stats, which stay pinned.
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .graphicsLayer { alpha = if (panelOpen) 0f else dockFade() },
                    ) {
                        if (!panelOpen) bestPart(Modifier.padding(bottom = BEST_PART_INSET))
                        SleeveNerdStats(
                            song = song,
                            isPlaying = isPlaying,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }
            },
            actions = { playerActions(true) },
            mainPane = { compact ->
                LandscapeMainPane(
                    compact = compact,
                    caption = if (hideSongStatus()) null else playbackOriginText(song, playedBy),
                    onOpenCaption = openPlaybackOrigin,
                    credits = {
                        LandscapeCredits(
                            song = song,
                            signedIn = signedIn,
                            likeStatus = likeStatus,
                            showRevertCue = showRevertCue(),
                            onToggleLike = onToggleLike,
                            onOpenMenu = onOpenMenu,
                            onMenuAnchor = onMenuAnchor,
                            onOpenAlbum = onOpenAlbum,
                            onOpenArtist = onOpenArtist,
                        )
                    },
                    lyricStrip = if (showLyricStrip()) {
                        {
                            CurrentLyricStrip(
                                lines = lyricsTranslation.displayedLyrics,
                                trackKey = song.videoId,
                                playhead = lyricsPlayhead,
                                isPlaying = isPlaying && position.advancing,
                                durationMs = durationMs,
                                lyricsUnavailable = lyricsUnavailable,
                                loadingText = lyricsLoadingText,
                                onClick = panels.openLyrics,
                            )
                        }
                    } else {
                        null
                    },
                    scrubber = {
                        PlayerScrubber(
                            shown = shown,
                            durationMs = durationMs,
                            loading = versionAligning || audioVersionSwitching,
                            mixing = mixing() && !scrub.scrubbing,
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
                    },
                    transport = { spacing ->
                        TransportRow(
                            isPlaying = isPlaying,
                            isLoading = isLoading || audioVersionSwitching,
                            previousEnabled = !controlsLocked &&
                                (hasPrevious || pastRestartPoint()),
                            nextEnabled = !controlsLocked && hasNext,
                            onPrevious = onPrevious,
                            onPlayPause = onPlayPause,
                            onNext = onNext,
                            compact = compact,
                            smoothness = transportSmoothness,
                            spacing = spacing,
                        )
                    },
                    volume = if (hideVolumeBar()) {
                        null
                    } else {
                        {
                            VolumeRow(
                                value = { volume.level.value },
                                onValueChange = onVolumeChange,
                                onValueChangeFinished = onVolumeChangeFinished,
                            )
                        }
                    },
                )
            },
            lyricsPane = {
                LandscapeLyricsPane(
                    hasLyrics = lyricsTranslation.displayedLyrics.isNotEmpty(),
                    placeholder = if (lyricsUnavailable) {
                        stringResource(Res.string.lyrics_not_available)
                    } else {
                        lyricsLoadingText
                    },
                    status = lyricsTranslation.status,
                    onStatusClick = { overlays.lyricsProviders = true },
                    picking = lyricPicker.picking,
                    pickBar = {
                        LyricsPickBar(
                            shareEnabled = lyricPicker.picks.isNotEmpty() &&
                                !lyricPicker.overBudget,
                            onCancel = lyricPicker.cancel,
                            onShare = lyricPicker.share,
                        )
                    },
                    romanizationToggle = {
                        RomanizationToggleButton(
                            state = lyricsTranslation.romanizationState,
                            showingRomanization = lyricsTranslation.showingRomanization,
                            enabled = !lyrics.isNullOrEmpty(),
                            onClick = lyricsTranslation.toggleRomanization,
                        )
                    },
                    translationToggle = {
                        TranslationToggleButton(
                            state = lyricsTranslation.translationState,
                            showingTranslation = lyricsTranslation.showingTranslation,
                            enabled = !lyrics.isNullOrEmpty(),
                            onClick = lyricsTranslation.toggleTranslation,
                        )
                    },
                ) { panelModifier ->
                    LyricsTranslationMotion(
                        trigger = lyricsTranslation.transition,
                        reduceMotion = lyricsTranslation.reduceMotion,
                        modifier = panelModifier,
                    ) { particleProgress ->
                        // [controlsOpen] is a constant `true`: that flag
                        // exists because the portrait player hides its
                        // transport behind the lyrics and needs a tap to
                        // bring it back. Here there is nothing hidden for
                        // a tap to reveal, and leaving the reveal gesture
                        // armed would only eat taps meant for the lines.
                        LyricsPanel(
                            lines = lyrics.orEmpty(),
                            subLines = lyricsTranslation.subLines,
                            trackKey = song.videoId,
                            playhead = lyricsPlayhead,
                            looking = !lyricsUnavailable,
                            isPlaying = isPlaying && position.advancing,
                            onSeekToLine = seekToLyric,
                            controlsOpen = true,
                            onRevealControls = {},
                            onHideControls = {},
                            translationProgress = particleProgress,
                            canPick = lyricsShareEnabled,
                            picking = lyricPicker.picking,
                            picked = lyricPicker.picks,
                            onPickLine = lyricPicker.pick,
                            onTogglePick = lyricPicker.toggle,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            },
            queuePane = {
                InlineQueue(
                    queue = queue,
                    currentIndex = queueIndex,
                    autoplayEnabled = autoplayEnabled,
                    controlsLocked = controlsLocked,
                    onJumpTo = onJumpTo,
                    onRemove = onRemoveFromQueue,
                    onMove = onMoveInQueue,
                    onClear = onClearQueue,
                    onDragActiveChange = onQueueDragActiveChange,
                    onOpenSearch = openQueueSearch,
                    modifier = Modifier.fillMaxSize(),
                )
            },
        )
        playerOverlays()
    }
}

/**
 * The artwork's own play/pause/scrub pose in the landscape layout. Three flat
 * scales and one priority rule: paused always wins outright over a scrub in
 * progress, rather than the two combining — there is one artwork, in one of
 * three settled poses, never a blend of two.
 */
private const val ARTWORK_EXPANDED_SCALE = 1f
private const val ARTWORK_PAUSE_SHRINK_SCALE = 0.88f
private const val ARTWORK_DRAG_SHRINK_SCALE = 0.94f

/**
 * The curve and duration those three poses move between — an ease-out cubic
 * over 500ms rather than a spring. A spring reads wrong for a press-and-release
 * gesture specifically: it visibly lags a quick scrub and keeps settling after
 * the finger has already lifted.
 *
 * The phone layout keeps its own bouncy spring (`artScale` in [NowPlayingScreen]) — it is answering a
 * different thing there, a sleeve that also collapses into a header, and the
 * bounce is the signature.
 */
private val ArtworkScaleEasing = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)
private const val ARTWORK_SCALE_DURATION_MS = 500

/**
 * One frame of the landscape sleeve's trip to the mini player: where it is
 * drawn in the player, and how small. See `landscapeDockPose`.
 */
private class LandscapeDockPose(val left: Float, val top: Float, val scale: Float)
