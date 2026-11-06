package com.music.bitchord.playback.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rate converter, asked for without the library.
 *
 * 3.0.4 shipped a Studio that crashed the app a second after it opened,
 * and the crash was this class: the native methods were called before
 * anything had loaded the library they live in, and the runtime answered
 * an `UnsatisfiedLinkError` on the thread that plays the music.
 *
 * A JVM has no native library at all, which makes it the right place to
 * ask the question that matters: what does the class do when there is
 * nothing behind it? The answer has to be "nothing, quietly" — a
 * converter that says it cannot work, and a caller that carries on with
 * the song as it is.
 */
class MdxResamplerTest {

    @Test
    fun `a converter with no library behind it says so instead of throwing`() {
        // The library is not there on a JVM, which is the same situation
        // as a phone whose library failed to load.
        val converter = MdxResampler.create(48_000.0, 44_100.0)

        // Whether the library is there is not the point of the test; what
        // matters is that the answer matches it, and that nothing threw.
        assertEquals(
            "the converter claims to work when it cannot",
            NativeAnalysisLibrary.available,
            converter.supported,
        )
    }

    @Test
    fun `a converter that cannot work does nothing when it is used`() {
        val converter = MdxResampler.create(48_000.0, 44_100.0)
        if (converter.supported) return

        // Every one of these used to be able to take the app down.
        assertTrue(
            "a converter that cannot work still produced audio",
            converter.process(FloatArray(128), 64).isEmpty(),
        )
        assertTrue(
            "a converter that cannot work still flushed audio",
            converter.flush().isEmpty(),
        )
        converter.reset()
        converter.close()
        // And closing twice is not a crash either.
        converter.close()
    }

    @Test
    fun `impossible rates make no converter at all`() {
        assertFalse(MdxResampler.create(0.0, 44_100.0).supported)
        assertFalse(MdxResampler.create(48_000.0, -1.0).supported)
        assertFalse(MdxResampler.create(-44_100.0, 48_000.0).supported)
    }
}
