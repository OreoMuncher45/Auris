package com.music.bitchord.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Whether the app is actually on screen.
 *
 * The distinction this exists to draw is one Compose does not draw on its own:
 * a `LaunchedEffect` belongs to the *composition*, not to the lifecycle, and a
 * composition outlives the activity being stopped — it is torn down only when
 * the activity is destroyed. So every loop the UI starts goes on running with
 * the screen off: position polls, frame clocks, a video decoder feeding a
 * surface nobody is looking at.
 *
 * None of that work has a viewer, and none of it survives being skipped —
 * whatever it was keeping up to date is re-read the moment the screen comes
 * back. Gating on this is the difference between "cheap while visible" and
 * "cheap", and it costs nothing on screen.
 *
 * RESUMED rather than STARTED: a paused-but-visible activity is one behind a
 * dialog or in the background half of split screen, which is not a case worth
 * animating for either.
 *
 * Playback itself is not gated on this and must not be — audio comes from
 * [com.music.bitchord.playback.PlaybackService], which is a foreground service
 * precisely so that it goes on running when this does not.
 */
@Composable
fun rememberIsForeground(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycle) {
        // Every event rather than ON_RESUME/ON_PAUSE alone: the state is read
        // back off the lifecycle instead of inferred from which event arrived,
        // so there is no transition this can be left out of step by.
        val observer = LifecycleEventObserver { owner, _ ->
            foreground = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return foreground
}

/**
 * Whether the page this is composed in is wholly hidden behind the player —
 * the portrait panel all the way up, which draws edge to edge and leaves the
 * page undrawn underneath it.
 *
 * The page stays composed while it is hidden, so it is current the moment the
 * panel starts down again. That is right for its layout and wrong for its
 * loops: a video clip on Home or a detail page went on decoding behind a
 * player that covered it, and each decoded frame woke the window to draw it —
 * a redraw a frame for a picture nobody could see, on a screen that otherwise
 * had nothing moving.
 *
 * Asked rather than handed down as a value, so a flip recomposes only the
 * readers of [rememberPageHidden], not the whole page. Never true for anything
 * composed in the player itself; it is provided around the page alone.
 */
val LocalPageHidden = compositionLocalOf<() -> Boolean> { { false } }

/** [LocalPageHidden] as a value that changes only when it flips. */
@Composable
fun rememberPageHidden(): Boolean {
    val hidden = LocalPageHidden.current
    val state = remember(hidden) { derivedStateOf(hidden) }
    return state.value
}
