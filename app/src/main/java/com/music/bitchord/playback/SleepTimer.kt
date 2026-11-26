package com.music.bitchord.playback

import android.os.SystemClock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * A Cast receiver's stream volume: the song's own level on the receiver, not
 * the device's volume and not the phone's.
 */
interface StreamVolume {
    /** The level when the fade began, 0..1. */
    val level: Double

    /** Ask the receiver for [level]; false when it refused or did not answer. */
    suspend fun set(level: Double): Boolean
}

/**
 * Sleep timer. Holds a deadline; [PlaybackService] watches it and pauses
 * playback once it passes.
 *
 * A deadline rather than a countdown: nothing has to tick for the timer to
 * stay accurate, so it survives the player UI being dismissed, and any
 * observer can work out how long is left for itself. elapsedRealtime, not
 * wall clock, so changing the system time can't cut a timer short.
 */
object SleepTimer {

    /** Deadline on [SystemClock.elapsedRealtime], or null when no timer is set. */
    val deadline = MutableStateFlow<Long?>(null)

    /** The preset that was chosen, so the picker can tick it. Null when off. */
    val minutes = MutableStateFlow<Int?>(null)

    /**
     * Pause when the current track ends instead of after a fixed wait.
     *
     * Deliberately not expressed as a deadline of "duration minus position":
     * seeking, crossfade and a queue that reorders itself would all leave that
     * number wrong, whereas the track ending is an event the player reports.
     */
    val afterTrack = MutableStateFlow(false)

    /** Durations offered in the picker. */
    val PRESETS = listOf(15, 30, 45, 60)

    /** Whether any kind of timer is currently armed. */
    val isRunning: Boolean get() = deadline.value != null || afterTrack.value

    fun start(minutes: Int) {
        afterTrack.value = false
        this.minutes.value = minutes
        deadline.value = SystemClock.elapsedRealtime() + minutes * 60_000L
    }

    /** Pause once the track playing right now finishes. */
    fun startAfterTrack() {
        minutes.value = null
        deadline.value = null
        afterTrack.value = true
    }

    fun cancel() {
        minutes.value = null
        deadline.value = null
        afterTrack.value = false
    }

    /** How long is left, or null when no timer is running. */
    fun remainingMs(): Long? =
        deadline.value?.let { (it - SystemClock.elapsedRealtime()).coerceAtLeast(0L) }

    /** How long the fade-out before a timed pause lasts, when it is on. */
    const val FADE_OUT_DURATION_MS = 30_000L

    /**
     * Whether the timer's fade-out is running. While it is, Automix and the
     * crossfade start no new blend: the fade owns the player's volume, and a
     * blend would bring the next track in at full volume over it. Written by
     * [PlaybackService], read on the crossfade's tick, so a plain flag.
     */
    @Volatile
    var fadingOut: Boolean = false

    /**
     * The volume for [remainingMs] before the deadline, over a fade lasting
     * [fadeMs]: full until the fade starts, then down in a straight line to
     * silence at the deadline.
     */
    fun fadeVolume(remainingMs: Long, fadeMs: Long = FADE_OUT_DURATION_MS): Float {
        if (remainingMs >= fadeMs) return 1f
        if (remainingMs <= 0L || fadeMs <= 0L) return 0f
        return (remainingMs.toFloat() / fadeMs).coerceIn(0f, 1f)
    }

    /**
     * Stop whatever is making the sound when the timer runs out: the Cast
     * receiver while one is connected, the phone's own player otherwise.
     */
    inline fun pauseFor(castActive: Boolean, pauseCast: () -> Unit, pauseLocal: () -> Unit) {
        if (castActive) pauseCast() else pauseLocal()
    }

    /** How often a Cast fade asks the receiver for a new level: each step is a round trip. */
    const val CAST_FADE_STEP_MS = 500L

    /** How a Cast fade ended. */
    enum class CastFade {
        /** The receiver confirmed the pause at the deadline. */
        PAUSED,
        /** The listener paused first: the timer still stands for whatever plays next. */
        LISTENER_PAUSED,
        /** Playback left the receiver (moved to the phone): the phone's path takes over. */
        MOVED,
        /** The receiver never confirmed a pause. */
        PAUSE_FAILED,
    }

    /** What the sleep timer does once a Cast fade has ended. */
    enum class AfterCastFade {
        /** Done: the receiver paused, or never would. The timer is cleared. */
        CANCEL,
        /** Playback is on the phone now: the phone's own fade and pause, at the same deadline. */
        CONTINUE_LOCAL,
        /** The listener paused: wait for the deadline, then pause whatever is playing. */
        WAIT_DEADLINE,
    }

    /**
     * Where the watcher goes after [fadeOutCast]. [castActiveNow] is read after
     * the fade returned, volume restore included: a handoff to the phone during
     * the awaited pause or the restore is a move, whatever the fade reported.
     */
    fun afterCastFade(result: CastFade, castActiveNow: Boolean): AfterCastFade = when {
        result == CastFade.MOVED || !castActiveNow -> AfterCastFade.CONTINUE_LOCAL
        result == CastFade.LISTENER_PAUSED -> AfterCastFade.WAIT_DEADLINE
        else -> AfterCastFade.CANCEL
    }

    /**
     * The pause at the deadline, wherever playback is: the receiver's is
     * confirmed and retried ([confirmCastPause] returns whether it took);
     * the phone's is immediate.
     */
    suspend fun pauseAtDeadline(castActive: () -> Boolean, confirmCastPause: suspend () -> Boolean, pauseLocal: () -> Unit) {
        repeat(CAST_PAUSE_ATTEMPTS) {
            if (!castActive()) {
                pauseLocal()
                return
            }
            val confirmed = confirmCastPause()
            // Checked again after every attempt, confirmed or not: Cast can end
            // while the pause is awaited, and playback resume on the phone.
            if (!castActive()) {
                pauseLocal()
                return
            }
            if (confirmed) return
        }
    }

    /** Pause attempts at the deadline before a Cast fade gives up on the receiver. */
    const val CAST_PAUSE_ATTEMPTS = 3

    /**
     * Fade a Cast receiver's stream down to silence by [spanMs] (the
     * deadline), pause it there, then put the stream volume back where it
     * was, so the next play is not silent.
     *
     * [volume] is null when the receiver can't set its stream volume; then,
     * and from a refused step on, there is no fade, but the pause still waits
     * for the deadline. [castActive] turning false means playback moved off
     * the receiver; [playing] turning false, that the listener paused. Both
     * end the fade without a pause, with different results. The pause is
     * awaited: [pause] returns whether the receiver confirmed it, and is
     * retried. The volume is put back on every exit, cancellation included,
     * and only after the pause.
     */
    suspend fun fadeOutCast(
        volume: StreamVolume?,
        spanMs: Long,
        stepMs: Long,
        now: () -> Long,
        castActive: () -> Boolean,
        playing: () -> Boolean,
        pause: suspend () -> Boolean,
    ): CastFade {
        val original = volume?.level
        val end = now() + spanMs.coerceAtLeast(0L)
        var fading = volume != null && spanMs > 0L
        var touched = false
        try {
            while (true) {
                if (!castActive()) return CastFade.MOVED
                if (!playing()) return CastFade.LISTENER_PAUSED
                val left = (end - now()).coerceAtLeast(0L)
                if (fading && volume != null && original != null) {
                    // Refused: no more fading, but the pause keeps its deadline.
                    if (volume.set(original * fadeVolume(left, spanMs))) touched = true else fading = false
                }
                // Read again: a receiver can take seconds to answer a volume
                // step, and waiting out the time measured before it would put
                // the pause that much past the deadline.
                val leftNow = (end - now()).coerceAtLeast(0L)
                if (leftNow <= 0L) break
                delay(minOf(stepMs, leftNow))
            }
            repeat(CAST_PAUSE_ATTEMPTS) {
                if (!castActive()) return CastFade.MOVED
                val confirmed = pause()
                // Checked again after the attempt: playback can move to the
                // phone while the pause is in flight.
                if (!castActive()) return CastFade.MOVED
                if (confirmed) return CastFade.PAUSED
            }
            return CastFade.PAUSE_FAILED
        } finally {
            if (touched && volume != null && original != null) withContext(NonCancellable) { restoreVolume(volume, original) }
        }
    }

    /** Tries at putting a Cast receiver's stream volume back before the fade gives up on it. */
    const val CAST_RESTORE_ATTEMPTS = 3
    private const val CAST_RESTORE_RETRY_MS = 500L

    /**
     * Puts the receiver's stream volume back to [original], retrying a
     * refusal: a receiver that took the fade to silence but refused the
     * restore would play the next resume silently. True once accepted.
     */
    private suspend fun restoreVolume(volume: StreamVolume, original: Double): Boolean {
        repeat(CAST_RESTORE_ATTEMPTS) { attempt ->
            if (volume.set(original)) return true
            if (attempt < CAST_RESTORE_ATTEMPTS - 1) delay(CAST_RESTORE_RETRY_MS)
        }
        return false
    }
}
