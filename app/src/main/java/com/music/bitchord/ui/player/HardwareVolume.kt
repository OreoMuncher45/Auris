package com.music.bitchord.ui.player

import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.AudioOutputStatus
import com.music.bitchord.playback.audio.VolumeApplication

/**
 * The hardware volume keys, and who has to answer them.
 *
 * ## The problem this solves
 *
 * Android's volume keys move the **system media stream**, and the system
 * mixer is what turns that into sound. On a bit-perfect route the mixer is
 * out of the way — the app scales the samples itself, or the DAC's own
 * control is turned down after the PCM — so the system's level is a number
 * that no longer describes anything the listener can hear.
 *
 * Worse, Android deliberately pins the media stream at maximum when a USB
 * DAC attaches, because it expects the DAC to do the attenuating. So on that
 * route the keys do nothing at all: the stream is already at the top, and
 * the app is the only thing that can still turn the sound down.
 *
 * So the keys are read here, in the app, and answered by whoever is actually
 * holding the level.
 *
 * ## When the app answers, and when Android does
 *
 * - **The app answers** when the level is being applied somewhere Android
 *   cannot see: the app's own gain, or the DAC's own control. That is the
 *   bit-perfect route, and the keys then move the level the listener is
 *   actually hearing.
 * - **Android answers** when the mixer is in the path, as it always has.
 *   The app stays out of it, and the system's own reading remains the truth.
 *
 * The decision is read from the live output status rather than guessed from
 * the settings: the same switch can end up on either path depending on the
 * device, the route and what is plugged in.
 */
object HardwareVolume {

    /**
     * One press of a volume key, in the system's own steps.
     *
     * Android's media stream moves in fifteen steps, and the app's bar is
     * read against that same scale, so a key press moves the bar by exactly
     * as much as the system panel would.
     */
    private const val STEP = 1f / 15f

    /**
     * Whether the app, rather than Android, is the thing that has to move the
     * level when a key is pressed.
     */
    fun appOwnsTheLevel(): Boolean {
        // With the mixer in the path, Android attenuates and the system's
        // level is the truth. Bit-perfect is the promise that the samples
        // reach the output unaltered, so it is also the promise that the
        // mixer is not in the path — and the case where the app has to take
        // the level itself.
        if (!AppSettings.bitPerfect.value) return false
        return when (AudioOutputStatus.current.value.volumeApplication) {
            VolumeApplication.APP_SOFTWARE,
            VolumeApplication.DAC_HARDWARE,
            -> true
            VolumeApplication.SYSTEM_MIXER -> false
        }
    }

    /**
     * The level a key press asks for, or null when Android should answer it.
     *
     * The level asked for is the app's own remembered level, not the
     * system's: on the route where the app holds the level, the system's
     * reading is either pinned at the top or describing a mixer that is not
     * in the path.
     */
    fun levelForKey(up: Boolean): Float? {
        if (!appOwnsTheLevel()) return null
        val current = AppSettings.playerVolume.value.coerceIn(0f, 1f)
        return (current + if (up) STEP else -STEP).coerceIn(0f, 1f)
    }
}
