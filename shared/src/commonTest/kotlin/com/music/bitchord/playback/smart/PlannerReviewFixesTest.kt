package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun aMatchedBlendWithClashingKeysTakesTheAdaptiveLengthNotTheWholeIntro() {
        // Same tempo, clashing keys: the adaptive sizing asks for a short blend,
        // and an 8 s run-up into the incoming drop must not stretch it to 12 s.
        val a = track(120.0).copy(key = "C major", keyConfidence = 0.9)
        val b = track(120.0).copy(key = "F♯ major", keyConfidence = 0.9, mixInTime = 8.5,
            mixInCandidates = listOf(MixCandidate(8.5, 1.0, "drop")))
        val p = plan(a, b)
        assertTrue(p.transitionEnd - p.transitionStart < 8.0, "overlap ${p.transitionEnd - p.transitionStart}")
    }

    @Test
    fun aBeatmatchedPhraseWithNoMeasuredCueFallsBackToTheAdaptivePlan() {
        // A trusted opening grid the model measured with no bar lines at all.
        val next = track(120.0).let { it.copy(headBeats = BeatSide(emptyList(), emptyList(), 120.0, 0.9)) }
        val p = plan(track(120.0), next, phraseBars = PHRASE_BARS_SHORT)
        assertTrue(!p.reason.contains("phrase-8-bar"), "reason ${p.reason}")
    }

    @Test
    fun anAdvancedCloseTempoBlendNeedsTheBeatMatchBarOnTheOpening() {
        val next = track(120.0).let { it.copy(headBeats = BeatSide(it.downbeats, it.downbeats, 120.0, 0.3)) }
        assertNotEquals(TransitionStyle.DJ_BLEND, plan(track(120.0), next).transitionStyle)
    }

    @Test
    fun aFixedPhraseCuedJustPastTheSkipLimitIsRejected() {
        // A 60 s incoming song may be cued at most 15 s in; its first bar line sits just past that.
        val next = track(120.0, duration = 60.0).let { t ->
            val bars = generateSequence(15.25) { it + 2.0 }.takeWhile { it < 59.0 }.toList()
            t.copy(downbeats = bars, phraseBoundaries = bars, firstBeat = 15.25, audibleStartTime = 15.2, mixInTime = bars[2])
        }
        val p = plan(track(120.0), next, phraseBars = PHRASE_BARS_SHORT)
        assertTrue(!p.reason.contains("phrase-8-bar"), "reason ${p.reason}")
        assertTrue(p.incomingCueTime <= latestIncomingCue(60.0), "cue ${p.incomingCueTime}")
    }

    /** [base] with a vocal mask on its energy curve: singing wherever [sung] says. */
    private fun singing(base: TrackAnalysis, sung: (Double) -> Boolean) =
        base.copy(vocalActivityMask = base.energyCurve.map { if (sung(it.time)) 1.0 else 0.0 })

    @Test
    fun theVocalOverlapIsMeasuredOnTheCueTheSkipCapLeaves() {
        // Too far apart in tempo to blend, so the filter mix cues the incoming song at its audible start, 20 s.
        // A 60 s song may be cued at most 15 s in: the cap moves the cue back over a vocal at 14-19 s.
        val out = singing(track(120.0)) { true }
        val inn = singing(track(160.0, duration = 60.0).copy(audibleStartTime = 20.0, mixInTime = 30.0)) { it in 14.0..19.0 }
        val p = plan(out, inn)
        assertTrue(p.incomingCueTime <= latestIncomingCue(60.0) + 1e-9, "cue ${p.incomingCueTime}")
        assertTrue(p.vocalOverlap > 0.0, "the vocal now in the overlap is counted: ${p.vocalOverlap}")
    }

    @Test
    fun aCueTheSkipCapLeavesAloneIsScoredWhereItIs() {
        // A 200 s song may be cued 50 s in: the 20 s cue stands, and the 14-19 s vocal stays out of the overlap.
        val out = singing(track(120.0)) { true }
        val inn = singing(track(160.0).copy(audibleStartTime = 20.0, mixInTime = 30.0)) { it in 14.0..19.0 }
        val p = plan(out, inn)
        assertEquals(20.0, p.incomingCueTime, 1e-9)
        assertEquals(0.0, p.vocalOverlap, 1e-9)
    }

    @Test
    fun anUnmixablePairsVocalOverlapIsMeasuredOnTheCappedCue() {
        // Unmixable: tempi no stretch meets, and both sides sung through.
        val out = singing(track(120.0).copy(vocalProbability = 0.9)) { true }
        val capped = singing(track(160.0, duration = 60.0).copy(audibleStartTime = 20.0, vocalProbability = 0.9)) { it in 14.0..19.0 }
        val moved = plan(out, capped)
        assertTrue("unmixable-pair" in moved.policyReasons, "reasons ${moved.policyReasons}")
        assertTrue(moved.incomingCueTime <= latestIncomingCue(60.0) + 1e-9, "cue ${moved.incomingCueTime}")
        assertTrue(moved.vocalOverlap > 0.0, "the vocal the cap brings into the overlap is counted: ${moved.vocalOverlap}")

        val kept = singing(track(160.0).copy(audibleStartTime = 20.0, vocalProbability = 0.9)) { it in 14.0..19.0 }
        val unchanged = plan(out, kept)
        assertTrue("unmixable-pair" in unchanged.policyReasons, "reasons ${unchanged.policyReasons}")
        assertEquals(20.0, unchanged.incomingCueTime, 1e-9)
        assertEquals(0.0, unchanged.vocalOverlap, 1e-9)
    }
}
