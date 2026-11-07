package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.automixEligible
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which tracks Automix is allowed to touch.
 *
 * The rule that matters: it is about what is *playing*, not about where the
 * row came from. A music-video row converted to an audio-only stream is
 * video-origin but is audio now, so it plays and blends like any other
 * track; only actual video playback is excluded.
 */
class AutomixEligibilityTest {

    @Test
    fun plainAudioIsEligible() {
        assertTrue(automixEligible(isVideo = false, isVideoOrigin = false))
    }

    @Test
    fun convertedVideoToAudioIsEligible() {
        // The shape a video-to-audio conversion produces: still video-origin,
        // no longer video.
        val converted = Song(
            videoId = "v",
            title = "T",
            artist = "A",
            thumbnailUrl = null,
            isVideo = false,
            isVideoOrigin = true,
        )
        assertTrue(
            "a converted video-origin track is audio now and must take part",
            automixEligible(converted.isVideo, converted.isVideoOrigin),
        )
    }

    @Test
    fun actualVideoIsNotEligible() {
        val video = Song(
            videoId = "v",
            title = "T",
            artist = "A",
            thumbnailUrl = null,
            isVideo = true,
            isVideoOrigin = true,
        )
        assertFalse(
            "actual video playback has no beat grid and must be left alone",
            automixEligible(video.isVideo, video.isVideoOrigin),
        )
    }

    @Test
    fun videoOriginAloneNeverExcludes() {
        // The regression this pins: the origin flag used to be the gate, which
        // kept every converted track out of Automix for no reason at all.
        assertTrue(automixEligible(isVideo = false, isVideoOrigin = true))
        assertFalse(automixEligible(isVideo = true, isVideoOrigin = true))
        assertFalse(automixEligible(isVideo = true, isVideoOrigin = false))
    }
}
