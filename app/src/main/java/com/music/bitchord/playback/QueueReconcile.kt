package com.music.bitchord.playback

/**
 * What a handoff has to do to the standby's copy of the queue so it matches
 * the session's live queue, worked out without touching either player.
 *
 * Kept apart from [CrossfadeController] so the rules can be tested on plain
 * lists: an edit that reaches the playing or incoming entry is never patched
 * over, because the standby would then swap in holding the wrong track or a
 * history row the listener already removed.
 */
internal sealed interface QueueReconcile {
    /** The queues differ at or before the incoming entry: don't hand off. */
    data object Bail : QueueReconcile

    /** Already identical. */
    data object Same : QueueReconcile

    /** Live entries from [from] were appended; copy them across. */
    data class Append(val from: Int) : QueueReconcile

    /** Entries from [size] were removed from the live queue's end. */
    data class TrimTo(val size: Int) : QueueReconcile

    /** The queues first differ at [from], after the incoming entry; replace from there. */
    data class ReplaceFrom(val from: Int) : QueueReconcile

    companion object {
        /**
         * @param liveIncoming index in [live] of the entry being brought in:
         *   the session's next entry, or its current one when the session
         *   already ran on into it before the handoff.
         * @param heldCurrent index of the standby's playing entry in [held].
         */
        fun <T> plan(
            live: List<T>,
            liveIncoming: Int,
            held: List<T>,
            heldCurrent: Int,
            same: (T, T) -> Boolean,
        ): QueueReconcile {
            val incoming = live.getOrNull(liveIncoming) ?: return Bail
            val heldIncoming = held.getOrNull(heldCurrent) ?: return Bail
            if (!same(incoming, heldIncoming)) return Bail
            val shared = minOf(live.size, held.size)
            var firstDifference = -1
            for (i in 0 until shared) {
                if (!same(live[i], held[i])) {
                    firstDifference = i
                    break
                }
            }
            if (firstDifference < 0) {
                return when {
                    live.size > held.size -> Append(held.size)
                    live.size < held.size && live.size > heldCurrent -> TrimTo(live.size)
                    else -> Same
                }
            }
            return if (firstDifference <= heldCurrent) Bail else ReplaceFrom(firstDifference)
        }
    }
}
