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

    private fun tone(seconds: Double): FloatArray = FloatArray((seconds * rate * channels).toInt()) { index ->
        sin(2.0 * PI * 440.0 * (index / channels) / rate).toFloat()
    }
}
