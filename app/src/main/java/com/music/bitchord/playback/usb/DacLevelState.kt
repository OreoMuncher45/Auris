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
// Derived from LastWave-Native's ExclusiveUsbOutput (ported and modified for Auris/BitChord).

package com.music.bitchord.playback.usb

/**
 * A USB output's hardware-level writes and what the writer does while one is
 * refused, apart from the USB plumbing so the path can be driven in a test:
 * [setDb] is the Feature Unit write, [nowMs] the clock.
 *
 * A refused write keeps the hardware path ([DacLevelFallback]): the level
 * stays pending and is written again once [mayWrite] says so, straight away
 * for a level the listener changed since the refusal. Meanwhile, if the DAC
 * is still louder than asked, [attenuate] scales every chunk the writer sends
 * by the difference, queued ones included.
 */
internal class DacLevelState(
    private val setDb: (Double) -> Boolean,
    private val nowMs: () -> Long,
) {
    /** The gain the writer applies to each chunk while a lower level is refused; 1 otherwise. */
    @Volatile var pendingAttenuation = 1f
        private set

    @Volatile private var retryAtMs = 0L

    /** The listener level the DAC last refused; a different one is written at once. */
    @Volatile private var refusedLevel = Float.NaN

    /**
     * Writes [targetDb] for listener [level]. True when the DAC took it; false
     * when refused, leaving the level pending and the attenuation set from the
     * DAC's present level [dacAtDb].
     */
    fun write(level: Float, targetDb: Double, dacAtDb: Double?): Boolean {
        if (setDb(targetDb)) {
            settled()
            return true
        }
        pendingAttenuation = DacLevelFallback.pendingAttenuation(targetDb, dacAtDb)
        refusedLevel = level
        retryAtMs = DacLevelFallback.retryAt(nowMs())
        return false
    }

    /** Whether a pending write for listener [level] may go now. */
    fun mayWrite(level: Float): Boolean =
        DacLevelFallback.mayRetry(retryAtMs, nowMs(), levelChanged = level != refusedLevel)

    /** Nothing pending any more: written, already there, or off the hardware path. */
    fun settled() {
        pendingAttenuation = 1f
        refusedLevel = Float.NaN
    }

    /** Scales one float chunk the writer is about to send; a no-op unless a lower level is refused. */
    fun attenuate(samples: FloatArray, count: Int) {
        val gain = pendingAttenuation
        if (gain < 1f - 1e-6f) ExclusivePcmGain.apply(samples, gain, count)
    }
}
