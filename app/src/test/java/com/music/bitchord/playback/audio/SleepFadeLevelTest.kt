package com.music.bitchord.playback.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class SleepFadeLevelTest {
    @Test
    fun onlyABitPerfectRouteTakesTheFadeOnItsSamples() {
        assertEquals(0.25f, SleepFadeLevel.sampleGain(bitPerfect = true, fade = 0.25f))
        assertEquals(1f, SleepFadeLevel.sampleGain(bitPerfect = false, fade = 0.25f))
    }

    @Test
    fun aGainOutOfRangeNeverBoostsOrBreaksTheSamples() {
        assertEquals(1f, SleepFadeLevel.sampleGain(bitPerfect = true, fade = 3f))
        assertEquals(0f, SleepFadeLevel.sampleGain(bitPerfect = true, fade = -1f))
        assertEquals(1f, SleepFadeLevel.sampleGain(bitPerfect = true, fade = Float.NaN))
    }
}
