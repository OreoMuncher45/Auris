package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The fixed-length phrase mode: when the listener picks 8 or 16 bars, the overlap is exactly that
 * many bars of the outgoing grid with both edges on the beat, and picking nothing (0) leaves the
 * adaptive plan untouched.
 */
class PhraseBarAutomixTest {

    /** A synthetic, fully analysed track on a steady grid, matching [AdvancedAutomixPlannerTest]. */
    private fun track(
        id: String,
        bpm: Double,
        key: String,
        vocal: Double,
        duration: Double = 200.0,
        /** How much longer each bar runs past 150 s: a tempo that eases off near the end. */
        lateBarStretch: Double = 1.0,
    ): TrackAnalysis {
        val beat = 60.0 / bpm
        val downbeats = generateSequence(0.5) { it + 4 * beat * (if (it >= 150.0) lateBarStretch else 1.0) }
            .takeWhile { it < duration - 1 }.toList()
        val energy = (0 until (duration * 2).toInt()).map { EnergySample(it / 2.0, 1.0) }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = id,
            duration = duration,
            bpm = bpm,
            beatInterval = beat,
            beatConfidence = 0.9,
            downbeats = downbeats,
            phraseBoundaries = downbeats.filterIndexed { index, _ -> index % 8 == 0 },
            firstBeat = 0.5,
            key = key,
            keyConfidence = 0.9,
            audibleStartTime = 0.5,
            contentEndTime = duration - 2,
            mixInTime = downbeats[4],
            energyCurve = energy,
            vocalProbability = vocal,
        )
    }

    private fun plan(current: TrackAnalysis, next: TrackAnalysis, phraseBars: Int) = planTransition(
        analysis = current,
        nextAnalysis = next,
        currentTrack = TransitionTrackInfo(current.trackId, (current.duration * 1000).toLong()),
        nextTrack = TransitionTrackInfo(next.trackId, (next.duration * 1000).toLong()),
        currentTime = 10.0,
        duration = current.duration,
        mode = CrossfadeMode.SMART,
        advanced = true,
        phraseBars = phraseBars,
    )

    @Test
    fun eightBarsIsExactlyEightBarsOnTheGrid() {
        val current = track("a", 120.0, "C major", vocal = 0.1)
        val next = track("b", 120.0, "C major", vocal = 0.1)
        val plan = plan(current, next, PHRASE_BARS_SHORT)

        assertEquals(TransitionStyle.DJ_BLEND, plan.transitionStyle, "matched tempi should beat-match")
        assertEquals(32, plan.transitionBeats, "8 bars of 4/4 is 32 beats")
        val bars = plan.fadeSeconds / (4 * (60.0 / 120.0))
        assertEquals(8.0, bars, 1e-6, "overlap of $bars bars")
        assertEquals(plan.fadeSeconds, plan.transitionEnd - plan.transitionStart, 1e-6)
        assertTrue(
            current.downbeats.any { kotlin.math.abs(it - plan.transitionStart) < 1e-6 },
            "the mix should open on a downbeat: ${plan.transitionStart}",
        )
        assertTrue(plan.transitionEnd <= current.duration + 1e-6)
        assertTrue(
            current.downbeats.any { kotlin.math.abs(it - plan.transitionEnd) < 1e-6 },
            "the mix should close on a downbeat: ${plan.transitionEnd}",
        )
    }

    @Test
    fun bothEdgesLandOnMeasuredDownbeatsWhenTheTempoDrifts() {
        // Bars 1% long near the end: eight nominal bars from the opening
        // downbeat fall 0.16 s short of the eighth measured one.
        val current = track("a", 120.0, "C major", vocal = 0.1, lateBarStretch = 1.01)
        val next = track("b", 120.0, "C major", vocal = 0.1)
        val plan = plan(current, next, PHRASE_BARS_SHORT)

        assertEquals("before-phrase-8-bar", plan.reason, "the phrase mode should take the pair")
        assertTrue(
            current.downbeats.any { kotlin.math.abs(it - plan.transitionStart) < 1e-6 },
            "the mix should open on a downbeat: ${plan.transitionStart}",
        )
        assertTrue(
            current.downbeats.any { kotlin.math.abs(it - plan.transitionEnd) < 1e-6 },
            "the mix should close on a downbeat: ${plan.transitionEnd}",
        )
        val opening = current.downbeats.indexOfFirst { kotlin.math.abs(it - plan.transitionStart) < 1e-6 }
        assertEquals(current.downbeats[opening + 8], plan.transitionEnd, 1e-6, "eight measured bars")
    }

    @Test
    fun sevenLongBarsAreNotPassedOffAsEight() {
        // Past 150 s each bar runs 8/7 as long, so the 7th measured bar line sits
        // exactly 8 nominal bars on. That is 7 bars, not the 8 asked for.
        val current = track("a", 120.0, "C major", vocal = 0.1, lateBarStretch = 8.0 / 7.0)
        val next = track("b", 120.0, "C major", vocal = 0.1)
        val plan = plan(current, next, PHRASE_BARS_SHORT)
        if (plan.reason.endsWith("phrase-8-bar")) {
            val start = current.downbeats.indexOfFirst { kotlin.math.abs(it - plan.transitionStart) < 1e-6 }
            val end = current.downbeats.indexOfFirst { kotlin.math.abs(it - plan.transitionEnd) < 1e-6 }
            kotlin.test.assertTrue(start >= 0 && end >= 0, "both phrase edges must be measured downbeats")
            assertEquals(PHRASE_BARS_SHORT, end - start, "a phrase plan must span 8 measured bars")
        }
    }

    @Test
    fun aMatchedPhraseMixCuesTheIncomingSongOnItsFirstDownbeat() {
        // Sound from 0.25 s, bar one at 1.0 s: cueing at the first sound would put
        // the incoming bar line 0.75 s off the outgoing one for the whole mix.
        val current = track("a", 120.0, "C major", vocal = 0.1)
        val base = track("b", 120.0, "C major", vocal = 0.1)
        val next = base.copy(audibleStartTime = 0.25, downbeats = base.downbeats.map { it + 0.5 })
        val plan = plan(current, next, PHRASE_BARS_SHORT)
        assertEquals("before-phrase-8-bar", plan.reason)
        assertEquals(1.0, plan.incomingCueTime, 1e-9)
    }

    @Test
    fun anUntrustedOutgoingGridGetsTheAdaptivePlan() {
        val current = track("a", 120.0, "C major", vocal = 0.1).copy(beatConfidence = 0.1)
        val next = track("b", 120.0, "C major", vocal = 0.1)
        assertTrue(!plan(current, next, PHRASE_BARS_SHORT).reason.endsWith("phrase-8-bar"))
    }

    @Test
    fun sixteenBarsIsExactlySixteenBarsOnTheGrid() {
        val current = track("a", 120.0, "C major", vocal = 0.1)
        val next = track("b", 120.0, "C major", vocal = 0.1)
        val plan = plan(current, next, PHRASE_BARS_LONG)

        assertEquals(64, plan.transitionBeats, "16 bars of 4/4 is 64 beats")
        val bars = plan.fadeSeconds / (4 * (60.0 / 120.0))
        assertEquals(16.0, bars, 1e-6, "overlap of $bars bars")
        assertTrue(
            plan.fadeSeconds > MAX_OVERLAP_SECONDS_FOR_TEST,
            "a 16-bar mix at 120 BPM runs 32s, past the adaptive 16s ceiling",
        )
    }

    @Test
    fun aSlowerIncomingSongIsStillSpedUpToMatchUnderPhraseMode() {
        val current = track("a", 120.0, "C major", vocal = 0.1)
        val next = track("b", 111.0, "C major", vocal = 0.1)
        val plan = plan(current, next, PHRASE_BARS_SHORT)
        assertEquals(TransitionStyle.DJ_BLEND, plan.transitionStyle)
        assertEquals(120.0 / 111.0, plan.incomingPlaybackRate, 1e-3)
        assertEquals(1.0, plan.outgoingPlaybackRate, "an audible deck must never change speed")
    }

    @Test
    fun choosingNothingLeavesTheAdaptivePlanUntouched() {
        val current = track("a", 120.0, "C major", vocal = 0.1)
        val next = track("b", 110.0, "G major", vocal = 0.1)
        val adaptive = planTransition(
            analysis = current,
            nextAnalysis = next,
            currentTrack = TransitionTrackInfo("a", 200_000),
            nextTrack = TransitionTrackInfo("b", 200_000),
            currentTime = 10.0,
            duration = 200.0,
            mode = CrossfadeMode.SMART,
            advanced = true,
        )
        assertEquals(adaptive, plan(current, next, phraseBars = 0))
    }

    @Test
    fun theAdaptivePlanReportsTheBeatsItActuallySchedules() {
        // A short incoming song holds the overlap to a fraction of its length,
        // far under the beats the planner first asks for.
        val current = track("a", 120.0, "C major", vocal = 0.1)
        val next = track("b", 120.0, "C major", vocal = 0.1, duration = 12.0)
        val plan = plan(current, next, phraseBars = 0)

        val scheduled = plan.transitionEnd - plan.transitionStart
        assertTrue(scheduled > 0 && scheduled < 6.0, "a short mix: $scheduled s (${plan.reason})")
        assertEquals(kotlin.math.round(scheduled / 0.5).toInt(), plan.transitionBeats, "beats over $scheduled s")
    }

    private companion object {
        /** The adaptive planner's ceiling; the phrase mode deliberately runs past it. */
        const val MAX_OVERLAP_SECONDS_FOR_TEST = 16.0
    }

    @Test
    fun aPhraseWhoseStartMissesTheMeasuredBarLinesIsSnappedOntoThem() {
        // Phrase boundaries a quarter beat off the measured bar lines: the start
        // the planner aligns to is unmeasured, the end it would reach is measured.
        // A phrase boundary a quarter beat after every bar line: the start the
        // planner aligns to is never a measured downbeat (more than beat/8 off).
        val base = track("a", 120.0, "C major", vocal = 0.1)
        val current = base.copy(phraseBoundaries = base.downbeats.map { it + 0.125 })
        val next = track("b", 120.0, "C major", vocal = 0.1)
        val plan = plan(current, next, PHRASE_BARS_SHORT)
        kotlin.test.assertTrue(plan.reason.endsWith("phrase-8-bar"), "a fixed phrase is still planned: ${plan.reason}")
        val start = current.downbeats.indexOfFirst { kotlin.math.abs(it - plan.transitionStart) < 1e-6 }
        val end = current.downbeats.indexOfFirst { kotlin.math.abs(it - plan.transitionEnd) < 1e-6 }
        kotlin.test.assertTrue(start >= 0 && end >= 0, "both phrase edges must be measured downbeats")
        assertEquals(PHRASE_BARS_SHORT, end - start)
    }
}
