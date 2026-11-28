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

    /** Whether the level pending since a refused write may be tried at [nowMs]. */
    fun mayRetry(retryAtMs: Long, nowMs: Long): Boolean = nowMs >= retryAtMs
}
