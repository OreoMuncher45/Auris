package com.music.bitchord.ui.player

import kotlin.math.abs

/**
 * The lyric playhead: a clock that runs on its own frames and is *steered* by
 * the player's position reports rather than set by them.
 *
 * The player reports where it is twice a second, and every report is a little
 * wrong — taken a few milliseconds before it is delivered, delivered on
 * whatever frame happens to be next, and rebased by the media controller
 * whenever the session sends it fresh state. Both earlier clocks treated each
 * report as the truth and differed only in what they did when it disagreed:
 *
 *  - snapping to it moved the sweep backwards and forwards with the jitter;
 *  - refusing to go backwards (a `maxOf` ratchet) froze the sweep until the
 *    song caught up with the highest report ever seen, which is the "stuck for
 *    a second" — one early report and every word waits for it.
 *
 * This is what am-lyrics gets from the browser for free. Its wipes are CSS
 * animations running on the compositor's own clock; the host's timestamp only
 * touches them when the two disagree by a lot, because, in their words,
 * repeated `currentTime` writes pin the motion to the host's tick rate. The
 * native equivalent is the same split:
 *
 *  1. **Target.** The reports are fitted with a straight line over the last
 *     few seconds, so a single late or early one moves the estimate by a
 *     fraction of its error rather than all of it. The slope is the playback
 *     rate, which is how a song at 1.25× stays in step without being told.
 *  2. **Display.** Advances by the frame's own duration at that rate, and
 *     closes whatever gap remains to the target by running up to
 *     [MAX_SLEW] faster or slower — never by stopping, and never backwards.
 *     A drift of tens of milliseconds is gone within a second and no step in
 *     the sweep ever shows it.
 *  3. **Discontinuities.** A gap too big to slew away is a seek, or a player
 *     that has genuinely moved. Up to [SNAP_MS] it is eased over [GLIDE_MS]
 *     (am-lyrics' rewind, which is what makes tapping the line before this one
 *     roll the words back rather than cut); past that it is a jump to another
 *     part of the song and is taken at once.
 *
 * Plain arithmetic with no Compose in it, so the behaviour can be tested frame
 * by frame; [rememberLyricClock] is the composable that drives it.
 */
internal class LyricClock(startMs: Long) {
    /** Where the lyrics are drawn, in song milliseconds. */
    var displayedMs: Double = startMs.toDouble()
        private set

    private var lastFrameMs = Double.NaN
    private var lastReportMs = Long.MIN_VALUE

    // The fit window, oldest first: when each report was first seen, on the
    // frame clock, and what it said.
    private val seenAt = DoubleArray(WINDOW_REPORTS)
    private val reported = DoubleArray(WINDOW_REPORTS)
    private var count = 0

    // The fitted line, as a centre point and a slope through it.
    private var meanSeenAt = 0.0
    private var meanReported = 0.0
    private var slope = 1.0

    /**
     * The last rate a long enough window settled on. A fresh window — after a
     * seek, or the first second of a run — borrows it until it has enough
     * reports of its own to say.
     */
    private var learnedRate = 1.0

    private var glideStartMs = Double.NaN
    private var glideOffsetMs = 0.0

    /**
     * Forget the run so far. The next [frame] starts the display on the report
     * it is handed — called whenever frames stop arriving, so a pause, or a
     * spell in the background, is not read as a frame that took minutes.
     */
    fun restart() {
        lastFrameMs = Double.NaN
        lastReportMs = Long.MIN_VALUE
        count = 0
        glideStartMs = Double.NaN
    }

    /** Settle on [positionMs] outright: playback is not moving, so there is nothing to slew. */
    fun hold(positionMs: Long) {
        restart()
        displayedMs = positionMs.toDouble()
    }

    /**
     * Advance to the frame at [frameMs] with the player's latest report in
     * hand, and return where the lyrics should be drawn. A report only counts
     * as new when its value changes; the same value handed back frame after
     * frame is the same report.
     */
    fun frame(frameMs: Double, reportedMs: Long): Long {
        if (lastFrameMs.isNaN()) {
            lastFrameMs = frameMs
            lastReportMs = reportedMs
            record(frameMs, reportedMs)
            displayedMs = reportedMs.toDouble()
            return reportedMs
        }

        if (reportedMs != lastReportMs) {
            lastReportMs = reportedMs
            // Judged against the fit *before* this report joins it. Far off the
            // line is not jitter, it is the song somewhere else; the reports
            // from before it describe a playhead that no longer exists.
            if (abs(reportedMs - targetAt(frameMs)) > DISCONTINUITY_MS) {
                count = 0
                glideStartMs = Double.NaN
            }
            record(frameMs, reportedMs)
        }

        val dt = (frameMs - lastFrameMs).coerceAtLeast(0.0)
        lastFrameMs = frameMs
        val target = targetAt(frameMs)
        val error = target - displayedMs

        when {
            abs(error) >= SNAP_MS -> {
                displayedMs = target
                glideStartMs = Double.NaN
            }
            !glideStartMs.isNaN() -> {
                val progress = (frameMs - glideStartMs) / GLIDE_MS
                if (progress >= 1.0) {
                    displayedMs = target
                    glideStartMs = Double.NaN
                } else {
                    // The offset eases out while the target keeps moving, so the
                    // glide lands on a playhead that is still running rather than
                    // on where it was when the glide began.
                    displayedMs = target + glideOffsetMs * (1.0 - smoothstep(progress))
                }
            }
            abs(error) >= GLIDE_FROM_MS -> {
                // Started a frame back so this frame already moves. Starting on
                // it would spend the frame on progress zero: a one-frame hold,
                // exactly the kind of hitch this exists to remove.
                glideStartMs = frameMs - dt
                glideOffsetMs = displayedMs - (target - dt * slope)
                val progress = dt / GLIDE_MS
                displayedMs = target + glideOffsetMs * (1.0 - smoothstep(progress))
            }
            else -> {
                val correction = (error / CORRECTION_MS).coerceIn(-MAX_SLEW, MAX_SLEW)
                displayedMs += dt * slope * (1.0 + correction)
            }
        }
        return displayedMs.toLong()
    }

    /** Where the fitted line says the song is at [frameMs]. */
    private fun targetAt(frameMs: Double): Double =
        if (count == 0) displayedMs else meanReported + (frameMs - meanSeenAt) * slope

    private fun record(frameMs: Double, reportedMs: Long) {
        // Age out first, so a report from before a long gap is never what the
        // newest one is averaged with.
        var drop = 0
        while (drop < count && frameMs - seenAt[drop] > WINDOW_MS) drop++
        if (count - drop >= WINDOW_REPORTS) drop = count - WINDOW_REPORTS + 1
        if (drop > 0) {
            seenAt.copyInto(seenAt, 0, drop, count)
            reported.copyInto(reported, 0, drop, count)
            count -= drop
        }
        seenAt[count] = frameMs
        reported[count] = reportedMs.toDouble()
        count++
        fit()
    }

    private fun fit() {
        var sumT = 0.0
        var sumY = 0.0
        for (i in 0 until count) {
            sumT += seenAt[i]
            sumY += reported[i]
        }
        meanSeenAt = sumT / count
        meanReported = sumY / count

        val span = seenAt[count - 1] - seenAt[0]
        if (count >= MIN_FIT_REPORTS && span >= MIN_FIT_SPAN_MS) {
            var covariance = 0.0
            var variance = 0.0
            for (i in 0 until count) {
                val dt = seenAt[i] - meanSeenAt
                covariance += dt * (reported[i] - meanReported)
                variance += dt * dt
            }
            if (variance > 0.0) {
                learnedRate = (covariance / variance).coerceIn(MIN_RATE, MAX_RATE)
            }
        }
        slope = learnedRate
    }
}

private fun smoothstep(fraction: Double): Double {
    val t = fraction.coerceIn(0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)
}

/** How far back the fit looks, and the most reports it holds. */
private const val WINDOW_MS = 6_000.0
private const val WINDOW_REPORTS = 16

/**
 * A slope from fewer reports than this, or a shorter stretch, is mostly the
 * jitter of the frame each one happened to arrive on.
 */
private const val MIN_FIT_REPORTS = 3
private const val MIN_FIT_SPAN_MS = 2_000.0

/** Playback rates the fit is allowed to conclude; a rate outside these is a bad fit. */
private const val MIN_RATE = 0.25
private const val MAX_RATE = 4.0

/**
 * A report this far off the fitted line starts a new fit. Four times anything a
 * delayed poll or a controller rebase produces, and well inside the gap a tap
 * on the neighbouring line seeks by.
 */
private const val DISCONTINUITY_MS = 400.0

/**
 * The gap closes at [CORRECTION_MS]'s worth of rate per millisecond of error —
 * 50 ms behind runs about 8 % fast — capped at [MAX_SLEW] either way. A sweep's
 * speed changes far more than that from one word to the next, so the
 * correction never reads as anything.
 */
private const val CORRECTION_MS = 600.0
private const val MAX_SLEW = 0.25

/** A gap this wide is eased over [GLIDE_MS] instead of slewed. */
private const val GLIDE_FROM_MS = 300.0

/** am-lyrics eases its rewinds over 260 ms with the same smoothstep. */
private const val GLIDE_MS = 260.0

/** A gap this wide is another part of the song, and is jumped to. */
private const val SNAP_MS = 2_000.0
