package com.music.bitchord.playback

/**
 * How "sleep after this song" meets a transition.
 *
 * The song the listener means is the one they are hearing as the session
 * when they set the timer. Mid-blend, before the handoff, that is still the
 * outgoing track, so the track fading in is not going to play: the blend is
 * dropped and the outgoing track fades to silence on its own. Left running,
 * the blend reached its handoff, the queue's advance fired the timer, and
 * the pause landed with both songs half faded: a cut, not an ending. After
 * the handoff the incoming track is the session and is "this song" itself,
 * so the blend finishes and the timer waits for that track's end.
 */
internal object SleepFade {
    fun dropsBlend(sleepAfterTrack: Boolean, handedOff: Boolean): Boolean = sleepAfterTrack && !handedOff

    /**
     * The fade-out's length: the transition's own span, but never longer than
     * what is left of the track. A timer set past the point the fade would have
     * started (or a blend dropped partway) still has to reach silence by the
     * track's end, not be cut by it partway down.
     */
    fun span(fadeMs: Long, timeLeftMs: Long): Long = minOf(fadeMs, timeLeftMs).coerceAtLeast(1L)
}
