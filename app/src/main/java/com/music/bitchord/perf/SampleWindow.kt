package com.music.bitchord.perf

/**
 * The last [keep] samples, shared between the thread that takes them and the
 * thread that reports on them.
 *
 * [PerfMonitor] samples on `Dispatchers.Default` and builds its report on
 * whichever thread asks for it. Every access holds one lock, and a reader
 * never walks the live window: it takes a [snapshot], a copy of the window at
 * a single moment, so a report describes one set of samples from its first
 * line to its last.
 */
internal class SampleWindow<T>(private val keep: Int) {
    private val lock = Any()
    private val samples = ArrayDeque<T>(keep + 1)

    /** Adds [sample] at the end, dropping the oldest past [keep]. */
    fun add(sample: T) {
        synchronized(lock) {
            samples.addLast(sample)
            while (samples.size > keep) samples.removeFirst()
        }
    }

    fun clear() {
        synchronized(lock) { samples.clear() }
    }

    /** The window as it stands now, oldest first. Later writes do not touch it. */
    fun snapshot(): List<T> = synchronized(lock) { samples.toList() }
}
