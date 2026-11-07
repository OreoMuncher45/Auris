package com.music.bitchord.playback

/**
 * Whether Automix may analyse, cue and blend a track.
 *
 * The rule is about what is *playing*, not about where the row came from:
 *
 * - Actual video playback has no beat grid to measure, no tempo to match and
 *   nothing to fade, so it is excluded.
 * - A music-video row that has been converted to an audio-only stream is
 *   still video-origin, but it is audio now: it has a beat grid like any
 *   other track, and it takes part in Automix like any other track.
 *
 * The origin flag is therefore deliberately not consulted. It is provenance —
 * it says where the row came from, so the player can offer to switch back —
 * and using it as a capability would keep converted tracks out of Automix
 * for no reason at all.
 *
 * Kept in one place so the gate cannot drift between the two places that ask
 * it: the transition planner and the analyser that feeds it.
 */
@Suppress("UNUSED_PARAMETER")
internal fun automixEligible(isVideo: Boolean, isVideoOrigin: Boolean): Boolean = !isVideo
