package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlannerReviewFixesTest {
    private fun track(bpm: Double, duration: Double = 200.0, downbeats: Boolean = true): TrackAnalysis {
        val beat = 60.0 / bpm
        val bars = if (downbeats) generateSequence(0.5) { it + 4 * beat }.takeWhile { it < duration - 1 }.toList() else emptyList()
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            duration = duration,
            bpm = bpm,
            beatInterval = beat,
            beatConfidence = 0.9,
            downbeats = bars,
            phraseBoundaries = bars.filterIndexed { i, _ -> i % 8 == 0 },
            firstBeat = 0.5,
            audibleStartTime = 0.5,
            contentEndTime = duration - 2,
            mixInTime = bars.getOrNull(4) ?: 8.5,
            energyCurve = (0 until (duration * 2).toInt()).map { EnergySample(it / 2.0, 1.0) },
        )
    }

    private fun plan(a: TrackAnalysis, b: TrackAnalysis, phraseBars: Int = 0, mixWindow: Boolean = false) = planTransition(
        analysis = a,
        nextAnalysis = b,
        currentTime = 100.0,
        duration = a.duration,
        mode = CrossfadeMode.SMART,
        advanced = true,
        phraseBars = phraseBars,
        mixWindow = mixWindow,
    )

    @Test
    fun aFixedPhraseWithNoMeasuredBarLinesFallsBackToTheAdaptivePlan() {
        val p = plan(track(120.0, downbeats = false), track(120.0), phraseBars = PHRASE_BARS_SHORT)
        assertTrue(!p.reason.contains("phrase-8-bar"), "reason ${p.reason}")
    }

    @Test
    fun anUntrustedOpeningGridGetsNoCloseTempoBlend() {
        val next = track(120.0).let { it.copy(headBeats = BeatSide(it.downbeats, it.downbeats, 120.0, 0.1)) }
        val p = plan(track(120.0), next)
        assertNotEquals(TransitionStyle.DJ_BLEND, p.transitionStyle)
    }

    @Test
    fun theMixWindowBoundsTheIncomingOverlapToo() {
        val next = track(120.0).copy(mixInTime = 28.5, audibleStartTime = 20.0)
        val p = plan(track(120.0), next, mixWindow = true)
        if (!p.blocked) {
            val incomingEnd = p.incomingCueTime + (p.transitionEnd - p.transitionStart) * p.incomingPlaybackRate
            assertTrue(incomingEnd <= 30.0 + 1e-6, "incoming overlap ends at $incomingEnd")
            assertTrue(p.handoffDuration <= p.transitionEnd - p.transitionStart + 1e-9)
        }
    }

    @Test
    fun aDropRankedAtTheVeryStartGivesWayToTheNextOne() {
        val next = track(120.0).copy(
            mixInTime = 0.0,
            mixInCandidates = listOf(MixCandidate(0.0, 1.0, "drop"), MixCandidate(16.5, 0.2, "drop")),
        )
        val point = incomingMixInPoint(next)
        assertTrue(point != null && point > 0.0, "mix-in point $point")
    }
}
