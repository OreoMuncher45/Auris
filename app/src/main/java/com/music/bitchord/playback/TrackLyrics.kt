package com.music.bitchord.playback

import com.music.bitchord.data.lyrics.LyricLine

/**
 * The synced lines the lock screen and notification follow, and the track
 * they were found for. A new track's lookup drops the previous track's lines
 * at once, so the ticker never reads old lines at the new track's position
 * while the lookup runs. A lookup that finishes for a track that is no
 * longer current publishes nothing.
 */
internal class TrackLyrics {
    var lines: List<LyricLine>? = null
        private set
    private var owner: String? = null

    /** A lookup starts for [trackId]. True when another track's lines were dropped. */
    fun lookUp(trackId: String): Boolean {
        if (owner == trackId) return false
        owner = trackId
        val dropped = lines != null
        lines = null
        return dropped
    }

    /** The lookup for [trackId] found [found]; false, publishing nothing, when another track owns the lines now. */
    fun publish(trackId: String, found: List<LyricLine>?): Boolean {
        if (owner != trackId) return false
        lines = found
        return true
    }

    fun clear() {
        owner = null
        lines = null
    }
}
