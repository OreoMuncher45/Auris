package com.music.bitchord.playback.smart

import com.music.bitchord.playback.NeuralModel
import com.music.bitchord.playback.NeuralVocalSeparator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two counts of a window, and the confusion between them.
 *
 * The audio is counted in samples and the transform is counted in hops of
 * 1024, so a window of 32 hops is 32_768 samples. Asking the transform for
 * 32_768 frames of a window that holds 32 gets an empty tensor back — and
 * an empty tensor is a model that appears to do nothing at all: the song
 * plays, the counter says windows are being separated, and not one sample
 * of the music is different.
 *
 * So the two numbers are pinned here.
 */
class MdxStftTest {

    @Test
    fun `a window is thirty-two frames of the transform, not thirty-two thousand samples`() {
        assertEquals(32, MdxStft.framesFor(32 * 1024))
        assertEquals(
            MdxStft.FRAMES,
            MdxStft.framesFor(NeuralVocalSeparator.WINDOW_FRAMES),
        )
    }

    @Test
    fun `the model's tensor is as wide as the window is long`() {
        val shape = NeuralModel.tensorShape(
            MdxStft.framesFor(NeuralVocalSeparator.WINDOW_FRAMES),
        )

        assertEquals("one song at a time", 1L, shape[0])
        assertEquals("two sides, real and imaginary", 4L, shape[1])
        assertEquals("the bins", MdxStft.BINS.toLong(), shape[2])
        assertEquals(
            "the time axis is counted in frames, not samples",
            MdxStft.FRAMES.toLong(),
            shape[3],
        )
    }

    @Test
    fun `the window is long enough for the frames it is asked for`() {
        // The transform refuses a window that is too short for the frames
        // asked of it, and answers with nothing at all.
        val samples = NeuralVocalSeparator.WINDOW_FRAMES
        val frames = MdxStft.framesFor(samples)
        assertTrue(
            "the window is too short for its own frames",
            samples >= frames * MdxStft.HOP,
        )
    }
}
