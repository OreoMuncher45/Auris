package com.music.bitchord.playback.smart

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The tone shape a transition can leave on the track arriving.
 *
 * ## Why this is separate from the filter shape
 *
 * The filter shape ([FilterTransitionVariant]) is about *where* the two tracks
 * hand over — which end of the spectrum is closed, and when. This is about
 * what the incoming track sounds like while it is arriving: a warmer, a
 * brighter or a darker entry than the record itself.
 *
 * They are separate because they answer separate questions, and a listener
 * who wants the handoff to be a low, heavy bass swap may still want the
 * arriving vocal to sound bright and present against it.
 *
 * ## The curve
 *
 * A single peaking band, centred where the shape says it is. The response is
 * computed from the same coefficients the audio path uses, so the line drawn
 * in the panel is the curve that is actually heard rather than a drawing of
 * one.
 */
enum class TransitionEqShape(
    /** Where the shaping is centred, in Hz. */
    val centerHz: Float,
    /** How much is added at the centre, in dB. Zero means no shaping at all. */
    val gainDb: Float,
) {
    /** No shaping: the track arrives as it was recorded. */
    FLAT(1_000f, 0f),

    /** Weight on the body of the track — kicks and bass bloom into the handoff. */
    WARM(220f, 4.5f),

    /** Presence on the vocal and the snare, so the arrival cuts through. */
    BRIGHT(3_000f, 4f),

    /** Openness at the top, for a track that wants to feel like air. */
    AIR(9_000f, 3.5f),

    /** The top rolled back, for a heavy, closed-in entry. */
    DARK(9_000f, -4f),
    ;

    /** Whether this shape changes the signal at all. */
    val isFlat: Boolean get() = gainDb == 0f

    /**
     * The shape's magnitude response across the audible band.
     *
     * One number per point, in dB, where 0 is the unfiltered signal. The
     * points are spread logarithmically, because that is how the ear hears
     * frequency and how every equalizer is drawn.
     *
     * [points] samples from 20 Hz to 20 kHz.
     */
    fun responseDb(points: Int = 48, sampleRate: Int = 48_000): List<Float> {
        if (isFlat || points <= 0) return List(points.coerceAtLeast(0)) { 0f }
        val out = ArrayList<Float>(points)
        val lowest = 20.0
        val highest = 20_000.0
        for (index in 0 until points) {
            val fraction = if (points == 1) 0.0 else index.toDouble() / (points - 1)
            val hz = lowest * Math.pow(highest / lowest, fraction)
            out.add(magnitudeDb(hz, sampleRate))
        }
        return out
    }

    /**
     * The response at one frequency, in dB.
     *
     * The peaking filter of [RBJ]: a bell around [centerHz] with a Q wide
     * enough to be a musical shape rather than a whistle.
     */
    fun magnitudeDb(hz: Double, sampleRate: Int = 48_000): Float {
        if (isFlat || sampleRate <= 0) return 0f
        val amplitude = Math.pow(10.0, gainDb / 40.0)
        val omega = 2.0 * PI * centerHz / sampleRate
        val alpha = sin(omega) / (2.0 * Q)
        val b0 = 1.0 + alpha * amplitude
        val b1 = -2.0 * cos(omega)
        val b2 = 1.0 - alpha * amplitude
        val a0 = 1.0 + alpha / amplitude
        val a1 = -2.0 * cos(omega)
        val a2 = 1.0 - alpha / amplitude
        val w = 2.0 * PI * hz / sampleRate
        val cosW = cos(w)
        val sinW = sin(w)
        val cos2W = cos(2.0 * w)
        val sin2W = sin(2.0 * w)
        val numerator = hypot(
            b0 + b1 * cosW + b2 * cos2W,
            b1 * sinW + b2 * sin2W,
        )
        val denominator = hypot(
            a0 + a1 * cosW + a2 * cos2W,
            a1 * sinW + a2 * sin2W,
        )
        if (denominator <= 0.0) return 0f
        val db = 20.0 * kotlin.math.log10(numerator / denominator)
        return db.toFloat().coerceIn(-24f, 24f)
    }

    private companion object {
        /** Broad enough to be a colour rather than a whistle. */
        const val Q = 1.0
    }
}
