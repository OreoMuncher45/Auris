package com.music.bitchord.desktop

/**
 * Windows' shared-mode WASAPI output.
 *
 * Java Sound's DirectSound bridge can claim that a USB endpoint accepts ordinary PCM and then
 * either reject the line or render silence when the Windows mix format is unusually high (for
 * example 384 kHz, 32-bit float). WASAPI shared mode accepts the decoder's normal rate and asks
 * the Windows audio engine to convert it to the endpoint's native mix format.
 */
internal object DesktopWindowsAudio {

    private val available: Boolean by lazy {
        DesktopPlatform.isWindows &&
            runCatching { DesktopAnalysisRuntime.loadNative(LIBRARY) }
                .onFailure { DesktopTrackLog.log("native Windows audio unavailable: ${it.message}") }
                .isSuccess
    }

    fun open(device: String, format: DesktopPcmFormat): Result<Int> = runCatching {
        check(available) { "native Windows audio is unavailable" }
        val bytes = nativeOpen(
            device,
            format.sampleRate,
            format.channels,
            format.bytesPerSample,
            format.isFloat,
        )
        check(bytes > 0) { nativeLastError().ifBlank { "WASAPI could not open the audio endpoint" } }
        bytes
    }

    fun write(samples: FloatArray, count: Int, gain: Float): Int =
        if (available) nativeWrite(samples, count, gain) else 0

    fun framesPlayed(): Long = if (available) nativeFramesPlayed() else 0L

    fun pause() {
        if (available) nativePause()
    }

    fun resume() {
        if (available) nativeResume()
    }

    fun flush() {
        if (available) nativeFlush()
    }

    fun drain() {
        if (available) nativeDrain()
    }

    fun close() {
        if (available) nativeClose()
    }

    fun deviceName(): String? =
        if (available) nativeDeviceName().takeIf(String::isNotBlank) else null

    @JvmStatic private external fun nativeOpen(
        device: String,
        sampleRate: Int,
        channels: Int,
        bytesPerSample: Int,
        floatingPoint: Boolean,
    ): Int

    @JvmStatic private external fun nativeWrite(samples: FloatArray, count: Int, gain: Float): Int
    @JvmStatic private external fun nativeFramesPlayed(): Long
    @JvmStatic private external fun nativePause()
    @JvmStatic private external fun nativeResume()
    @JvmStatic private external fun nativeFlush()
    @JvmStatic private external fun nativeDrain()
    @JvmStatic private external fun nativeClose()
    @JvmStatic private external fun nativeDeviceName(): String
    @JvmStatic private external fun nativeLastError(): String

    private const val LIBRARY = "bitchord_window"
}
