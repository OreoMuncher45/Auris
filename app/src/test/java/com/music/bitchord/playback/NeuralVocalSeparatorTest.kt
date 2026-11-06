package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The separator's own arithmetic, measured.
 *
 * 3.0.3 shipped Studio and it did not separate anything, and the reason
 * was not the network: it was the separator's cursor. The song went in,
 * the answer came out for a different part of the song, and the two were
 * mixed together as though they belonged to each other. The listener
 * heard the song, unchanged, with a ghost of the vocal on top.
 *
 * These tests are about the arithmetic rather than the model, because the
 * arithmetic is the part that can be wrong without anyone noticing: a
 * network that returns the wrong answer and a cursor that puts the right
 * answer in the wrong place sound exactly the same.
 *
 * The model here is a fake that answers with half the song it was given,
 * so the answer is known exactly: the mixed output must be half the song
 * wherever the answer was used, and the whole song wherever the answer
 * was not. It answers only when the test says so, because a separator
 * whose model answers instantly never has to fill the gap with the song
 * as it is -- and that gap is the whole of the first second of a track.
 */
class NeuralVocalSeparatorTest {

    /** One window of audio, in frames. */
    private val window = NeuralVocalSeparator.WINDOW_FRAMES

    /**
     * A model that answers with half of what it was given, when told to.
     *
     * The answer is deliberately the simplest one there is: half the song
     * is the vocal, so mixing the song with it at full cut leaves half the
     * song, and every sample of the answer is known.
     */
    private class GatedModel : VocalModel {
        override var ready: Boolean = true
        var calls: Int = 0
            private set

        private val gate = CountDownLatch(1)

        override fun separate(modelAudio: FloatArray, frames: Int): FloatArray? {
            calls++
            gate.await(10, TimeUnit.SECONDS)
            return FloatArray(modelAudio.size) { modelAudio[it] * 0.5f }
        }

        /** Lets the model answer. */
        fun answer() {
            gate.countDown()
        }
    }

    /**
     * A song that says where it is: every frame carries its own number, so
     * a sample that arrives in the wrong place is obvious. A frame that
     * comes out at the wrong number is a repeat or a skip, which is the
     * thing a cursor gets wrong.
     */
    private fun song(frames: Int, from: Int = 0): FloatArray =
        FloatArray(frames * 2) { index ->
            (from + index / 2).toFloat()
        }

    /** Pulls everything the separator has, until it says there is no more. */
    private fun drain(separator: NeuralVocalSeparator): FloatArray {
        val pieces = ArrayList<FloatArray>()
        var guard = 0
        while (guard++ < 10_000) {
            val piece = separator.pull(4_096)
            if (piece.isEmpty()) break
            pieces.add(piece)
        }
        val total = pieces.sumOf { it.size }
        val out = FloatArray(total)
        var at = 0
        for (piece in pieces) {
            System.arraycopy(piece, 0, out, at, piece.size)
            at += piece.size
        }
        return out
    }

    /** Waits until the separator has separated [count] windows. */
    private fun awaitWindows(separator: NeuralVocalSeparator, count: Long) {
        val until = System.currentTimeMillis() + 10_000
        while (separator.windowsSeparated < count && System.currentTimeMillis() < until) {
            Thread.sleep(5)
        }
        assertTrue(
            "the model was never asked ${separator.windowsSeparated} times",
            separator.windowsSeparated >= count,
        )
    }

    @Test
    fun `the song comes out in order, and none of it is lost`() {
        val model = GatedModel()
        val separator = NeuralVocalSeparator(model)
        separator.cut = 1f

        val frames = window * 3
        separator.push(song(frames), frames)
        // The model has not answered yet, so the song plays as it is: the
        // gap has to be filled with the song, and the separated song has
        // to pick up exactly where that left off.
        val dry = separator.pull(4_096)
        assertEquals("the dry fill did not come out", 4_096, dry.size / 2)

        model.answer()
        awaitWindows(separator, 2)
        separator.push(song(window, from = frames), window)
        Thread.sleep(20)
        val rest = drain(separator)

        // Every frame that went in comes out, and the frames that were
        // played dry are the song and the frames that were separated are
        // half of it. A frame that carries any other number is a repeat or
        // a skip: the cursor moved wrong.
        val out = FloatArray(dry.size + rest.size)
        System.arraycopy(dry, 0, out, 0, dry.size)
        System.arraycopy(rest, 0, out, dry.size, rest.size)
        val outFrames = out.size / 2
        assertTrue("nothing came out", outFrames > 0)
        assertTrue(
            "the separator lost audio: $outFrames frames out of $frames in",
            outFrames <= frames + window,
        )
        for (frame in 0 until outFrames) {
            val value = out[frame * 2]
            val dryFrame = frame.toFloat()
            val separated = dryFrame * 0.5f
            val ok = value == dryFrame || value == separated
            assertTrue(
                "frame $frame is neither the song nor the half of it: $value",
                ok,
            )
        }
    }

    @Test
    fun `the separated song starts exactly where the dry one stopped`() {
        val model = GatedModel()
        val separator = NeuralVocalSeparator(model)
        separator.cut = 1f

        // One window in, a piece of dry audio out: the song has to start
        // before the network has answered, or there is a second of silence
        // at the start of every track.
        separator.push(song(window), window)
        val first = separator.pull(4_096)
        assertEquals("the dry fill did not come out", 4_096, first.size / 2)
        for (frame in 0 until 4_096) {
            assertEquals(
                "the dry fill is not the song",
                frame.toFloat(),
                first[frame * 2],
            )
        }

        // The answer arrives, and the separated song continues from the
        // frame the dry one stopped at: no repeat, no gap.
        model.answer()
        awaitWindows(separator, 1)
        separator.push(song(window, from = window), window)
        Thread.sleep(20)
        val rest = drain(separator)

        // The first frame of the rest is the frame after the dry fill, and
        // it is separated rather than dry.
        assertTrue("the song was never separated", rest.isNotEmpty())
        assertEquals(
            "the separated song did not continue where the dry one stopped",
            4_096f * 0.5f,
            rest[0],
        )
    }

    @Test
    fun `the counts of what went in and what came out agree`() {
        val model = GatedModel()
        val separator = NeuralVocalSeparator(model)
        separator.cut = 1f

        val frames = window * 2
        separator.push(song(frames), frames)
        model.answer()
        awaitWindows(separator, 1)
        Thread.sleep(20)
        drain(separator)

        assertEquals("the separator did not count what went in", frames.toLong(), separator.framesIn)
        assertEquals(
            "the separator handed out a different amount than it took in",
            separator.framesIn,
            separator.framesOut,
        )
        assertEquals("nothing was dropped", 0L, separator.droppedFrames)
    }

    @Test
    fun `a model that is shut leaves the song to the caller`() {
        // The model is shut: the separator must not swallow the song. The
        // caller plays it as it is.
        val model = object : VocalModel {
            override val ready: Boolean = false
            override fun separate(modelAudio: FloatArray, frames: Int): FloatArray? = null
        }
        val separator = NeuralVocalSeparator(model)
        separator.cut = 1f

        val frames = window
        separator.push(song(frames), frames)
        val out = drain(separator)
        assertTrue("a closed model swallowed the song", out.isEmpty())
        assertEquals(0L, separator.framesIn)
    }

    @Test
    fun `a model that fails mid-song leaves that stretch dry`() {
        // A window the network could not answer for is played as it is:
        // the song keeps going and the voice comes back for that stretch.
        val model = object : VocalModel {
            override val ready: Boolean = true
            override fun separate(modelAudio: FloatArray, frames: Int): FloatArray? = null
        }
        val separator = NeuralVocalSeparator(model)
        separator.cut = 1f

        val frames = window * 2
        separator.push(song(frames), frames)
        Thread.sleep(50)
        val out = drain(separator)
        assertTrue("the song was lost", out.size / 2 > 0)
        for (frame in 0 until out.size / 2) {
            assertEquals(
                "a failed window should be dry",
                frame.toFloat(),
                out[frame * 2],
            )
        }
    }
}
