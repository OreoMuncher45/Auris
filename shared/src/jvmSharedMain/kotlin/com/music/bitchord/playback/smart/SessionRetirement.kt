package com.music.bitchord.playback.smart

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read

/**
 * Closes an ONNX session (and its options) only once no inference is using it.
 *
 * ONNX Runtime needs a session and its options open until every run on them
 * has returned, but the trackers hand a session out and run it outside their
 * own lock, so a [BeatTracker.release] or a thread-count change could close it
 * mid-run. A retired session is closed at once when nothing is running, and
 * otherwise by the last inference to leave. Nothing ever waits for a run to
 * finish: release is called from service teardown, on the main thread.
 */
internal class SessionRetirement {
    private val inUse = ReentrantReadWriteLock()
    private val retired = ConcurrentLinkedQueue<AutoCloseable>()

    /** Runs [block] with whatever sessions it uses kept open. */
    fun <T> using(block: () -> T): T =
        try {
            inUse.read(block)
        } finally {
            drain()
        }

    /** Closes [items], in order, as soon as no [using] block is running. */
    fun retire(vararg items: AutoCloseable?) {
        items.forEach { it?.let(retired::add) }
        drain()
    }

    private fun drain() {
        if (retired.isEmpty()) return
        val write = inUse.writeLock()
        if (!write.tryLock()) return
        try {
            while (true) {
                val item = retired.poll() ?: break
                runCatching { item.close() }
            }
        } finally {
            write.unlock()
        }
    }

    /** For tests: how many retired items are still waiting to be closed. */
    internal val waiting: Int get() = retired.size
}
