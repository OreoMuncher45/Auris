package com.music.bitchord.perf

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleWindowTest {

    @Test
    fun `keeps the newest samples, oldest first`() {
        val window = SampleWindow<Int>(keep = 3)
        (1..5).forEach(window::add)
        assertEquals(listOf(3, 4, 5), window.snapshot())
    }

    @Test
    fun `a snapshot is not touched by later writes`() {
        val window = SampleWindow<Int>(keep = 3)
        (1..3).forEach(window::add)
        val before = window.snapshot()
        window.add(4)
        window.clear()
        assertEquals(listOf(1, 2, 3), before)
        assertTrue(window.snapshot().isEmpty())
    }

    /**
     * The report's side of the race: one thread samples (add, trim, now and
     * then clear) while another reads the window the way the report does.
     * Without the lock this throws, or sees a window that is not one
     * moment's: a gap or a reversal in the sequence, or more than [KEEP]
     * samples. It is a race test, so it is probabilistic; with the lock taken
     * out it failed on every run here.
     *
     * Both threads start together, and the reader waits for the writer's
     * first sample, so every read overlaps the writer's work.
     */
    @Test
    fun `a reader always sees one moment's window`() {
        val window = SampleWindow<Long>(keep = KEEP)
        val stop = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val started = CountDownLatch(2)
        val firstWrite = CountDownLatch(1)

        val writer = Thread {
            started.countDown()
            started.await()
            var next = 0L
            try {
                while (!stop.get()) {
                    window.add(next++)
                    if (next == 1L) firstWrite.countDown()
                    if (next % 1_000 == 0L) window.clear()
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }
        val reader = Thread {
            started.countDown()
            started.await()
            try {
                check(firstWrite.await(5, TimeUnit.SECONDS)) { "the writer never started" }
                repeat(READS) {
                    val seen = window.snapshot()
                    check(seen.size <= KEEP) { "window of ${seen.size} > $KEEP" }
                    seen.zipWithNext { a, b -> check(b == a + 1) { "not one moment: $a then $b" } }
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }
        writer.start()
        reader.start()
        reader.join(TimeUnit.SECONDS.toMillis(30))
        stop.set(true)
        writer.join(TimeUnit.SECONDS.toMillis(5))
        assertFalse("reader still running", reader.isAlive)
        assertFalse("writer still running", writer.isAlive)

        assertNull("reader or writer failed: ${failure.get()}", failure.get())
    }

    private companion object {
        const val KEEP = 90
        const val READS = 20_000
    }
}
