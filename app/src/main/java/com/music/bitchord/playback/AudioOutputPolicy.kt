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
     * Whether bit-perfect mode may open a PCM-float AudioTrack on this route.
     *
     * Float is not a *preference* here, which is why this does not consult
     * [OutputPcmMode] the way [shouldUseFloatOutput] does. Media3's
     * `DefaultAudioSink` has exactly two linear-PCM output encodings: float,
     * and 16-bit. Its `configure` puts `ToInt16PcmAudioProcessor` in the chain
     * for every input encoding whenever float output is off — so on a 24-bit
     * source, "don't use float" does not mean "pass 24-bit through", it means
     * "downconvert to 16-bit". Float is the only container that carries a
     * 24-bit sample to AudioTrack intact (int24 fits exactly in float32's
     * 24-bit significand), so bit-perfect mode has to ask for it.
     *
     * The route guard from [shouldUseFloatOutput] still applies, and for the
     * same reason: a built-in speaker path that accepts a float AudioTrack and
     * then converts it in AudioFlinger produces distortion on affected OEM
     * devices. A phone speaker therefore gets 16-bit, which is bit-exact for a
     * 16-bit source and honestly reported as inexact for anything above it.
     */
    fun allowsFloatForBitPerfect(
        routeKind: AudioRouting.Kind,
        advertisesPcmFloat: Boolean,
    ): Boolean = routeKind != AudioRouting.Kind.PHONE && advertisesPcmFloat

    /** Samsung's vendor FLAC decoder emits invalid timestamps with PCM float. */
    fun isUnsafeFloatFlacDecoder(name: String): Boolean {
        val normalized = name.lowercase()
        return normalized == "c2.sec.flac.decoder" ||
            (normalized.startsWith("omx.sec.") && normalized.contains("flac"))
    }
}
