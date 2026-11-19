package com.music.bitchord.ui.player

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colours the listener pinned on the Now Playing screen.
 *
 * A null role means "follow the artwork", which is the player's original
 * behaviour. The roles are separate on purpose: recolouring the scrubber should
 * not also recolour the artist name, and the transport is the part most people
 * mean when they say the buttons are the wrong colour.
 */
data class PlayerColorOverrides(
    val accent: Color? = null,
    val seekbar: Color? = null,
    val text: Color? = null,
    /** The one-line lyric above the scrubber; null follows the artwork ink. */
    val lyrics: Color? = null,
    /**
     * The lyrics panel's own controls — the translate, romanize and sing
     * discs. Null follows the artwork ink, like everything else in the panel.
     */
    val lyricsButtons: Color? = null,
    /**
     * The line above the scrubber that says where the lyrics came from.
     * Null follows the artwork ink.
     */
    val lyricsSource: Color? = null,
)

internal val LocalPlayerAccent = staticCompositionLocalOf<Color?> { null }
internal val LocalPlayerSeekbar = staticCompositionLocalOf<Color?> { null }
internal val LocalPlayerText = staticCompositionLocalOf<Color?> { null }
internal val LocalPlayerLyrics = staticCompositionLocalOf<Color?> { null }

/**
 * The lyrics panel's own controls.
 *
 * A state, and never null: these follow the artwork's ink unless the listener
 * pinned a colour for them, so what arrives here is either the ink itself —
 * which moves with a playing clip — or a colour standing still. White by
 * default, so anything outside the expanded player keeps its look.
 */
internal val LocalPlayerLyricsButtons = staticCompositionLocalOf<State<Color>> {
    mutableStateOf(Color.White)
}

/**
 * The line that says where the lyrics came from.
 *
 * A state, for the reason [LocalPlayerLyricsButtons] is one.
 */
internal val LocalPlayerLyricsSource = staticCompositionLocalOf<State<Color>> {
    mutableStateOf(Color.White)
}

/**
 * The opacity roles that go with the artwork palette. Defaults match the
 * player's own table, so any surface outside the expanded player is unchanged.
 */
internal val LocalPlayerAlphas = staticCompositionLocalOf { NowPlayingAlphas() }
