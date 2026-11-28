package com.music.bitchord.playback.loopmix

/**
 * How a new loop-mix playhead reading relates to the last one: a loop wrap (a
 * deliberate jump back on the same deck), a deck change at the handoff (a
 * re-anchor onto the other song, never a wrap), or neither.
 */
internal enum class LoopPositionJump {
    NONE, WRAP, DECK_CHANGE;

    companion object {
        fun classify(
            previousMs: Long,
            previousIncoming: Boolean,
            positionMs: Long,
            incoming: Boolean,
            wrapMs: Long,
        ): LoopPositionJump = when {
            previousMs < 0 -> NONE
            previousIncoming != incoming -> DECK_CHANGE
            positionMs < previousMs - wrapMs -> WRAP
            else -> NONE
        }
    }
}
