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

    /**
     * Whether a late handoff (the outgoing player ran on into the next track
     * itself) may still move the session onto the standby. Not onto a standby
     * that failed or went idle: the player that advanced works, and muting it
     * for a broken one leaves silence. Nor when the natural advance paused
     * playback (the sleep timer's "after this song" lands on that advance):
     * the still-playing standby would undo the pause. The natural advance
     * stands in both cases.
     */
    fun lateHandoffAllowed(standbyFailed: Boolean, standbyIdle: Boolean, advancedPlayerPlaying: Boolean): Boolean =
        !standbyFailed && !standbyIdle && advancedPlayerPlaying
}
