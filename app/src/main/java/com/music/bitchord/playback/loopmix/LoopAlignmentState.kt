package com.music.bitchord.playback.loopmix

import androidx.media3.common.Player
import kotlin.math.abs

/**
 * What alignment has established about the incoming player of a loop mix, and
 * whether the handoff may move onto it.
 *
 * Only a full, stall-free measurement that no correction follows is
 * confirmed; a stall before the handoff invalidates whatever was measured.
 * Kept apart from [LoopMixSession] (which needs the native renderer) so the
 * rules can be tested on their own.
 */
class LoopAlignmentState {
    /** The residual offset, ms (player minus deck); NaN until measured, and after a stall. */
    var errorMs: Double = Double.NaN
        private set

    var confirmed: Boolean = false
        private set

    fun record(errorSeconds: Double, confirmed: Boolean) {
        this.confirmed = confirmed
        errorMs = errorSeconds * 1000
    }

    fun invalidate() {
        confirmed = false
        errorMs = Double.NaN
    }

    /** The handoff gate: a confirmed offset within [maxErrorMs]; NaN never passes. */
    fun handoffAllowed(maxErrorMs: Double): Boolean = confirmed && abs(errorMs) <= maxErrorMs

    companion object {
        /** How many alignment attempts one loop mix gets before the handoff gate decides. */
        const val MAX_ATTEMPTS = 3

        /**
         * Whether alignment should be started again after an attempt ended:
         * never once confirmed (a confirmed alignment is not re-measured), and
         * otherwise while attempts remain. An attempt whose rounds all stalled
         * or lacked readings ends unconfirmed; without a retry the handoff later
         * refuses a player both clocks could by then have measured.
         */
        fun retryAfterAttempt(confirmed: Boolean, attempts: Int): Boolean = !confirmed && attempts < MAX_ATTEMPTS

        /**
         * Whether an incoming player's [playbackState] change must drop the
         * alignment: it buffers while the loop still runs ahead of the handoff.
         */
        fun stallInvalidates(playbackState: Int, loopActive: Boolean, handedOff: Boolean): Boolean =
            playbackState == Player.STATE_BUFFERING && loopActive && !handedOff
    }
}
