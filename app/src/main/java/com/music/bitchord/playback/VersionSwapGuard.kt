package com.music.bitchord.playback

/**
 * Whether a version swap may still hand playback to its standby copy: the
 * same player is active and it is still on the queue entry the swap began on.
 * A swap whose track moved on while its standby aligned would otherwise take
 * playback back to the old track.
 */
internal fun versionSwapStillCurrent(
    activeIsPlaying: Boolean,
    indexAtStart: Int,
    indexNow: Int,
    mediaIdAtStart: String,
    mediaIdNow: String?,
): Boolean = activeIsPlaying && indexNow == indexAtStart && mediaIdNow == mediaIdAtStart
