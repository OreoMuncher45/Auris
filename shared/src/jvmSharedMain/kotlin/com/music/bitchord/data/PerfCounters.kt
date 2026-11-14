package com.music.bitchord.data

import java.util.concurrent.atomic.AtomicLongArray

/**
 * Counters for the work the player does per frame, for the performance monitor.
 *
 * Deliberately dumb: one atomic increment per event, and nothing else. No
 * timestamps, no strings, no allocation — a counter that costs more than the
 * thing it counts would be measuring itself. The reading side is the phone's
 * [com.music.bitchord.perf.PerfMonitor], which samples these on a clock and
 * turns the deltas into a rate.
 *
 * That is the whole design of the monitor: the hot paths stay hot, and the
 * explaining happens somewhere else, a few times a minute.
 */
object PerfCounters {
    /** The things worth counting separately. */
    enum class Counter {
        /** A frame copied off a playing clip, for the backdrop and the ink. */
        FRAME_READ,

        /** A captured frame averaged into the backdrop's mesh. */
        MESH_READ,

        /** A captured frame read for the ink's colours. */
        INK_READ,

        /** A box-blur pass over a cover, still or live. */
        BLUR_PASS,

        /** A pass of the Automix analyser over a track's audio. */
        ANALYSER_PASS,

        /**
         * A track's source worked out — which service it plays from, at what
         * quality.
         *
         * The expensive half of the app's startup and of every skip: it is
         * where the network calls, the extractor, the addon scripts and the
         * browser page live, and none of that shows up in the counters above.
         */
        RESOLVE,

        /**
         * A track prepared for the Automix to play next.
         *
         * Each one is a resolution of its own and can hold a player, a decoder
         * and an artwork bitmap, so the count is the honest measure of how
         * much the app is keeping ready.
         */
        PREPARE,

        /**
         * A BotGuard page loaded for YouTube's tokens.
         *
         * A browser, inside the app's own process: tens of megabytes and a
         * few dozen threads, alive for as long as it is held.
         */
        WEBVIEW,
    }

    private val counts = AtomicLongArray(Counter.entries.size)

    /** One event. Costs an atomic add. */
    fun bump(counter: Counter) {
        counts.incrementAndGet(counter.ordinal)
    }

    /**
     * Every counter's total, in [Counter] order.
     *
     * Totals, not deltas: the reader subtracts. Kept that way so two readers
     * cannot steal each other's numbers, and so a reader that misses a sample
     * still sees the whole truth in the next one.
     */
    fun totals(): LongArray = LongArray(Counter.entries.size) { counts.get(it) }
}