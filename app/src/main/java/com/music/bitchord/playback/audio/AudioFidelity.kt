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

import com.music.bitchord.playback.AudioRouting

/**
 * A single 0–100 number for how much of the source survives the playing path,
 * together with every point it deducted and why.
 *
 * This is a score for *this app's path*, not for the music or the DAC. It
 * starts at 100 and subtracts each stage that demonstrably alters or fails to
 * carry the decoded samples: the platform mixer, a lossy re-encode downstream,
 * an output encoding narrower than the source, a rate change, and each DSP
 * stage that is actually running. Every deduction is returned, so the readout
 * can say "87 because the system mixer is in the path and the EQ is on"
 * instead of showing a number nobody can act on.
 *
 * The route caps mirror the shape used by LastWave's fidelity scale, but the
 * inputs here come from this app's own measurements: [outputExact] is the
 * sink's verdict on the encoding trip, and [exclusiveUsbActive] /
 * [directPlaybackActual] / [bitPerfectMixerActive] distinguish a path that
 * leaves the mixer out from one that only asks it nicely.
 */
object AudioFidelity {

    /** Everything the score is allowed to look at. No inferred values. */
    data class Inputs(
        val routeKind: AudioRouting.Kind,
        val exclusiveUsbActive: Boolean = false,
        val directPlaybackActual: Boolean = false,
        val bitPerfectMixerActive: Boolean = false,
        /** Whether the output encoding carries the decoder's samples intact. */
        val outputExact: Boolean = false,
        /** False when the stream is a compressed bitstream the DSP never sees. */
        val dspAvailable: Boolean = true,
        /** True when the listener deliberately took every DSP stage out. */
        val dspBypassed: Boolean = false,
        val loudnessActive: Boolean = false,
        val equalizerActive: Boolean = false,
        val spatialActive: Boolean = false,
        val vocalCutActive: Boolean = false,
        val jamesDspActive: Boolean = false,
        /** DSD has no native output path here, so it is always converted to PCM. */
        val dsd: Boolean = false,
        val sourceRateHz: Int? = null,
        val outputRateHz: Int? = null,
    )

    data class Deduction(val label: String, val points: Int)

    data class Assessment(
        val score: Int,
        val grade: String,
        /** Names of the DSP stages genuinely running, in chain order. */
        val activeEffects: List<String>,
        /** False for a bitstream the DSP chain never sees. */
        val effectsAvailable: Boolean,
        /** True when effects are out of the path by request, not by being off. */
        val effectsBypassed: Boolean,
        val deductions: List<Deduction>,
    )

    private const val MIXED_PATH_POINTS = 12
    private const val DIRECT_PATH_POINTS = 6
    private const val BIT_PERFECT_MIXER_POINTS = 3
    private const val PHONE_PATH_POINTS = 32
    private const val BLUETOOTH_CODEC_POINTS = 12
    private const val NARROW_ENCODING_POINTS = 12
    private const val RESAMPLE_POINTS = 8
    private const val DSD_CONVERSION_POINTS = 6

    private const val LOUDNESS_POINTS = 4
    private const val EQUALIZER_POINTS = 8
    private const val SPATIAL_POINTS = 8
    private const val VOCAL_CUT_POINTS = 8
    private const val JAMES_DSP_POINTS = 8

    fun assess(inputs: Inputs): Assessment {
        val deductions = mutableListOf<Deduction>()

        when {
            inputs.exclusiveUsbActive -> Unit
            inputs.directPlaybackActual -> deductions +=
                Deduction("Direct AudioTrack path (device clock not independently verified)", DIRECT_PATH_POINTS)
            inputs.bitPerfectMixerActive -> deductions +=
                Deduction("Platform mixer asked to stay bit-perfect", BIT_PERFECT_MIXER_POINTS)
            else -> deductions +=
                Deduction("System mixer is in the path", MIXED_PATH_POINTS)
        }

        if (inputs.routeKind == AudioRouting.Kind.PHONE) {
            deductions += Deduction("Built-in speaker path", PHONE_PATH_POINTS)
        }
        if (inputs.routeKind == AudioRouting.Kind.BLUETOOTH) {
            deductions += Deduction("Bluetooth re-encodes the stream", BLUETOOTH_CODEC_POINTS)
        }
        if (inputs.dspAvailable && !inputs.outputExact) {
            deductions += Deduction("Output cannot carry the source encoding", NARROW_ENCODING_POINTS)
        }
        if (inputs.dsd) {
            deductions += Deduction("DSD converted to PCM", DSD_CONVERSION_POINTS)
        } else {
            val source = inputs.sourceRateHz?.takeIf { it > 0 }
            val output = inputs.outputRateHz?.takeIf { it > 0 }
            if (source != null && output != null && source != output) {
                deductions += Deduction(
                    "Resampled ${rateText(source)} -> ${rateText(output)}",
                    RESAMPLE_POINTS,
                )
            }
        }

        // A bypassed chain runs nothing, whatever the switches still say.
        val running = if (!inputs.dspAvailable || inputs.dspBypassed) {
            emptyList()
        } else {
            buildList {
                if (inputs.loudnessActive) {
                    add("Loudness normalization" to LOUDNESS_POINTS)
                }
                if (inputs.equalizerActive) {
                    add("Equalizer" to EQUALIZER_POINTS)
                }
                if (inputs.spatialActive) {
                    add("Spatial audio" to SPATIAL_POINTS)
                }
                if (inputs.vocalCutActive) {
                    add("Vocal cut" to VOCAL_CUT_POINTS)
                }
                if (inputs.jamesDspActive) {
                    add("DSP" to JAMES_DSP_POINTS)
                }
            }
        }
        running.forEach { (label, points) -> deductions += Deduction(label, points) }

        val lost = deductions.sumOf { it.points }
        val score = (100 - lost).coerceIn(0, 100)
        return Assessment(
            score = score,
            grade = gradeOf(score),
            activeEffects = running.map { it.first },
            effectsAvailable = inputs.dspAvailable,
            effectsBypassed = inputs.dspBypassed,
            deductions = deductions,
        )
    }

    private fun gradeOf(score: Int): String = when {
        score >= 98 -> "Bit-perfect"
        score >= 90 -> "Reference"
        score >= 72 -> "High fidelity"
        score >= 55 -> "Mixed path"
        score >= 35 -> "Processed"
        else -> "Heavily processed"
    }

    private fun rateText(hz: Int): String = when {
        hz % 1000 == 0 -> "${hz / 1000} kHz"
        else -> "%.1f kHz".format(java.util.Locale.ROOT, hz / 1000.0)
    }
}
