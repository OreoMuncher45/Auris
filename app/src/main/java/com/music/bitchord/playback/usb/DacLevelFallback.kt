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
 * What a USB output does when writing the listener's level to the DAC's
 * Feature Unit fails: it keeps the hardware path and tries the level again,
 * spaced [RETRY_MS] apart so a DAC refusing writes doesn't put two control
 * transfers in front of every chunk the writer sends.
 *
 * It does not switch to software gain mid-stream. PCM already copied into
 * the queue, and the URBs already submitted, were scaled for the hardware
 * path (gain 1); putting the DAC back to its base level under them would
 * play them above the listener's level until newly scaled PCM arrived. And
 * a software gain kept over the DAC's last accepted level would leave a
 * faded level (the sleep fade rides on it) in place after a restore.
 */
internal object DacLevelFallback {
    const val RETRY_MS = 1_000L

    /**
     * The gain the writer applies to every chunk it sends while a refused
     * level is pending: the DAC is still at [dacAtDb] (its last accepted
     * level, or its own read level), and the listener asked for [targetDb].
     * Below 1 only when the DAC is louder than asked; a refused raise leaves
     * the output quieter than asked, which needs nothing. Applied as each
     * chunk is sent, so it covers the PCM already queued at gain 1 as well.
     */
    fun pendingAttenuation(targetDb: Double, dacAtDb: Double?): Float {
        if (dacAtDb == null || !targetDb.isFinite() || !dacAtDb.isFinite() || targetDb >= dacAtDb) return 1f
        return Math.pow(10.0, (targetDb - dacAtDb) / 20.0).toFloat().coerceIn(0f, 1f)
    }

    /** When to write the level again after a write the DAC refused at [nowMs]. */
    fun retryAt(nowMs: Long): Long = nowMs + RETRY_MS

    /**
     * Whether the pending level may be written at [nowMs]: once the retry
     * spacing is over, or straight away when the listener has asked for a
     * different level since the refusal ([levelChanged]). The spacing only
     * keeps a refused level from being re-sent on every chunk; a new one is
     * tried at once, so a further decrease (and its attenuation, if refused
     * too) never waits behind the old one's deadline.
     */
    fun mayRetry(retryAtMs: Long, nowMs: Long, levelChanged: Boolean = false): Boolean =
        levelChanged || nowMs >= retryAtMs
}
