package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The neural stage, asked to play a song it cannot play.
 *
 * 3.0.4 crashed the app a second after it opened, and the crash was in
 * here: the processor configured itself for the track, and the first thing
 * it did was ask for a native symbol that was not in memory.
 *
 * A processor that cannot do its job has to say so and stand down. The
 * song then plays as it is, at its own rate, and the app stays up — which
 * is a far better outcome than an exception on the thread that plays the
 * music.
 *
 * A JVM has no native library, so it is exactly the situation where the
 * converter cannot be made.
 */
class NeuralSingProcessorTest {

    /** A model that is shut: the separator has nothing to say. */
    private class ShutModel : VocalModel {
        override val ready: Boolean = false
        override fun separate(modelAudio: FloatArray, frames: Int): FloatArray? = null
    }

    private fun processor(quality: StudioQuality): NeuralSingProcessor =
        NeuralSingProcessor(
            separator = NeuralVocalSeparator(ShutModel()),
            quality = { quality },
        ).also { it.enabled = true }

    @Test
    fun `a processor that cannot convert stands down instead of throwing`() {
        val studio = processor(StudioQuality.MODEL_RATE)
        val format = AudioProcessor.AudioFormat(48_000, C.ENCODING_PCM_16BIT, 2)

        // The answer on a JVM is "not set": there is no converter here,
        // so the stage does not take part. What matters is that it
        // answered at all.
        assertEquals(
            AudioProcessor.AudioFormat.NOT_SET,
            studio.configure(format),
        )
    }

    @Test
    fun `a processor that is off stands down too`() {
        val studio = processor(StudioQuality.MODEL_RATE)
        studio.enabled = false
        val format = AudioProcessor.AudioFormat(48_000, C.ENCODING_PCM_16BIT, 2)

        assertEquals(
            AudioProcessor.AudioFormat.NOT_SET,
            studio.configure(format),
        )
    }

    @Test
    fun `a mono track is left alone`() {
        val studio = processor(StudioQuality.MODEL_RATE)
        val format = AudioProcessor.AudioFormat(48_000, C.ENCODING_PCM_16BIT, 1)

        assertEquals(
            AudioProcessor.AudioFormat.NOT_SET,
            studio.configure(format),
        )
    }
}
