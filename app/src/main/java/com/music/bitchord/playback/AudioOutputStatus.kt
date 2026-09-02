package com.music.bitchord.playback

import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.music.bitchord.data.settings.OutputPcmMode
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Live facts about the Android output route, kept separate from source-format
 * statistics. The framework owns the final mixer/DAC decision, so these fields
 * deliberately describe the selected device's advertised capabilities rather
 * than pretending that an app can guarantee bit-perfect delivery.
 */
object AudioOutputStatus {
    data class Snapshot(
        val sink: String = "AudioTrack",
        val requestedPcmMode: OutputPcmMode = OutputPcmMode.PCM_16,
        val deviceName: String = "System default",
        val sampleRatesHz: IntArray = IntArray(0),
        val encodings: IntArray = IntArray(0),
        val isUsb: Boolean = false,
    )

    val current = MutableStateFlow(Snapshot())

    fun publish(
        manager: AudioManager,
        requestedPcmMode: OutputPcmMode,
        preferred: AudioDeviceInfo?,
    ) {
        val device = preferred ?: manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.isSink }
        current.value = Snapshot(
            requestedPcmMode = requestedPcmMode,
            deviceName = device?.productName?.toString()?.ifBlank { null } ?: "System default",
            sampleRatesHz = device?.sampleRates ?: IntArray(0),
            encodings = device?.encodings ?: IntArray(0),
            isUsb = device?.type in setOf(
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_ACCESSORY,
            ),
        )
    }
}
