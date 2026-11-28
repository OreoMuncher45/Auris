package com.music.bitchord.playback.loopmix

/**
 * How long an armed loop mix may go without a usable clock from the outgoing
 * sink before its takeover. The sink publishes a reading on every renderer
 * tick while an arm exists, so a gap this long means it has stopped (nothing
 * is calling its position, or every projection lands past a shift) and the
 * mix would otherwise wait for a takeover that never comes.
 */
internal class LoopClockWait(private val timeoutMs: Long = TIMEOUT_MS) {
    private var lastReadingAt = Long.MIN_VALUE

    /** From the moment the arm is attached. */
    fun start(nowMs: Long) {
        lastReadingAt = nowMs
    }

    /** True once [reading] (null: none) has been missing for longer than the timeout. */
    fun expired(reading: Double?, nowMs: Long): Boolean {
        if (reading != null || lastReadingAt == Long.MIN_VALUE) {
            lastReadingAt = nowMs
            return false
        }
        return nowMs - lastReadingAt > timeoutMs
    }

    companion object {
        const val TIMEOUT_MS = 3_000L
    }
}
