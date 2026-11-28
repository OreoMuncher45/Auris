package com.music.bitchord.playback

import com.music.bitchord.playback.smart.MixWaveform
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MixDeckWaveTest {
    private val bands = MixWaveform.Bands(FloatArray(4), FloatArray(4), FloatArray(4))
    private val wave = MixDeckWave("song", rendition = "song#premium", from = 10.0, to = 40.0, bands = bands)

    @Test
    fun theSameCopyAndSpanIsReused() {
        assertTrue(wave.covers("song", "song#premium", 12.0, 38.0))
    }

    @Test
    fun anotherCutUnderTheSameMediaIdIsDecodedAgain() {
        assertFalse("the old cut's waveform for the new one", wave.covers("song", "song", 12.0, 38.0))
        assertFalse(wave.covers("song", null, 12.0, 38.0))
    }

    @Test
    fun aSpanItDoesNotCoverIsDecodedAgain() {
        assertFalse(wave.covers("song", "song#premium", 5.0, 38.0))
    }
}
