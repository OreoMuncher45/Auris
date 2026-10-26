package com.music.bitchord.playback

import com.music.bitchord.data.settings.OutputPcmMode

/**
 * Decides whether Media3 may open a PCM-float AudioTrack.
 *
 * Float output is not a quality switch that is safe on every Android route.
 * Some OEM speaker paths accept the AudioTrack and then convert it to PCM16 in
 * AudioFlinger; on affected Samsung FLAC decoders that combination can also
 * produce timestamp discontinuities and severely distorted output. Only an
 * explicitly selected external route that advertises PCM float is allowed.
 */
internal object AudioOutputPolicy {
    /** Backwards-compatible USB-specific check. */
    fun shouldUseFloatOutput(
        requestedMode: OutputPcmMode,
        isPreferredUsbRoute: Boolean,
        advertisesPcmFloat: Boolean,
    ): Boolean = requestedMode == OutputPcmMode.FLOAT_32 &&
        isPreferredUsbRoute &&
        advertisesPcmFloat

    /**
     * Route-aware float output decision.
     *
     * - Built-in phone speaker (PHONE) must remain capped at 16-bit to prevent OEM mixer
     *   issues and distortion on affected device paths.
     * - External routes (USB, Bluetooth, Wired, HDMI) are allowed to use Float32 output
     *   if requested and actively advertised by the route's AudioDeviceInfo encodings.
     */
    fun shouldUseFloatOutput(
        requestedMode: OutputPcmMode,
        routeKind: AudioRouting.Kind,
        advertisesPcmFloat: Boolean,
    ): Boolean {
        if (requestedMode != OutputPcmMode.FLOAT_32) return false
        if (routeKind == AudioRouting.Kind.PHONE) return false
        return advertisesPcmFloat
    }

    /**
     * The precision the path should aim for, before the route has a say.
     *
     * Bit-perfect asks for the most faithful representation the sink can open,
     * not merely whatever the precision menu holds. Media3's sink offers only
     * 16-bit and float, so leaving a hi-res source on the menu's PCM16 would
     * quantize it before the DAC ever saw it. The route still decides — the
     * built-in speaker stays capped at 16-bit below — but the request starts
     * at float so a route that can carry it is allowed to.
     */
    fun requestedPcmMode(mode: OutputPcmMode, bitPerfect: Boolean): OutputPcmMode =
        if (bitPerfect) OutputPcmMode.FLOAT_32 else mode

    /** Samsung's vendor FLAC decoder emits invalid timestamps with PCM float. */
    fun isUnsafeFloatFlacDecoder(name: String): Boolean {
        val normalized = name.lowercase()
        return normalized == "c2.sec.flac.decoder" ||
            (normalized.startsWith("omx.sec.") && normalized.contains("flac"))
    }
}
