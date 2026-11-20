/*
 * Copyright (C) 2026 BitChord Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.music.bitchord.playback.audio

/**
 * Technical Architecture Evaluation: AAudio as a Potential Native Transport for BitChord.
 *
 * OBJECTIVE:
 * Determine whether integrating an AAudio C/C++ native audio stream provides measurable
 * audio fidelity or architectural advantages over Android's [android.media.AudioTrack]
 * direct/offload pipeline and Media3's [androidx.media3.exoplayer.audio.DefaultAudioSink].
 *
 * TECHNICAL EVALUATION:
 *
 * 1. MMAP and Exclusive Mode Reality on Android:
 *    - AAudio offers two sharing modes: `AAUDIO_SHARING_MODE_EXCLUSIVE` and `AAUDIO_SHARING_MODE_SHARED`.
 *    - Exclusive mode bypasses the Android AudioFlinger software mixer ONLY if the audio HAL and
 *      underlying kernel driver support MMAP (Memory-Mapped Ring Buffer).
 *    - On Android USB audio paths (`AUDIO_DEVICE_OUT_USB_HEADSET` / `AUDIO_DEVICE_OUT_USB_DEVICE`):
 *      The Linux kernel USB audio driver (`snd-usb-audio`) uses standard isochronous USB transfer
 *      endpoints and DOES NOT support MMAP exclusive mode.
 *    - Consequently, when an AAudio stream requests EXCLUSIVE mode on a USB audio device, the Android
 *      audio server silently falls back to `AAUDIO_SHARING_MODE_SHARED`.
 *
 * 2. Shared Mode Route Equivalent:
 *    - In SHARED mode, AAudio feeds into the exact same AudioFlinger mixer threads (`AudioOut_1D`,
 *      `deep_buffer`, or `hifi_playback`) that [android.media.AudioTrack] uses.
 *    - There is zero hardware fidelity difference between AAudio SHARED mode and AudioTrack for PCM
 *      playback; both routes are mixed by AudioFlinger at the HAL's active sample rate (e.g. 48 kHz).
 *
 * 3. Media3 & Platform Integration Trade-offs:
 *    - [androidx.media3.exoplayer.audio.DefaultAudioSink] provides mature, battle-tested audio
 *      clock synchronization, timestamp smoothing, latency compensation, dynamic volume shaping,
 *      audio focus handling, and seamless crossfade blending.
 *    - Bypassing DefaultAudioSink with a custom JNI/C++ AAudio engine would require re-implementing
 *      all position tracking, clock recovery, and gapless transition logic from scratch in C++.
 *    - In addition, loading C++ native libraries (`libaaudio.so`) introduces ABI complexity, JNI
 *      crossing overhead, potential memory leaks, and OEM-specific driver crash risks without
 *      delivering any measurable output fidelity improvement.
 *
 * 4. Android Direct Playback Superiority:
 *    - Beginning in Android 13 (API 33), Android provides official Direct Playback APIs:
 *      [android.media.AudioManager.getDirectPlaybackSupport] and [android.media.AudioManager.getDirectProfilesForAttributes].
 *    - When the device HAL exposes a direct hardware path (e.g., `AUDIO_OUTPUT_FLAG_DIRECT` or
 *      `hifi_playback`), [android.media.AudioTrack] configured with matching attributes and format
 *      is automatically assigned to that direct, unmixed profile by AudioPolicyManager.
 *
 * ARCHITECTURAL DECISION:
 * - AAudio is REJECTED as the transport for USB DACs and as a general playback
 *   transport, for the reasons above.
 * - The one exception is a built-in DAC (a DAP's headphone or line output),
 *   whose HAL does offer MMAP exclusive streams: there an AAudio exclusive
 *   stream is the only mixer-free path, and it is opt-in as the "Built-in
 *   DAC" bit-perfect path ([com.music.bitchord.playback.audio.bitperfect.AAudioExclusiveSession]).
 *   A stream Android returns as shared is refused, never used.
 * - BitChord achieves maximum output quality and hardware stability by combining:
 *   1. Canonical Float32 DSP in [PrecisionAudioSink].
 *   2. Dynamic runtime direct-playback detection via [DirectAudioProbe].
 *   3. Authoritative route negotiation via [OutputNegotiator].
 *   4. Rock-solid, glitch-free output via [androidx.media3.exoplayer.audio.DefaultAudioSink] and [android.media.AudioTrack].
 *   5. Direct USB through the exclusive usbdevfs session in [com.music.bitchord.playback.usb.ExclusiveUsbOutput].
 */
object AAudioEvaluation {
    const val IS_INTEGRATED: Boolean = false
    const val RATIONALE: String =
        "AAudio falls back to SHARED mode on USB paths without MMAP, routing to the same AudioFlinger mixer as AudioTrack. Direct AudioTrack provides equal or superior fidelity with complete platform stability."
}
