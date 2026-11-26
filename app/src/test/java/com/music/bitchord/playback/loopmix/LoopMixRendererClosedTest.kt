package com.music.bitchord.playback.loopmix

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LoopMixRendererClosedTest {
    /**
     * The renderer as a JVM sink sees it: no native library, so no handle at
     * all, closed or not. A live handle being closed under a render is the
     * native side's to show (the admission count in [LoopMixRenderer]); it
     * can't be reached from here.
     */
    @Test
    fun aRendererWithNoHandleRendersNothingAndLeavesTheBufferAlone() {
        val renderer = LoopMixRenderer(48_000)
        renderer.close()
        val buffer = FloatArray(8) { 0.5f }
        assertFalse(renderer.render(0L, buffer, 0, 4))
        assertArrayEquals(FloatArray(8) { 0.5f }, buffer, 0f)
    }
}
