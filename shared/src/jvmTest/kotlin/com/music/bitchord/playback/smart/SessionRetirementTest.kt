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
        var closedUnderTheRun = false
        // Leaves [SessionRetirement.using] only when the test releases it, so no
        // pause on the test's side can end the run before the assertions.
        val worker = thread {
            retirement.using {
                running.countDown()
                finish.await()
                closedUnderTheRun = session.closed
            }
        }
        try {
            assertTrue(running.await(5, TimeUnit.SECONDS))

            // On a thread of its own, with a bounded wait: a retire that
            // blocked on the run would show here as a timeout.
            val retiring = thread { retirement.retire(session, options) }
            retiring.join(5_000)
            assertFalse(retiring.isAlive, "retire waited for the run")
            assertFalse(session.closed)
            assertEquals(2, retirement.waiting)
        } finally {
            finish.countDown()
        }
        worker.join(5_000)
        assertFalse(closedUnderTheRun, "closed under a run")
        assertTrue(session.closed)
        assertTrue(options.closed)
        assertEquals(0, retirement.waiting)
    }
}
