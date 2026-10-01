package com.music.bitchord.desktop

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopTempoBufferTest {
    private val channels = 2
    private val rate = 44_100

    @Test
    fun `ten percent beatmatch produces less wall time without dropping queued output`() {
        val tempo = DesktopTempoBuffer(channels, rate).apply { speed = 1.1f }
        val source = tone(seconds = 2.0)
        var offset = 0
        while (offset < source.size) {
            val count = minOf(4_096, source.size - offset)
            tempo.push(source.copyOfRange(offset, offset + count), count)
            offset += count
        }

        var rendered = 0
        while (tempo.available > 0) {
            tempo.take(4_096)
            rendered += tempo.outputCount
        }
        assertTrue(rendered > 0)
        assertTrue(rendered < source.size, "a speed-up should occupy less output time")
        val ratio = source.size.toDouble() / rendered
        assertTrue(ratio in 1.04..1.16, "unexpected rendered rate $ratio")
    }

    @Test
    fun `surplus survives block-sized reads`() {
        val tempo = DesktopTempoBuffer(channels, rate).apply { speed = 1.08f }
        val source = tone(seconds = 1.0)
        tempo.push(source, source.size)
        val before = tempo.available
        tempo.take(4_096)
        assertEquals(before - tempo.outputCount, tempo.available)
        assertTrue(tempo.available > 0)
    }

    @Test
    fun `finishing an eased stretch hands back to the decoder with nothing lost or repeated`() {
        val tempo = DesktopTempoBuffer(channels, rate).apply { speed = 1.04f }
        val source = tone(seconds = 2.0)
        val pushed = 1 * rate * channels
        var offset = 0
        while (offset < pushed) {
            val count = minOf(4_096, pushed - offset)
            tempo.push(source.copyOfRange(offset, offset + count), count)
            offset += count
        }
        tempo.speed = 1f
        tempo.finish()
        val out = ArrayList<Float>()
        while (tempo.available > 0) {
            val block = tempo.take(4_096)
            for (i in 0 until tempo.outputCount) out += block[i]
        }
        // The decoder carries on from `pushed`, so what was queued must end exactly on what came
        // before it — the same samples, not a gap or a repeat.
        val tailFrames = 2_000
        for (frame in 0 until tailFrames) {
            val expected = source[pushed - (tailFrames - frame) * channels]
            val actual = out[out.size - (tailFrames - frame) * channels]
            assertEquals(expected, actual, 1e-6f, "frame $frame before the handback differs")
        }
    }

    private fun tone(seconds: Double): FloatArray = FloatArray((seconds * rate * channels).toInt()) { index ->
        sin(2.0 * PI * 440.0 * (index / channels) / rate).toFloat()
    }
}
