package com.music.bitchord.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.PlaybackPosition
import com.music.bitchord.data.settings.LastPlayerScreen

/**
 * Which surface the portrait player is showing — the sleeve, the lyrics or the
 * queue — and the state that travels with the two panels.
 *
 * One holder rather than a dozen loose states, so the pieces of the player
 * that open, close and follow the panels can be handed one object. Each field
 * is still a state of its own: a write invalidates only what reads that field.
 *
 * [restored] is the surface the player was last left on — read once when the
 * expanded player is created, so reopening it (and a process restart) returns
 * to the same surface.
 */
@Stable
internal class PlayerPanelState(restored: LastPlayerScreen) {
    var queueOpen by mutableStateOf(restored == LastPlayerScreen.QUEUE)
    var lyricsOpen by mutableStateOf(restored == LastPlayerScreen.LYRICS)

    /**
     * 0 = full sleeve, 1 = queue. Everything that moves reads off this.
     *
     * Plain state driven by an animation rather than an animated value,
     * because it has two drivers and only one of them is an animation: the
     * toggle at the foot of the player, which travels end to end, and a finger
     * dragging the sleeve upward, which sets it outright. An animation keyed on
     * [queueOpen] cannot be pushed around mid-flight by a drag — and a drag that
     * could only move the *target* would have nothing to show for itself until
     * it was released, then jump from wherever the animation had got to.
     *
     * Read only inside layout and draw lambdas and the player's thresholds,
     * never as a value in composition.
     */
    val queueSlide: MutableFloatState = mutableFloatStateOf(0f)

    /**
     * Whether the lyrics or queue list is actively mid-scroll. The player's own
     * swipe gestures — skip-by-drag and the dismiss band — are suppressed for
     * as long as either is true, so a scroll that grazes past a list's edge
     * can never be misread as a drag meant for the player underneath it. Reset
     * whenever the owning panel closes, since a list scrolled mid-transition
     * out never gets a matching "stopped scrolling" event of its own.
     */
    var lyricsScrolling by mutableStateOf(false)
    var queueScrolling by mutableStateOf(false)

    var lyricsControlsOpen by mutableStateOf(restored == LastPlayerScreen.LYRICS)
    var queueControlsOpen by mutableStateOf(true)

    /**
     * Whether a finger is on the sleeve right now. Parks the queue's settle
     * rather than leaving the two to write the same value on alternate frames.
     */
    var queueDragging by mutableStateOf(false)

    /**
     * Bumped when a drag hands the value back, so the settle runs again even
     * though [queueOpen] may not have moved: a swipe that gave up short of
     * [QUEUE_CARRY_FRACTION] has to fall back to 0 just as surely as one that
     * carried has to finish reaching 1.
     */
    var queueReleased by mutableIntStateOf(0)

    // Change the panel and its controls in the same snapshot. Driving the
    // controls from a LaunchedEffect left one composed frame where lyrics were
    // open but the half-player was not, so every trip into lyrics briefly
    // started an exit animation and reversed it on the following frame.
    val openLyrics: () -> Unit = {
        lyricsControlsOpen = true
        lyricsOpen = true
        queueOpen = false
    }
    val closeLyrics: () -> Unit = {
        lyricsControlsOpen = false
        lyricsOpen = false
    }
    val toggleLyrics: () -> Unit = {
        if (lyricsOpen) closeLyrics() else openLyrics()
    }
    val toggleQueue: () -> Unit = {
        val opening = !queueOpen
        if (opening && lyricsOpen) queueSlide.floatValue = 1f
        // With the queue rather than after it, for the reason [openLyrics]
        // gives: left to an effect, it was a second recomposition of the whole
        // player on the sleeve's first frame of moving.
        if (opening) queueControlsOpen = true
        queueOpen = opening
        if (opening) closeLyrics()
    }

    /** Back to the sleeve from either panel. */
    val closePanels: () -> Unit = {
        queueOpen = false
        closeLyrics()
    }
}

/**
 * How far the portrait sleeve has collapsed into the header, 0 to 1, and the
 * handful of thresholds composition is allowed to ask of it.
 *
 * A queue tap/drag already owns a 420ms progress value ([PlayerPanelState.queueSlide]).
 * Reusing it avoids driving this large layout from two independent animations
 * on every frame. Lyrics retain the ordinary collapse animation ([animated]);
 * switching queue -> lyrics stays at one because its target never changed.
 */
@Stable
internal class PlayerCollapse(panels: PlayerPanelState, animated: State<Float>) {
    /**
     * A function rather than a value. It moves on every frame of a 420ms
     * collapse and of a finger dragging the queue, and read as a value it
     * recomposed the whole player on each of those frames — the sleeve, the
     * credits, both panels and the controls, just to move a few of them. Layout
     * and draw lambdas call it where they place or paint; composition only asks
     * the thresholds below, each of which changes once per collapse.
     */
    val p: () -> Float = {
        val queueOwnsCollapse = !panels.lyricsOpen &&
            (panels.queueOpen || panels.queueDragging || panels.queueSlide.floatValue > 0.001f)
        if (queueOwnsCollapse) panels.queueSlide.floatValue else animated.value
    }
    val started by derivedStateOf { p() > 0f }
    val atRest by derivedStateOf { p() == 0f }
    val pastSettling by derivedStateOf { p() >= 0.01f }
    val pastHalf by derivedStateOf { p() >= 0.5f }
    val almostDone by derivedStateOf { p() >= 0.999f }
    val done by derivedStateOf { p() >= 1f }
    val queueShowing by derivedStateOf { panels.queueSlide.floatValue > 0.01f }
}

/**
 * The full lyrics panel of the portrait player, with its translation,
 * romanisation and Sing controls and the bar that shares a pick.
 *
 * Kept composed but off screen while [visible] is false once the player has
 * prewarmed it, so opening it does not lay out every line on the frame the
 * sleeve starts moving.
 */
@Composable
internal fun BoxScope.PortraitLyricsPane(
    visible: Boolean,
    /** The panel's own fade-up after the sleeve has settled. Read in draw. */
    fade: () -> Float,
    panels: PlayerPanelState,
    lyrics: List<LyricLine>?,
    lyricsTranslation: LyricsTranslationUi,
    song: Song,
    lyricsPlayhead: LyricPlayhead,
    lyricsUnavailable: Boolean,
    isPlaying: Boolean,
    position: PlaybackPosition,
    onSeekToLine: (Long) -> Unit,
    canPick: Boolean,
    lyricPicker: LyricsPicker,
    singActive: Boolean,
    onOpenSing: () -> Unit,
) {
    LyricsTranslationMotion(
        trigger = lyricsTranslation.transition,
        reduceMotion = lyricsTranslation.reduceMotion,
        modifier = Modifier
            .fillMaxSize()
            .padding(top = HEADER_HEIGHT)
            // Keep the prepared list measured but completely
            // outside hit-testing/drawing until it is needed.
            .offset {
                if (visible) IntOffset.Zero else IntOffset(100_000, 0)
            }
            // Arrives after the sleeve has finished collapsing
            // into the header rather than during — see
            // [collapseDone]. Fading lyrics in over a sleeve
            // still mid-collapse doubled the same movement in
            // two places on screen at once, and composing them
            // there was what made the collapse stutter.
            .graphicsLayer { alpha = if (visible) fade() else 0f },
    ) { particleProgress ->
        LyricsPanel(
            lines = lyrics.orEmpty(),
            subLines = lyricsTranslation.subLines,
            trackKey = song.videoId,
            playhead = lyricsPlayhead,
            looking = !lyricsUnavailable,
            isPlaying = isPlaying && position.advancing,
            active = visible,
            onSeekToLine = onSeekToLine,
            controlsOpen = panels.lyricsControlsOpen,
            onRevealControls = { panels.lyricsControlsOpen = true },
            onHideControls = { panels.lyricsControlsOpen = false },
            translationProgress = particleProgress,
            onScrollingChange = { panels.lyricsScrolling = it },
            canPick = canPick,
            picking = lyricPicker.picking,
            picked = lyricPicker.picks,
            onPickLine = lyricPicker.pick,
            onTogglePick = lyricPicker.toggle,
            discsFloatOver = true,
            modifier = Modifier.fillMaxSize(),
        )
    }

    // Floated over the foot of the lyrics rather than placed in
    // the controls below them. In the controls it was a row of
    // layout like any other, and the bottom block is measured at
    // its natural height — so the button's 34dp came straight
    // off the panel above it and the lyrics lost a line. Drawn
    // here it costs the panel nothing and still reads as sitting
    // on top of the half player, because that is where it is.
    //
    // Arrives and leaves on the controls' own fade: the panel is
    // for reading, and a control parked over the words when
    // nobody asked for the controls is one more thing between
    // the reader and them.
    val translateShown = visible && panels.lyricsControlsOpen
    val translateFade by animateFloatAsState(
        targetValue = if (translateShown) 1f else 0f,
        animationSpec = tween(if (translateShown) 220 else 160),
        label = "translateFade",
    )
    // Whether the toggles are composed at all, through derived state: read as
    // a value, the fade recomposed this whole pane on every frame of it, and
    // the controls fade every time they stand down or come back. The alpha is
    // read in the layers below, in draw.
    val translateDrawn by remember { derivedStateOf { translateFade > 0.01f } }
    if (translateDrawn && !lyricPicker.picking) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .graphicsLayer { alpha = translateFade },
        ) {
            RomanizationToggleButton(
                state = lyricsTranslation.romanizationState,
                showingRomanization = lyricsTranslation.showingRomanization,
                enabled = translateShown && !lyrics.isNullOrEmpty(),
                onClick = lyricsTranslation.toggleRomanization,
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .graphicsLayer { alpha = translateFade },
        ) {
            TranslationToggleButton(
                state = lyricsTranslation.translationState,
                showingTranslation = lyricsTranslation.showingTranslation,
                // Not tappable on the way out: a disc at 20%
                // opacity is on its way to gone, not a target.
                enabled = translateShown && !lyrics.isNullOrEmpty(),
                onClick = lyricsTranslation.toggleTranslation,
            )
        }
        // Sing's way in, centered between the two language
        // discs the way Apple's mic sits in its lyrics view.
        // Hidden while picking: the pick bar owns the bottom
        // center then, and a mic under it would be a second
        // target in the same place.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .graphicsLayer { alpha = translateFade },
        ) {
            SingMicButton(
                singActive = singActive,
                enabled = translateShown && !lyrics.isNullOrEmpty(),
                onClick = onOpenSing,
            )
        }
    }
    // The bar arrives and leaves without a transition of its own:
    // it is an instruction over the words, and one that has to
    // be legible the instant it is on. Anything that animated
    // would be over the reader's first pick anyway.
    //
    // Only while the panel it belongs to is open: the pick is cancelled
    // once the panel closes, and this keeps the bar off the sleeve for the
    // frame that takes. Asked of the panel rather than of [visible], which
    // also waits on the collapse, so the bar can never hide while a pick
    // is still live.
    if (panels.lyricsOpen && lyricPicker.picking) {
        LyricsPickBar(
            shareEnabled = lyricPicker.picks.isNotEmpty() &&
                !lyricPicker.overBudget,
            onCancel = lyricPicker.cancel,
            onShare = lyricPicker.share,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * The queue panel of the portrait player.
 *
 * Kept composed but off screen while [visible] is false once the player has
 * prewarmed it, for the same reason as [PortraitLyricsPane].
 */
@Composable
internal fun PortraitQueuePane(
    visible: Boolean,
    /** The panel's own fade-up after the sleeve has settled. Read in draw. */
    fade: () -> Float,
    panels: PlayerPanelState,
    queue: List<Song>,
    queueIndex: Int,
    autoplayEnabled: Boolean,
    controlsLocked: Boolean,
    onJumpTo: (Int) -> Unit,
    onRemoveFromQueue: (Int) -> Unit,
    onMoveInQueue: (Int, Int) -> Unit,
    onClearQueue: () -> Unit,
    onQueueDragActiveChange: (Boolean) -> Unit,
    onOpenSearch: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = HEADER_HEIGHT)
            .offset {
                if (visible) IntOffset.Zero else IntOffset(100_000, 0)
            }
            .graphicsLayer {
                alpha = if (!visible) {
                    0f
                } else if (panels.queueDragging) {
                    ((panels.queueSlide.floatValue - 0.45f) / 0.55f).coerceIn(0f, 1f)
                } else {
                    fade()
                }
                translationY = if (visible) {
                    (1f - panels.queueSlide.floatValue) * 26.dp.toPx()
                } else {
                    0f
                }
            },
    ) {
        InlineQueue(
            queue = queue,
            currentIndex = queueIndex,
            autoplayEnabled = autoplayEnabled,
            controlsLocked = controlsLocked,
            onJumpTo = onJumpTo,
            onRemove = onRemoveFromQueue,
            onMove = onMoveInQueue,
            onClear = onClearQueue,
            onScrollingChange = { panels.queueScrolling = it },
            onDragActiveChange = onQueueDragActiveChange,
            collapsePlayerOnScroll = true,
            onRevealPlayer = { panels.queueControlsOpen = true },
            onHidePlayer = { panels.queueControlsOpen = false },
            onOpenSearch = onOpenSearch,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The lyrics panel's half player stands down after [LYRICS_CONTROLS_IDLE_MS]
 * left alone, and never while a finger is on the scrubber or the volume bar.
 *
 * A composable of its own so the states it is keyed on are read here: a
 * scrub or a volume drag starting or ending re-keys this effect and recomposes
 * nothing else.
 */
@Composable
internal fun LyricsControlsIdleEffect(
    panels: PlayerPanelState,
    scrub: PlayerScrub,
    volume: PlayerVolume,
) {
    LaunchedEffect(panels.lyricsOpen, panels.lyricsControlsOpen, scrub.scrubbing, volume.dragging) {
        if (panels.lyricsOpen && panels.lyricsControlsOpen && !scrub.scrubbing && !volume.dragging) {
            delay(LYRICS_CONTROLS_IDLE_MS)
            panels.lyricsControlsOpen = false
        }
    }
}
