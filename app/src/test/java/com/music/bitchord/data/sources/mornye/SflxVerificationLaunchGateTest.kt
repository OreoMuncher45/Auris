package com.music.bitchord.data.sources.mornye

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** One activity, exact duplicate suppression, and no suppression of a new challenge. */
class SflxVerificationLaunchGateTest {

    @Test
    fun `parallel copies of one active challenge launch once`() {
        val gate = SflxVerificationLaunchGate()
        val workers = 16
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val launches = AtomicInteger()
        val executor = Executors.newFixedThreadPool(workers)
        try {
            val calls = List(workers) {
                executor.submit {
                    ready.countDown()
                    start.await(2, TimeUnit.SECONDS)
                    gate.open("tidal-web", "https://verify.example/one") {
                        launches.incrementAndGet()
                        Thread.sleep(20)
                    }
                }
            }
            assertEquals(true, ready.await(2, TimeUnit.SECONDS))
            start.countDown()
            calls.forEach { it.get(2, TimeUnit.SECONDS) }
            gate.open("tidal-web", "https://verify.example/one") { launches.incrementAndGet() }
            assertEquals("no five-second window lets a duplicate through", 1, launches.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `a new URL or extension is not mistaken for a duplicate`() {
        val gate = SflxVerificationLaunchGate()
        val opened = ArrayList<String>()

        gate.open("tidal-web", "https://verify.example/one") { opened += "tidal one" }
        gate.open("tidal-web", "https://verify.example/two") { opened += "tidal two" }
        gate.open("amazon", "https://verify.example/one") { opened += "amazon one" }

        assertEquals(listOf("tidal one", "tidal two", "amazon one"), opened)
    }

    @Test
    fun `a failed launch releases the old reservation`() {
        val gate = SflxVerificationLaunchGate()
        val launches = AtomicInteger()
        gate.open("tidal-web", "https://verify.example/one", launch = {
            launches.incrementAndGet()
            throw IllegalStateException("activity not found")
        })

        gate.open("tidal-web", "https://verify.example/one") { launches.incrementAndGet() }

        assertEquals("the explicit retry is allowed after the failure", 2, launches.get())
    }

    @Test
    fun `close permits reopen but a stale close cannot clear a newer challenge`() {
        val gate = SflxVerificationLaunchGate()
        val launches = AtomicInteger()
        gate.open("tidal-web", "https://verify.example/one") { launches.incrementAndGet() }
        gate.open("tidal-web", "https://verify.example/two") { launches.incrementAndGet() }

        gate.closed("tidal-web", "https://verify.example/one")
        gate.open("tidal-web", "https://verify.example/two") { launches.incrementAndGet() }
        assertEquals("old page cannot release the new page", 2, launches.get())

        gate.closed("tidal-web", "https://verify.example/two")
        gate.open("tidal-web", "https://verify.example/two") { launches.incrementAndGet() }
        assertEquals("closing then explicitly retrying still works", 3, launches.get())
    }
}
