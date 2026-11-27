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

/** Software gain shared by all PCM formats entering the exclusive USB queue. */
internal object ExclusivePcmGain {
    /** Scales the first [count] samples; the rest is spare capacity of a pooled array. */
    fun apply(samples: FloatArray, gain: Float, count: Int = samples.size) {
        if (!gain.isFinite()) return
        val normalized = gain.coerceIn(0f, 1f)
        if (normalized >= 1f - 1e-6f) return
        for (index in 0 until minOf(count, samples.size)) {
            samples[index] = (samples[index] * normalized).coerceIn(-1f, 1f)
        }
    }
}
