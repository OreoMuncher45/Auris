package com.music.bitchord.playback

/**
 * Whether a crossfade's handoff books the incoming track as the one that
 * became current: scrobble, history, "sleep after this song", read-ahead.
 *
 * An ordinary handoff does, because the incoming track started as the first
 * item of a player nothing was listening to. A late one does not: the
 * outgoing player ran on into the track itself, and the service's listener
 * booked that natural advance as it happened, so a second booking would
 * submit the new song as a finished listen moments after it started.
 */
internal object HandoffBookkeeping {
    fun booksTrackChange(late: Boolean): Boolean = !late
}
