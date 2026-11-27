/*
 * Copyright (C) 2026 LastWave Authors (https://github.com/Clash-Projects/LastWave-Native)
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This file is part of LastWave.
 *
 * LastWave is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * LastWave is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LastWave. If not, see <https://www.gnu.org/licenses/>.
 */
// Ported from LastWave-Native and modified for Auris/BitChord.

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
