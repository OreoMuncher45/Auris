package com.music.bitchord

import com.music.bitchord.ui.player.LyricClock
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricClockTest {
    /**
     * A song playing at [rate] from [startMs], polled every 500 ms. Each poll
     * is read off the true playhead and then delivered up to [maxDelayMs] late,
     * landing on the first frame after it arrives — the way a MediaController
     * read written into snapshot state reaches the frame loop.
     */
    private class Playback(
        val startMs: Long = 44_500L,
        val rate: Double = 1.0,
        val maxDelayMs: Double = 60.0,
        seed: Int = 7,
    ) {
        private val random = Random(seed)
        private var nextPollMs = 0.0
        private val inFlight = ArrayDeque<Pair<Double, Long>>()
        var report: Long = startMs
            private set

        fun trueAt(frameMs: Double) = startMs + frameMs * rate

        /** Advances deliveries to [frameMs] and returns the report the frame sees. */
        fun reportAt(frameMs: Double): Long {
            while (nextPollMs <= frameMs) {
                val sampled = trueAt(nextPollMs).toLong()
                inFlight.addLast(nextPollMs + random.nextDouble() * maxDelayMs to sampled)
                nextPollMs += 500.0
            }
            while (inFlight.isNotEmpty() && inFlight.first().first <= frameMs) {
                report = inFlight.removeFirst().second
            }
            return report
        }
    }

    private data class Run(val frames: List<Double>, val shown: List<Long>, val truth: List<Double>)

    private fun run(playback: Playback, seconds: Double, frameMs: Double = 1000.0 / 60): Run {
        val clock = LyricClock(playback.startMs)
        val frames = ArrayList<Double>()
        val shown = ArrayList<Long>()
        val truth = ArrayList<Double>()
        var t = 0.0
        while (t <= seconds * 1000) {
            frames += t
            shown += clock.frame(t, playback.reportAt(t))
            truth += playback.trueAt(t)
            t += frameMs
        }
        return Run(frames, shown, truth)
    }

    @Test fun jitteryReportsNeverMoveTheSweepBackwards() {
        val run = run(Playback(maxDelayMs = 120.0), seconds = 60.0)
        for (i in 1 until run.shown.size) {
            assertTrue("went back at frame $i", run.shown[i] >= run.shown[i - 1])
        }
    }

    /** The regression this replaces: a `maxOf` ratchet froze the sweep for up to a second. */
    @Test fun jitteryReportsNeverStallTheSweep() {
        val run = run(Playback(maxDelayMs = 120.0), seconds = 60.0)
        // Over any quarter second the sweep covers at least 70 % of it.
        val window = 15
        for (i in window until run.shown.size) {
            val moved = run.shown[i] - run.shown[i - window]
            val elapsed = run.frames[i] - run.frames[i - window]
            assertTrue("stalled near frame $i: $moved ms in $elapsed", moved >= elapsed * 0.7)
        }
    }

    @Test fun staysOnTheSongThroughJitter() {
        val run = run(Playback(maxDelayMs = 60.0), seconds = 30.0)
        for (i in run.frames.indices) {
            if (run.frames[i] < 2_000) continue
            val error = run.shown[i] - run.truth[i]
            // Behind by up to the mean delivery delay is expected; ahead is not.
            assertTrue("off by $error at ${run.frames[i]}", error in -80.0..20.0)
        }
    }

    @Test fun learnsAFasterPlaybackRate() {
        val run = run(Playback(rate = 1.25), seconds = 30.0)
        for (i in run.frames.indices) {
            if (run.frames[i] < 6_000) continue
            val error = run.shown[i] - run.truth[i]
            assertTrue("off by $error at ${run.frames[i]}", abs(error) < 90.0)
        }
        for (i in 1 until run.shown.size) assertTrue(run.shown[i] >= run.shown[i - 1])
    }

    @Test fun anEarlyOutlierDoesNotFreezeTheSweep() {
        val clock = LyricClock(10_000)
        var shown = 0L
        var previous = clock.frame(0.0, 10_000)
        var t = 0.0
        var report = 10_000L
        while (t < 6_000) {
            t += 1000.0 / 60
            val due = (t / 500).toLong() * 500
            // Every poll on time, except one that claims 200 ms more than it should.
            report = 10_000 + due + if (due == 3_000L) 200 else 0
            shown = clock.frame(t, report)
            assertTrue("stalled at $t", shown - previous >= 5)
            previous = shown
        }
        assertTrue(abs(shown - (10_000 + t)) < 40)
    }

    @Test fun backwardSeekFarAwayJumpsAtOnce() {
        val clock = LyricClock(30_000)
        clock.frame(0.0, 30_000)
        clock.frame(16.0, 30_000)
        assertEquals(4_000L, clock.frame(33.0, 4_000))
    }

    @Test fun forwardSeekFarAwayJumpsAtOnce() {
        val clock = LyricClock(10_000)
        clock.frame(0.0, 10_000)
        clock.frame(16.0, 10_000)
        assertEquals(40_000L, clock.frame(33.0, 40_000))
    }

    /** Tapping the line before this one: the words roll back, they do not cut. */
    @Test fun nearbySeekGlidesAndLands() {
        val clock = LyricClock(20_000)
        var t = 0.0
        var shown = clock.frame(t, 20_000)
        while (t < 1_000) {
            t += 1000.0 / 60
            shown = clock.frame(t, 20_000 + (t / 500).toLong() * 500)
        }
        val seekAt = t
        // A second back from the ~21 s it has reached.
        val target = 20_000L
        var largestStep = 0L
        while (t < seekAt + 400) {
            t += 1000.0 / 60
            val next = clock.frame(t, target)
            largestStep = maxOf(largestStep, abs(next - shown))
            shown = next
        }
        assertTrue("cut rather than glided: $largestStep ms in a frame", largestStep < 150)
        val expected = target + (t - (seekAt + 1000.0 / 60))
        assertTrue("never landed: $shown vs $expected", abs(shown - expected) < 30)
    }

    @Test fun holdSettlesAtOnce() {
        val clock = LyricClock(10_000)
        clock.frame(0.0, 10_000)
        clock.frame(400.0, 10_400)
        clock.hold(10_380)
        assertEquals(10_380.0, clock.displayedMs, 0.0)
    }

    /** A pause or a spell in the background is not one very long frame. */
    @Test fun restartDoesNotReadTheGapAsPlayback() {
        val clock = LyricClock(10_000)
        clock.frame(0.0, 10_000)
        clock.frame(16.0, 10_016)
        clock.restart()
        assertEquals(12_000L, clock.frame(60_000.0, 12_000))
        assertEquals(12_016L, clock.frame(60_016.0, 12_000))
    }

    /** The dense handovers that the reconciler's own test reproduced, now frame by frame. */
    @Test fun lineHandoversNeverGoBack() {
        val lineStarts = listOf(44_754L, 48_000L, 51_104L, 54_158L)
        val run = run(Playback(startMs = 44_500L, maxDelayMs = 250.0, seed = 3), seconds = 12.0)
        var previousLine = -1
        for (shown in run.shown) {
            val line = lineStarts.indexOfLast { it <= shown }
            assertTrue(line >= previousLine)
            previousLine = line
        }
    }
}
