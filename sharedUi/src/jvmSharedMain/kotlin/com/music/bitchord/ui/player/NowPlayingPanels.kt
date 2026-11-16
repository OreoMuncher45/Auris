package com.music.bitchord.ui.player

import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
