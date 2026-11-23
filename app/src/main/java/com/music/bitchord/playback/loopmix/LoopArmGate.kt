package com.music.bitchord.playback.loopmix

import androidx.media3.common.Player

/**
 * Why an armed loop mix must not take over (or, once built, be armed), or null
 * when it still can. Read on the controller's tick while the decks build and
 * until the outgoing sink splices: every one of these makes the planned pair
 * wrong, and before the takeover the song can simply play on untouched.
 */
internal object LoopArmGate {
    fun refusal(
        bitPerfect: Boolean,
        automixOn: Boolean,
        playbackSpeed: Float,
        repeatMode: Int,
        nextStillIncoming: Boolean,
    ): String? = when {
        bitPerfect -> "bit-perfect is on"
        !automixOn -> "Automix was switched off"
        // The decks were decoded and planned at 1x; the refusal at planning time
        // only covered the speed then.
        playbackSpeed != 1f -> "playback speed is not 1x"
        // The song repeats instead: there is no next track to mix into.
        repeatMode == Player.REPEAT_MODE_ONE -> "repeat one is on"
        !nextStillIncoming -> "the next entry changed"
        else -> null
    }
}
