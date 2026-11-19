package com.music.bitchord.playback.usb

/**
 * What the USB monitor knows about the attached DAC. Shape kept identical to
 * LastWave's, so the ported monitor stays a direct copy.
 */
data class UsbDacInfo(
    val name: String,
    val vendorId: Int = -1,
    val productId: Int = -1,
    /** Sample rates from the platform audio descriptor; empty = undisclosed. */
    val sampleRatesHz: List<Int> = emptyList(),
    val channelCounts: List<Int> = emptyList(),
    /** Direct USB access granted via [UsbDacMonitor.requestPermission]. */
    val usbPermissionGranted: Boolean = false,
    /** True when a USB audio peripheral exists on the USB bus itself. */
    val hasUsbPeripheral: Boolean = false,
    /** [android.media.AudioDeviceInfo.getId] used for preferred-device routing. */
    val deviceId: Int = -1,
)
