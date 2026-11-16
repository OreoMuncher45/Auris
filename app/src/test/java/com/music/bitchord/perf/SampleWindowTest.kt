package com.music.bitchord.perf

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleWindowTest {

    @Test
    fun keepsTheNewestSamplesOldestFirst() {
        val window = SampleWindow<Int>(keep = 3)
        (1..5).forEach(window::add)
        assertEquals(listOf(3, 4, 5), window.snapshot())
    }

    @Test
    fun snapshotIsNotTouchedByLaterWrites() {
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
     * then clear) while another walks the window the way the report does,
     * reading the size, every element and the last one. With the old bare
     * ArrayDeque this throws, or sees a window that is not one moment's:
     * a gap or a reversal in the sequence, or more than [KEEP] samples.
     */
    @Test
    fun readerAlwaysSeesOneMomentsWindow() {
        val window = SampleWindow<Long>(keep = KEEP)
        val stop = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val started = CountDownLatch(2)

        val writer = Thread {
            started.countDown()
            var next = 0L
            try {
                while (!stop.get()) {
                    window.add(next++)
                    if (next % 1_000 == 0L) window.clear()
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }
        val reader = Thread {
            started.countDown()
            try {
                repeat(READS) {
                    val seen = window.snapshot()
                    check(seen.size <= KEEP) { "window of ${seen.size} > $KEEP" }
                    seen.zipWithNext { a, b -> check(b == a + 1) { "not one moment: $a then $b" } }
                    if (seen.isNotEmpty()) check(seen.last() == seen.first() + seen.size - 1)
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }
        writer.start()
        reader.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        reader.join(TimeUnit.SECONDS.toMillis(30))
        stop.set(true)
        writer.join(TimeUnit.SECONDS.toMillis(5))

        assertNull("reader or writer failed: ${failure.get()}", failure.get())
    }

    private companion object {
        const val KEEP = 90
        const val READS = 20_000
    }
}
