package com.music.bitchord.playback.smart

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionRetirementTest {

    private class Closeable : AutoCloseable {
        @Volatile var closed = false
        override fun close() { closed = true }
    }

    @Test
    fun aSessionRetiredWithNothingRunningClosesAtOnce() {
        val retirement = SessionRetirement()
        val session = Closeable()
        retirement.retire(session)
        assertTrue(session.closed)
    }

    @Test
    fun aSessionRetiredMidRunClosesOnlyWhenTheRunReturnsAndRetireDoesNotWait() {
        val retirement = SessionRetirement()
        val session = Closeable()
        val options = Closeable()
        val running = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val worker = thread {
            retirement.using {
                running.countDown()
                finish.await(5, TimeUnit.SECONDS)
                assertFalse(session.closed, "closed under a run")
            }
        }
        assertTrue(running.await(5, TimeUnit.SECONDS))

        retirement.retire(session, options)
        // Returned without waiting, and nothing closed yet.
        assertFalse(session.closed)
        assertEquals(2, retirement.waiting)

        finish.countDown()
        worker.join(5_000)
        assertTrue(session.closed)
        assertTrue(options.closed)
        assertEquals(0, retirement.waiting)
    }
}
