package com.music.bitchord.playback

/**
 * Where the incoming copy has to sit for a quality-upgrade fade to sound like
 * one recording changing quality rather than two recordings playing at once.
 *
 * Kept separate from [PlaybackService] because the sign convention is the
 * whole feature and is invisible in an expression: an upgrade joins two
 * *different files* of the same song, and [offsetMs] is the shift measured
 * between them — target time = source time + offset. Every position the
 * incoming player is given, and every drift read back from it, has to agree
 * on that direction or the fade chases the outgoing player forever.
 */
internal object UpgradeFadeAlignment {

    /** Content position in the incoming copy that matches [liveMs] of the outgoing one. */
    fun incomingPositionMs(liveMs: Long, offsetMs: Long, durationMs: Long = 0L): Long {
        val wanted = (liveMs + offsetMs).coerceAtLeast(0L)
        return if (durationMs > 0) wanted.coerceAtMost(durationMs) else wanted
    }

    /** How far the incoming copy is behind the outgoing one; positive means it must move forward. */
    fun driftMs(liveMs: Long, incomingMs: Long, offsetMs: Long): Long =
        (liveMs + offsetMs) - incomingMs

    /**
     * Where to seek the incoming player so that it lands on the outgoing
     * position *after* the seek's own resume latency.
     *
     * A seek is not instantaneous: the player reports the target at once,
     * becomes ready again a resume-time later, and resumes at the target while
     * the outgoing player has moved on by exactly that time. Seeking to the
     * live position therefore re-enters the blend the same number of
     * milliseconds behind on every attempt — the old code's single resync did
     * that, and it is why a blend that otherwise worked still sounded doubled.
     * [lastDriftMs] is what the previous correction measured after it resumed,
     * which *is* that latency, so aiming that far ahead cancels it.
     */
    fun correctionPositionMs(liveMs: Long, offsetMs: Long, lastDriftMs: Long): Long =
        (liveMs + offsetMs + lastDriftMs.coerceIn(0L, MAX_RESUME_LATENCY_MS))
            .coerceAtLeast(0L)

    /** Close enough for the ear: below this the two copies sum without a comb of their own. */
    const val FINE_DRIFT_MS = 12L

    /**
     * What the fade will still accept after the corrections have run out.
     *
     * Codec frame boundaries and two audio pipelines' own latency put a floor
     * under how exact a seek can be. A residual under this stays inside the
     * first comb notch's neighbourhood and is inaudible under a one-second
     * blend; refusing the upgrade over it would trade a real quality gain for
     * no artefact at all.
     */
    const val MAX_ACCEPTABLE_DRIFT_MS = 35L

    /**
     * Corrections tried before the fade is given up rather than chased.
     *
     * One to measure the resume time, one to cancel it, and the rest for a
     * device whose resume time moves between seeks — a correction only ever
     * learns the *last* one.
     */
    const val MAX_CORRECTIONS = 4

    /** Ceiling on the resume latency the correction will compensate for. */
    const val MAX_RESUME_LATENCY_MS = 400L
}
