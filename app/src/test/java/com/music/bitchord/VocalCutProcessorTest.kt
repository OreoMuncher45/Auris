package com.music.bitchord

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.music.bitchord.playback.VocalCutProcessor
import com.music.bitchord.playback.audio.AudioBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Sing's vocal cut actually does to a signal.
 *
 * The risk this covers is a cut that is quietly the wrong shape: a mistake in
 * the mid/side mix or the makeup gain still runs and still sounds like
 * *something*, and by ear a wrong cut is indistinguishable from a right one
 * until the vocal refuses to leave. So every test here measures: a centered
 * tone must vanish at full cut, a hard-panned tone must survive it untouched,
 * and an idle processor must not move a single sample.
 */
class VocalCutProcessorTest {

    @Test
    fun `idle processor leaves the block byte-identical`() {
        val processor = VocalCutProcessor()
        processor.level = 0f
        val block = stereoBlock(0.5f, -0.25f, 0.9f, 0.1f)
        val before = block.samples.copyOf()
        processor.process(block)
        assertTrue(before.contentEquals(block.samples))
    }

    @Test
    fun `full cut silences a centered tone`() {
        val cut = FloatArray(8) { if (it % 2 == 0) 0.6f else 0.6f }
        VocalCutProcessor().cutCenter(cut, cut.size, 1f)
        cut.forEach { assertEquals(0f, it, 1e-6f) }
    }

    @Test
    fun `full cut preserves a hard-panned tone at full amplitude`() {
        // Left-only: mid is half the left, so left minus mid is half the
        // left, and the x2 makeup for full cut restores it exactly — with
        // the side image mirrored out of phase into the empty channel.
        val cut = floatArrayOf(0.8f, 0f, -0.4f, 0f)
        VocalCutProcessor().cutCenter(cut, cut.size, 1f)
        assertEquals(0.8f, cut[0], 1e-6f)
        assertEquals(-0.8f, cut[1], 1e-6f)
        assertEquals(-0.4f, cut[2], 1e-6f)
        assertEquals(0.4f, cut[3], 1e-6f)
    }

    @Test
    fun `half cut pulls a centered tone down by a third`() {
        // (L - 0.5 * L) / (1 - 0.25) = 2L/3: the makeup holds the side
        // energy steady, so the center drops by exactly a third.
        val cut = floatArrayOf(0.9f, 0.9f)
        VocalCutProcessor().cutCenter(cut, cut.size, 0.5f)
        assertEquals(0.6f, cut[0], 1e-6f)
        assertEquals(0.6f, cut[1], 1e-6f)
    }

    @Test
    fun `makeup gain never exceeds doubling`() {
        // A hard-panned peak at full cut is the hottest case: side-only
        // content restored to itself, never above it.
        val cut = floatArrayOf(1f, 0f)
        VocalCutProcessor().cutCenter(cut, cut.size, 1f)
        assertTrue(cut[0] <= 1f + 1e-6f)
    }

    @Test
    fun `configure accepts stereo 16-bit and nothing else`() {
        val processor = VocalCutProcessor()
        val stereo16 = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        assertEquals(stereo16, processor.configure(stereo16))
        assertEquals(
            AudioProcessor.AudioFormat.NOT_SET,
            processor.configure(AudioProcessor.AudioFormat(44100, 1, C.ENCODING_PCM_16BIT)),
        )
        processor.flush()
        assertEquals(
            AudioProcessor.AudioFormat.NOT_SET,
            processor.configure(AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_FLOAT)),
        )
    }

    private fun stereoBlock(vararg samples: Float): AudioBlock {
        val block = AudioBlock(channelCount = 2, capacityFrames = samples.size / 2)
        samples.copyInto(block.samples)
        block.setFrameCount(samples.size / 2)
        return block
    }
}
