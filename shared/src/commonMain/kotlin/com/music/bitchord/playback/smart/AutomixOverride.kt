package com.music.bitchord.playback.smart

/**
 * What the listener has chosen for one track's transition, over and above what
 * Automix worked out for itself.
 *
 * The planner's own answer is the default because it is the one that knows
 * the two recordings; this is the listener's answer, kept per track so that
 * "this song always blends into the next one like this" is a thing that can
 * be said and then meant every time.
 *
 * Every field is optional in the sense that *nothing* here is required: an
 * empty override is the planner's own behaviour, unchanged.
 */
data class AutomixOverride(
    /**
     * Whether Automix touches this track's transition at all.
     *
     * False leaves the transition to the plain crossfade: no beat match, no
     * filter, no cueing. It is the "not this song" switch.
     */
    val enabled: Boolean = true,
    /**
     * A blend the listener asked for, or null to leave the choice to the
     * planner.
     *
     * Asked for, not demanded: the planner honours it when it can execute it
     * — a beat-matched blend needs two grids that can be matched, and a
     * plain fade needs nothing — and says what it actually did in the plan
     * it returns.
     */
    val style: TransitionStyle? = null,
    /**
     * Whole bars of overlap the listener asked for, 0 to leave the size to
     * the planner. Clamped to the musical range the planner works in.
     */
    val bars: Int = 0,
    /**
     * The filter shape the listener asked for, or null to leave the choice to
     * the planner. Only ever heard on a filtered handoff.
     */
    val filter: FilterTransitionVariant? = null,
    /**
     * The tone shape the listener asked for the arriving track to have, or
     * null to leave the track as it was recorded.
     *
     * Heard on whichever side is arriving, for as long as the transition
     * lasts. It is not a permanent EQ on the track: it is part of the
     * handoff, and it is gone when the handoff is.
     */
    val eq: TransitionEqShape? = null,
    /**
     * An extra effect on the handoff, or null to leave it to the planner.
     *
     * Separate from the filter shape because they answer different questions:
     * the filter says which end of the spectrum is closed and when, the
     * effect says what happens to the outgoing track as it leaves.
     */
    val effects: AutomixEffect? = null,
    /**
     * Where the listener wants the overlap to begin on the outgoing track, in
     * seconds, or 0 to leave the placement to the planner.
     *
     * This is the panel's draggable line: the listener puts it where they
     * want the handoff and it snaps to the beat grid, so what they are asking
     * for is a musical place rather than an arbitrary one.
     *
     * The size stays whole bars whatever this says. A start that does not
     * leave room for a legal overlap is refused and the planner's own
     * placement stands.
     */
    val transitionStart: Double = 0.0,
) {
    /** Whether this override says anything at all. */
    val isEmpty: Boolean
        get() = enabled && style == null && bars <= 0 && filter == null && eq == null &&
            effects == null && transitionStart <= 0.0

    companion object {
        /** Nothing chosen: the planner's own behaviour. */
        val NONE = AutomixOverride()
    }
}

/**
 * An extra effect laid over the handoff, on top of the blend itself.
 *
 * ## Why this is separate from the filter
 *
 * The filter shape ([FilterTransitionVariant]) is about the spectrum: which
 * end is closed, and when. This is about the outgoing track as it leaves —
 * whether it goes quietly or trails away through an echo.
 *
 * They are separate because a listener who wants the low end to swap hard
 * may still want the vocal they are leaving behind to ring out for a moment
 * afterwards.
 */
enum class AutomixEffect {
    /** Nothing laid over the blend: the two tracks meet and one leaves. */
    NONE,

    /** The outgoing side trails away through a beat-synced echo tail. */
    ECHO_TAIL,
}
