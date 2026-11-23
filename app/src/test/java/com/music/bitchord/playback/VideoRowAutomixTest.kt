package com.music.bitchord.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoRowAutomixTest {
    @Test
    fun audioConvertedFromAMusicVideoRowStillBlends() {
        // Video-origin, but playing audio: an ordinary AutoMix crossfade is allowed.
        assertTrue(VideoRowAutomix.mayBlend(currentIsVideo = false, nextIsVideo = false))
        // ...but never a loop mix, on either side.
        assertFalse(VideoRowAutomix.mayLoop(currentVideoOrigin = true, nextVideoOrigin = false))
        assertFalse(VideoRowAutomix.mayLoop(currentVideoOrigin = false, nextVideoOrigin = true))
    }

    @Test
    fun anItemPlayingVideoGetsNoAutomix() {
        assertFalse(VideoRowAutomix.mayBlend(currentIsVideo = true, nextIsVideo = false))
        assertFalse(VideoRowAutomix.mayBlend(currentIsVideo = false, nextIsVideo = true))
    }

    @Test
    fun ordinaryAudioLoopsAndBlends() {
        assertTrue(VideoRowAutomix.mayBlend(currentIsVideo = false, nextIsVideo = false))
        assertTrue(VideoRowAutomix.mayLoop(currentVideoOrigin = false, nextVideoOrigin = false))
    }
}
