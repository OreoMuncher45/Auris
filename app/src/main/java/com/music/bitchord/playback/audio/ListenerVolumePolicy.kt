package com.music.bitchord.playback.audio

import kotlin.math.pow

/**
 * Where the listener's volume is applied on the path that is playing.
 *
 * The answer changes what "bit-perfect" can mean: a gain applied to the
 * samples is a change to the samples, and a gain applied by the DAC after
 * the PCM is not. The readout in the audio pipeline names which one is
 * happening, because the listener's own slider is what decides it.
 */
enum class VolumeApplication {
    /**
     * Android's mixer attenuates, as it always has.
     *
     * The app touches nothing: the samples reach the mixer exactly as the
     * decoder produced them.
     */
    SYSTEM_MIXER,

    /**
     * The app scales the samples on their way out.
     *
     * What a bit-perfect route has to do for itself: the mixer's gain is
     * exactly what bypassing the mixer gives up, so without this the phone's
     * slider would move and the sound would not.
     */
    APP_SOFTWARE,

    /**
     * The DAC's own volume control attenuates, after the PCM.
     *
     * The samples on the wire are untouched; the level is turned down inside
     * the DAC, which is where a listener with a DAC expects it to happen.
     */
    DAC_HARDWARE,
}

/**
 * The listener's own volume, and what to do with it.
 *
 * ## The one rule
 *
 * The phone's slider is an **offset from the level the output already has**,
 * never an absolute level and never applied twice.
 *
 * Every path that the app has to attenuate on its own — because the system
 * mixer is out of the way — goes through here, so that the same slider means
 * the same thing on every one of them:
 *
 *  - **The mixer is in the path.** Android attenuates. The app does nothing
 *    at all: adding a gain of its own would cut the sound twice.
 *  - **The mixer is bypassed.** The app applies the level itself, as a
 *    sample gain, or as an offset on the DAC's own volume control.
 *
 * ## Why an offset and not a level
 *
 * A USB DAC has a knob. The knob is the listener's, and it is set where they
 * want it. Writing an absolute level into the DAC's volume control throws
 * that away — and it threw it away in the worst possible way, because
 * Android pins the media stream at maximum the moment a USB DAC attaches.
 * The absolute level was therefore always "maximum", whatever the slider
 * said, and a DAC that answered the app's control was left at full volume.
 *
 * So the DAC's own level is read first, and the slider's attenuation is
 * added to it. The knob stays where the listener put it, and the phone's
 * slider moves the sound down from there.
 */
object ListenerVolumePolicy {

    /**
     * The quietest the app's own curve goes, in dB.
     *
     * Android's own media-volume curve bottoms out around the same place, so
     * a slider that is halfway down sounds the same whether the mixer is
     * doing the cutting or the app is.
     */
    const val FLOOR_DB: Double = -62.0

    /**
     * The attenuation the listener's level asks for, in dB.
     *
     * Zero at full volume, [FLOOR_DB] at silence, and curved in between so
     * that the top of the slider is not all the interesting part. This is
     * the same curve the DAC's own control used to be given, moved here so
     * that every path shares it.
     */
    fun offsetDb(level: Float, floorDb: Double = FLOOR_DB): Double {
        val v = level.coerceIn(0f, 1f).toDouble()
        val floor = floorDb.coerceIn(-96.0, -24.0)
        if (v <= 0.0) return floor
        if (v >= 1.0) return 0.0
        return (floor * (1.0 - v.pow(0.75))).coerceIn(floor, 0.0)
    }

    /**
     * The same curve as a linear gain: what the samples are multiplied by.
     *
     * Used by every path that scales the samples itself, so that the slider
     * sounds the same on the exclusive USB route, on the framework's
     * bit-perfect route and on a phone with the mixer in the path.
     */
    fun perceptualGain(level: Float): Float {
        val v = level.coerceIn(0f, 1f)
        if (v <= 0f) return 0f
        if (v >= 1f) return 1f
        return 10.0.pow(offsetDb(v) / 20.0).toFloat().coerceIn(0f, 1f)
    }

    /**
     * The level to write to a DAC that has a level of its own.
     *
     * The DAC's own level plus the slider's attenuation, held inside the
     * DAC's range. At full volume this is the DAC's own level unchanged, so
     * the knob is left exactly where the listener put it.
     */
    fun dacTargetDb(
        baseDb: Double,
        level: Float,
        minDb: Double,
        maxDb: Double,
    ): Double {
        val low = minDb.coerceAtMost(maxDb)
        val high = maxDb.coerceAtLeast(low)
        return (baseDb + offsetDb(level)).coerceIn(low, high)
    }

    /**
     * Whether a reading of the system's media volume is Android's own doing.
     *
     * Android pins the media stream at maximum when a USB DAC attaches: the
     * DAC is expected to do the attenuating, so the phone's slider is
     * deliberately taken out of the way. The reading then says nothing about
     * what the listener chose, and adopting it would slam the level to full
     * on every attach.
     *
     * @param systemGain what the system reads.
     * @param usbAttached whether a USB audio output is on the bus.
     * @param appLevel the level the app is using.
     */
    fun systemReadIsPinned(
        systemGain: Float,
        usbAttached: Boolean,
        appLevel: Float,
    ): Boolean {
        if (!usbAttached) return false
        if (systemGain < 0.999f) return false
        return appLevel < 0.999f
    }
}
