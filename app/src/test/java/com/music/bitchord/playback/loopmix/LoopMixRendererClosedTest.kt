package com.music.bitchord.playback.loopmix

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LoopMixRendererClosedTest {
    @Test
    fun aClosedRendererSaysItRenderedNothing() {
        // No native library on the JVM, so this renderer never had a handle; closing it
        // as well covers the path a sink hits after the controller freed it.
        val renderer = LoopMixRenderer(48_000)
        renderer.close()
        val buffer = FloatArray(8) { 0.5f }
        assertFalse(renderer.render(0L, buffer, 0, 4))
        assertArrayEquals(FloatArray(8) { 0.5f }, buffer, 0f)
    }
}
