package com.decent.usbaudio

import com.decent.usbaudio.KernelDriverHandback.Interface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KernelDriverHandbackTest {

    private class FakePort(
        private val connectCodes: Map<Int, Int> = emptyMap(),
        private val throwOnConnect: Set<Int> = emptySet(),
        private val refuseRelease: Set<Int> = emptySet(),
    ) : KernelDriverHandback.Port {
        val calls = mutableListOf<String>()
        override fun release(number: Int): Boolean {
            calls += "release $number"
            return number !in refuseRelease
        }
        override fun connect(number: Int): Int {
            calls += "connect $number"
            if (number in throwOnConnect) throw IllegalStateException("fd closed")
            return connectCodes[number] ?: 0
        }
    }

    /** A USB-C headset: AudioControl 0, speaker streaming 1, microphone 2. */
    private val headset = listOf(
        Interface(number = 1, audioSubclass = 2),
        Interface(number = 1, audioSubclass = 2), // alt setting 1 of the same interface
        Interface(number = 2, audioSubclass = 2),
        Interface(number = 0, audioSubclass = 1),
    )

    @Test
    fun `every claim is released before any interface is reattached`() {
        val port = FakePort()
        KernelDriverHandback.handBack(claimed = listOf(0, 1), audioInterfaces = headset, port = port)
        val lastRelease = port.calls.indexOfLast { it.startsWith("release") }
        val firstConnect = port.calls.indexOfFirst { it.startsWith("connect") }
        assertTrue(port.calls.toString(), lastRelease < firstConnect)
    }

    @Test
    fun `AudioControl is reattached first and each interface once`() {
        assertEquals(listOf(0, 1, 2), KernelDriverHandback.reattachOrder(headset))
    }

    @Test
    fun `the microphone interface is reattached too, though it was never claimed`() {
        val port = FakePort()
        val result = KernelDriverHandback.handBack(listOf(0, 1), headset, port)
        assertTrue("connect 2" in port.calls)
        assertEquals(listOf(0, 1, 2), result.connected)
    }

    @Test
    fun `a streaming interface the kernel driver already took back does not stop the rest`() {
        val ebusy = -16
        val port = FakePort(connectCodes = mapOf(1 to ebusy))
        val result = KernelDriverHandback.handBack(listOf(0, 1), headset, port)
        assertEquals(listOf(0, 2), result.connected)
        assertEquals(mapOf(1 to ebusy), result.failed)
    }

    @Test
    fun `a failing call is recorded and the handback carries on`() {
        val port = FakePort(throwOnConnect = setOf(0), refuseRelease = setOf(1))
        val result = KernelDriverHandback.handBack(listOf(0, 1), headset, port)
        assertEquals(listOf(0), result.released)
        assertEquals(listOf(1, 2), result.connected)
        assertEquals(setOf(0), result.failed.keys)
    }

    @Test
    fun `a failed open that claimed only AudioControl still reattaches every audio interface`() {
        // openDevice: AudioControl claimed with force, streaming claim refused.
        val port = FakePort()
        val result = KernelDriverHandback.handBack(listOf(0), headset, port)
        assertEquals(listOf("release 0", "connect 0", "connect 1", "connect 2"), port.calls)
        assertEquals(listOf(0, 1, 2), result.connected)
    }

    @Test
    fun `a DAC with no AudioControl interface still gets its streaming interfaces back`() {
        val port = FakePort()
        val result = KernelDriverHandback.handBack(listOf(3), listOf(Interface(3, 2)), port)
        assertEquals(listOf(3), result.released)
        assertEquals(listOf(3), result.connected)
    }
}
