package com.music.bitchord.playback

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow

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
}
