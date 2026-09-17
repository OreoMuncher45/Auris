/*
 * Copyright (C) 2026 BitChord Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.music.bitchord.playback.audio.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.reflect.Method

/**
 * Realtime telemetry model capturing actual negotiated Bluetooth A2DP codec and transport state.
 */
data class BluetoothTelemetry(
    val isConnected: Boolean = false,
    val deviceName: String? = null,
    val codecName: String = "Unknown",
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val bitrateLabel: String = "Not exposed by Android",
    val mode: String? = null,
    val isAuthoritative: Boolean = false,
    val lastUpdatedMs: Long = 0L,
) {
    val isHighRes: Boolean
        get() = (sampleRateHz != null && sampleRateHz > 48000) || (bitDepth != null && bitDepth > 16)

    fun formattedSummary(): String = buildString {
        if (!isConnected) {
            append("Disconnected")
            return@buildString
        }
        append(codecName)
        if (bitDepth != null) append(" / $bitDepth-bit")
        if (sampleRateHz != null) append(" / $sampleRateHz Hz")
        if (bitrateLabel != "Not exposed by Android" && bitrateLabel.isNotBlank()) {
            append(" @ $bitrateLabel")
        }
    }
}

/**
 * Authoritative, non-blocking tracker monitoring Bluetooth A2DP codec and configuration changes.
 *
 * Features:
 * - Listens for [BluetoothA2dp.ACTION_CODEC_CONFIG_CHANGED] broadcasts for realtime updates.
 * - Queries active A2DP profile state using safe reflection across all Android versions.
 * - Extracts actual codec (LDAC, LHDC, aptX HD, aptX, AAC, SBC), sample rate, and bit depth.
 * - For LDAC, inspects vendor-specific bits for exact operational bitrate (990/660/330 kbps or ABR).
 * - NEVER invents, estimates, or infers bitrate from the codec name alone.
 * - Fully isolated from the audio callback thread: state is updated asynchronously and read atomically.
 */
class BluetoothAudioTracker(private val context: Context) {

    private val _telemetry = MutableStateFlow(BluetoothTelemetry())
    val telemetry: StateFlow<BluetoothTelemetry> = _telemetry.asStateFlow()

    private var a2dpProfile: BluetoothA2dp? = null
    private var isReceiverRegistered: Boolean = false

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.A2DP) {
                a2dpProfile = proxy as? BluetoothA2dp
                refreshCurrentDevice()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) {
                a2dpProfile = null
                _telemetry.value = BluetoothTelemetry()
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                ACTION_CODEC_CONFIG_CHANGED -> {
                    parseCodecStatusIntent(intent)
                }
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                    if (state == BluetoothProfile.STATE_CONNECTED) {
                        refreshCurrentDevice()
                    } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                        _telemetry.value = BluetoothTelemetry()
                    }
                }
                BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    refreshCurrentDevice()
                }
            }
        }
    }

    /**
     * Starts monitoring Bluetooth codec and connection broadcasts.
     */
    fun start() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(ACTION_CODEC_CONFIG_CHANGED)
                addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    context.registerReceiver(receiver, filter)
                }
                isReceiverRegistered = true
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to register Bluetooth receiver", e)
            }
        }

        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            adapter?.getProfileProxy(context, profileListener, BluetoothProfile.A2DP)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to bind A2DP profile proxy", e)
        }
    }

    /**
     * Stops monitoring and releases profile proxies.
     */
    fun stop() {
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Throwable) {
            }
            isReceiverRegistered = false
        }
        val proxy = a2dpProfile
        if (proxy != null) {
            try {
                BluetoothAdapter.getDefaultAdapter()?.closeProfileProxy(BluetoothProfile.A2DP, proxy)
            } catch (_: Throwable) {
            }
            a2dpProfile = null
        }
    }

    /**
     * Asynchronously queries the active A2DP device's codec status via reflection.
     */
    @SuppressLint("MissingPermission")
    fun refreshCurrentDevice() {
        val a2dp = a2dpProfile ?: return
        try {
            val connectedDevices = a2dp.connectedDevices
            val activeDevice = connectedDevices.firstOrNull()
            if (activeDevice == null) {
                _telemetry.value = BluetoothTelemetry()
                return
            }

            val deviceName = try {
                activeDevice.name ?: activeDevice.alias ?: "Bluetooth Device"
            } catch (_: Throwable) {
                "Bluetooth Device"
            }

            // Reflection: BluetoothA2dp.getCodecStatus(BluetoothDevice)
            val method: Method? = try {
                a2dp.javaClass.getMethod("getCodecStatus", BluetoothDevice::class.java)
            } catch (_: Throwable) {
                null
            }

            val codecStatus = method?.invoke(a2dp, activeDevice)
            if (codecStatus != null) {
                val parsed = parseCodecStatusObject(codecStatus, deviceName)
                if (parsed != null) {
                    _telemetry.value = parsed
                    return
                }
            }

            _telemetry.value = BluetoothTelemetry(
                isConnected = true,
                deviceName = deviceName,
                codecName = "Bluetooth A2DP",
                bitrateLabel = "Not exposed by Android",
                isAuthoritative = false,
                lastUpdatedMs = SystemClock.elapsedRealtime(),
            )
        } catch (e: Throwable) {
            Log.d(TAG, "Error refreshing A2DP device status: ${e.message}")
        }
    }

    private fun parseCodecStatusIntent(intent: Intent) {
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

        val deviceName = try {
            device?.name ?: _telemetry.value.deviceName ?: "Bluetooth Device"
        } catch (_: Throwable) {
            _telemetry.value.deviceName ?: "Bluetooth Device"
        }

        val codecStatus = intent.extras?.get(EXTRA_CODEC_STATUS)
        if (codecStatus != null) {
            val parsed = parseCodecStatusObject(codecStatus, deviceName)
            if (parsed != null) {
                _telemetry.value = parsed
                return
            }
        }
        refreshCurrentDevice()
    }

    private fun parseCodecStatusObject(codecStatus: Any, deviceName: String): BluetoothTelemetry? {
        return try {
            val getCodecConfigMethod = codecStatus.javaClass.getMethod("getCodecConfig")
            val codecConfig = getCodecConfigMethod.invoke(codecStatus) ?: return null
            parseCodecConfigObject(codecConfig, deviceName)
        } catch (e: Throwable) {
            Log.d(TAG, "Failed to parse BluetoothCodecStatus: ${e.message}")
            null
        }
    }

    internal fun parseCodecConfigObject(codecConfig: Any, deviceName: String): BluetoothTelemetry {
        val clazz = codecConfig.javaClass

        val codecType = try {
            clazz.getMethod("getCodecType").invoke(codecConfig) as? Int ?: -1
        } catch (_: Throwable) {
            -1
        }

        val sampleRateMask = try {
            clazz.getMethod("getSampleRate").invoke(codecConfig) as? Int ?: 0
        } catch (_: Throwable) {
            0
        }

        val bitsMask = try {
            clazz.getMethod("getBitsPerSample").invoke(codecConfig) as? Int ?: 0
        } catch (_: Throwable) {
            0
        }

        val codecSpecific1 = try {
            val method = clazz.getMethod("getCodecSpecific1")
            (method.invoke(codecConfig) as? Number)?.toLong() ?: 0L
        } catch (_: Throwable) {
            0L
        }

        val codecName = try {
            // Android 14+ public or internal method
            val nameMethod = clazz.getMethod("getCodecName")
            (nameMethod.invoke(codecConfig) as? String)?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        } ?: mapCodecType(codecType)

        val sampleRateHz = mapSampleRate(sampleRateMask)
        val bitDepth = mapBitDepth(bitsMask)

        val (bitrateLabel, mode) = when (codecType) {
            SOURCE_CODEC_TYPE_LDAC -> parseLdacBitrate(codecSpecific1)
            else -> Pair("Not exposed by Android", null)
        }

        return BluetoothTelemetry(
            isConnected = true,
            deviceName = deviceName,
            codecName = codecName,
            sampleRateHz = sampleRateHz,
            bitDepth = bitDepth,
            bitrateLabel = bitrateLabel,
            mode = mode,
            isAuthoritative = true,
            lastUpdatedMs = SystemClock.elapsedRealtime(),
        )
    }

    companion object {
        private const val TAG = "BluetoothAudioTracker"

        const val ACTION_CODEC_CONFIG_CHANGED = "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED"
        const val EXTRA_CODEC_STATUS = "android.bluetooth.extra.CODEC_STATUS"

        // BluetoothCodecConfig Codec Types
        const val SOURCE_CODEC_TYPE_SBC = 0
        const val SOURCE_CODEC_TYPE_AAC = 1
        const val SOURCE_CODEC_TYPE_APTX = 2
        const val SOURCE_CODEC_TYPE_APTX_HD = 3
        const val SOURCE_CODEC_TYPE_LDAC = 4
        const val SOURCE_CODEC_TYPE_LC3 = 5
        const val SOURCE_CODEC_TYPE_OPUS = 6

        fun mapCodecType(type: Int): String = when (type) {
            SOURCE_CODEC_TYPE_SBC -> "SBC"
            SOURCE_CODEC_TYPE_AAC -> "AAC"
            SOURCE_CODEC_TYPE_APTX -> "aptX"
            SOURCE_CODEC_TYPE_APTX_HD -> "aptX HD"
            SOURCE_CODEC_TYPE_LDAC -> "LDAC"
            SOURCE_CODEC_TYPE_LC3 -> "LC3"
            SOURCE_CODEC_TYPE_OPUS -> "Opus"
            else -> if (type > 6) "Vendor Codec ($type)" else "Unknown"
        }

        fun mapSampleRate(mask: Int): Int? = when {
            (mask and (1 shl 5)) != 0 -> 192000
            (mask and (1 shl 4)) != 0 -> 176400
            (mask and (1 shl 3)) != 0 -> 96000
            (mask and (1 shl 2)) != 0 -> 88200
            (mask and (1 shl 1)) != 0 -> 48000
            (mask and (1 shl 0)) != 0 -> 44100
            mask in setOf(44100, 48000, 88200, 96000, 176400, 192000) -> mask
            else -> null
        }

        fun mapBitDepth(mask: Int): Int? = when {
            (mask and (1 shl 2)) != 0 -> 32
            (mask and (1 shl 1)) != 0 -> 24
            (mask and (1 shl 0)) != 0 -> 16
            mask in setOf(16, 24, 32) -> mask
            else -> null
        }

        /**
         * Parses LDAC vendor-specific parameter 1 for operational bitrate mode.
         *
         * AOSP LDAC values (from a2dp_vendor_ldac_constants.h):
         * 1000 = High Quality (990 kbps)
         * 1001 = Standard (660 kbps)
         * 1002 = Connection Priority (330 kbps)
         * 1003 = Adaptive Bit Rate (ABR)
         */
        fun parseLdacBitrate(param1: Long): Pair<String, String?> = when (param1) {
            1000L -> Pair("990 kbps (High Quality)", "Fixed")
            1001L -> Pair("660 kbps (Standard)", "Fixed")
            1002L -> Pair("330 kbps (Connection)", "Fixed")
            1003L -> Pair("Adaptive (ABR)", "Adaptive")
            else -> Pair("Not exposed by Android", null)
        }
    }
}
