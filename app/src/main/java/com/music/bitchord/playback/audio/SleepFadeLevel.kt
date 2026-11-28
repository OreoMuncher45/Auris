package com.music.bitchord.playback.audio

/**
 * The sleep timer's fade, as a gain the sinks apply to the samples while
 * bit-perfect is on: there the player's volume is held at unity, so turning
 * the player down does nothing, and the music stopped at full level instead
 * of fading. 1 at all other times.
 *
 * Only under bit-perfect: elsewhere the player's own volume carries the fade,
 * and applying this too would square it. The listener's saved level is never
 * touched; this multiplies whatever gain the route applies.
 */
internal object SleepFadeLevel {
    @Volatile
    var gain: Float = 1f

    /** The fade's share of a block's gain on a route that [bitPerfect] is or isn't on. */
    fun sampleGain(bitPerfect: Boolean, fade: Float = gain): Float =
        if (bitPerfect) fade.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f else 1f
}
