package com.music.bitchord.playback

import androidx.media3.common.C

/**
 * Where a skip back or forward by [offsetMs] lands, measured from [positionMs]
 * (what the listener hears: during a loop mix, the mix's position rather than
 * the decoder's), kept within the song as Media3's own seekBack and
 * seekForward keep it.
 */
internal object SeekOffset {
    fun target(positionMs: Long, offsetMs: Long, durationMs: Long): Long {
        var target = positionMs + offsetMs
        if (durationMs != C.TIME_UNSET) target = minOf(target, durationMs)
        return maxOf(target, 0L)
    }
}
