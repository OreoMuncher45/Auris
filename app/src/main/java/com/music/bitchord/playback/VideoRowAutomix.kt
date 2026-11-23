package com.music.bitchord.playback

/**
 * What AutoMix may do with a pair that involves a music-video row.
 *
 * An item playing video gets no AutoMix at all. Audio converted from a
 * music-video row ([isVideoOrigin] without [isVideo]) is audio, so it is
 * analysed, cued and blended like any other, as Auris always did. The loop
 * mix is the one exception: it loops and re-cuts bars of the song, and a
 * converted music video's edit (intros, spoken parts, a cut that differs
 * from the album track) is exactly what that goes wrong on.
 */
internal object VideoRowAutomix {
    fun mayBlend(currentIsVideo: Boolean, nextIsVideo: Boolean): Boolean = !currentIsVideo && !nextIsVideo

    fun mayLoop(currentVideoOrigin: Boolean, nextVideoOrigin: Boolean): Boolean =
        !currentVideoOrigin && !nextVideoOrigin
}
