package com.music.bitchord.playback.smart

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * What the two sides of a transition do to their volume across the overlap.
 *
 * ## Why this is separate from the blend style
 *
 * [TransitionStyle] says how the two tracks are joined — beat-matched, filtered,
 * equal-power — and every one of those joins has carried the same gain pair:
 * the incoming side rises on a sine and the outgoing side falls on its cosine,
 * so the two always sum to one and the blend never dips.
 *
 * That pair is a *choice about the music*, and it is not the only one. A
 * listener who wants the outgoing track to vanish under the incoming one, or
 * to come up out of nothing, is asking for something the equal-power pair
 * cannot do. This is that ask: a shape for the two faders across the overlap,
 * applied on top of whatever join the planner chose.
 *
 * ## Which side a shape describes
 *
 * A shape is named after the side it is about, and the other side follows:
 * [FADE_OUT] is the outgoing track's shape and leaves the arriving one to
 * rise as it always has; [FADE_IN] is the arriving track's shape and leaves
 * the leaving one to hold its ground until it has to go.
 *
 * ## Where the curves live
 *
 * The gains are here rather than in the renderer so that the panel and the
 * renderer agree by construction: the curve the listener sees drawn over the
 * outgoing track is the curve the renderer will ride.
 */
enum class TransitionVolumeShape {
    /**
     * The planner's own answer: the equal-power pair every transition has
     * used. Not a shape of its own, and the same as [NONE] in what is heard.
     */
    AUTO,

    /**
     * Explicitly plain: the equal-power pair, asked for rather than assumed.
     *
     * Different from [AUTO] only in that it is a statement — "leave the
     * volume alone" — rather than an absence of one.
     */
    NONE,

    /**
     * The outgoing track drops away at a point and the other song takes over.
     *
     * The outgoing side holds its level and then falls off a cliff, and the
     * incoming side is up almost at once: the swap is a cut, not a fade.
     */
    SLAM,

    /** The outgoing track fades down across the whole overlap, ahead of the equal-power curve. */
    FADE_OUT,

    /** The outgoing track is gone by the first third of the overlap. */
    FAST_OUT,

    /** The incoming track fades up across the whole overlap, behind the equal-power curve. */
    FADE_IN,

    /** The incoming track is up by the first third of the overlap. */
    FAST_IN;

    /** Whether this shape leaves the equal-power pair alone. */
    val isPlain: Boolean get() = this == AUTO || this == NONE

    /**
     * The outgoing track's gain across the overlap, 0..1.
     *
     * [progress] is how far through the overlap the blend is, 0 at its opening
     * and 1 at its close.
     */
    fun outgoingGain(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        val gain = when (this) {
            AUTO, NONE -> fall(p)
            SLAM -> if (p <= SLAM_AT) 1f else 1f - (p - SLAM_AT) / SLAM_RAMP
            FADE_OUT -> fall(p.pow(FADE_OUT_CURVE))
            FAST_OUT -> fall((p / FAST_OUT_SPAN).coerceIn(0f, 1f))
            FADE_IN -> if (p <= HOLD_UNTIL) 1f else fall((p - HOLD_UNTIL) / (1f - HOLD_UNTIL))
            FAST_IN -> if (p <= HOLD_UNTIL) 1f else fall((p - HOLD_UNTIL) / (1f - HOLD_UNTIL))
        }
        return gain.coerceIn(0f, 1f)
    }

    /**
     * The incoming track's gain across the overlap, 0..1.
     *
     * The other half of the same pair: the shape says what one side does, and
     * this says what the other side does while it happens.
     */
    fun incomingGain(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        val gain = when (this) {
            AUTO, NONE -> rise(p)
            SLAM -> rise((p / SLAM_RISE_SPAN).coerceIn(0f, 1f))
            FADE_OUT -> rise(p)
            FAST_OUT -> rise(p)
            FADE_IN -> rise(p.pow(FADE_IN_CURVE))
            FAST_IN -> rise((p / FAST_IN_SPAN).coerceIn(0f, 1f))
        }
        return gain.coerceIn(0f, 1f)
    }

    private companion object {
        /** The sine/cosine pair: equal power, so the two sides never dip. */
        fun rise(p: Float): Float = sin(p * (Math.PI / 2.0)).toFloat()

        fun fall(p: Float): Float = cos(p * (Math.PI / 2.0)).toFloat()

        /** Where the outgoing side drops in a slam, as a share of the overlap. */
        const val SLAM_AT = 0.62f

        /** How long that drop takes: a cliff, not a fade. */
        const val SLAM_RAMP = 0.05f

        /** How fast the incoming side is up in a slam. */
        const val SLAM_RISE_SPAN = 0.16f

        /** The outgoing side is gone by here in a fast out. */
        const val FAST_OUT_SPAN = 0.34f

        /** The incoming side is up by here in a fast in. */
        const val FAST_IN_SPAN = 0.34f

        /**
         * How much ahead of the equal-power curve a fade out runs.
         *
         * Below one the curve falls away faster than the sine, so the track
         * being left is quieter for longer and the track arriving has to carry
         * the overlap on its own.
         */
        const val FADE_OUT_CURVE = 0.62f

        /** Above one the curve is slower: the arriving track stays out of the way. */
        const val FADE_IN_CURVE = 1.55f

        /** Where the outgoing side lets go in the incoming shapes. */
        const val HOLD_UNTIL = 0.68f
    }
}
