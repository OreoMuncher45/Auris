package com.music.bitchord.playback.smart

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether an Automix transition is being previewed at this moment.
 *
 * ## Why the panel needs to be told
 *
 * The panel's preview button has two faces — play and stop — and it can only
 * draw the right one if something says which state it is in. The engine is
 * the only thing that knows: a preview ends by itself when the blend is over,
 * and the listener never pressed stop. A button in the panel that guessed
 * would sit there saying "stop" long after the preview had finished.
 *
 * The state lives in shared code because the panel is shared code and the
 * engine is not: the phone's playback service publishes into it, and the
 * panel reads it, with neither of them knowing the other exists.
 */
object AutomixPreviewState {
    private val _playing = MutableStateFlow(false)

    /** True while a preview of the transition is playing. */
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    /**
     * Says whether a preview is playing.
     *
     * Called by the engine that owns the players, and by nobody else: the
     * panel asks for a preview and waits to be told what happened.
     */
    fun setPlaying(playing: Boolean) {
        _playing.value = playing
    }
}
