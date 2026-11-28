package com.music.bitchord.playback.loopmix

import androidx.media3.common.Player

/**
 * Whether the queue still has the song a loop mix's decks were built for as
 * its next entry. Checked while the decks build, before the takeover and after
 * it until the handoff.
 */
internal object LoopNextEntry {
    /**
     * True while [liveNext] is the very entry [armed] holds, playing the same
     * stream or file ([samePlayable]: a finished download replaces an entry's
     * stream in place, and the decks were cut from the old one), and Repeat One
     * isn't holding the playing song: the player's next index ignores it, but
     * the song repeats instead of moving on.
     */
    fun <T : Any> stillArmed(repeatMode: Int, liveNext: T?, armed: T?, samePlayable: (T, T) -> Boolean): Boolean =
        repeatMode != Player.REPEAT_MODE_ONE && liveNext != null && armed != null && samePlayable(armed, liveNext)
}
