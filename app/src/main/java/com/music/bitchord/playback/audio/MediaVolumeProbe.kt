package com.music.bitchord.playback.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * The system's own media-stream volume, and the two facts about it that
 * matter to the app: what it reads, and whether Android is pinning it.
 *
 * Split out of the two places that need it — the player's volume bar and
 * the exclusive USB session — so that both answer the same question the
 * same way. The decision itself lives in [ListenerVolumePolicy], which is
 * plain arithmetic and can be tested without a device.
 */
object MediaVolumeProbe {

    /** The media stream's level, 0..1. */
    fun levelOf(manager: AudioManager?): Float {
        val audio = manager ?: return 0f
        val max = runCatching { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
            .getOrDefault(0)
        if (max <= 0) return 0f
        val volume = runCatching { audio.getStreamVolume(AudioManager.STREAM_MUSIC) }
            .getOrDefault(max)
        return volume.coerceIn(0, max).toFloat() / max
    }

    /**
     * Whether a USB audio output is attached.
     *
     * The kind of output Android pins the media stream at maximum for: a
     * USB DAC is expected to attenuate on its own, and the phone's slider
     * is taken out of the way while one is attached.
     */
    fun usbOutputAttached(manager: AudioManager?): Boolean {
        val audio = manager ?: return false
        val devices = runCatching { audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
            .getOrNull() ?: return false
        return devices.any { device ->
            device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
        }
    }
}
