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

    private fun plan(a: TrackAnalysis, b: TrackAnalysis, phraseBars: Int = 0) = planTransition(
        analysis = a,
        nextAnalysis = b,
        currentTime = 100.0,
        duration = a.duration,
        mode = CrossfadeMode.SMART,
        advanced = true,
        phraseBars = phraseBars,
    )

    @Test
    fun aFixedPhraseWithNoMeasuredBarLinesFallsBackToTheAdaptivePlan() {
        val p = plan(track(120.0, downbeats = false), track(120.0), phraseBars = PHRASE_BARS_SHORT)
        // Falls back to a plan, not a refusal.
        assertTrue(!p.blocked, "blocked: ${p.reason}")
        assertTrue(!p.reason.contains("phrase-8-bar"), "reason ${p.reason}")
    }

    @Test
    fun anUntrustedOpeningGridGetsNoCloseTempoBlend() {
        val next = track(120.0).let { it.copy(headBeats = BeatSide(it.downbeats, it.downbeats, 120.0, 0.1)) }
        val p = plan(track(120.0), next)
        assertNotEquals(TransitionStyle.DJ_BLEND, p.transitionStyle)
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
        assertTrue(!p.blocked, "blocked: ${p.reason}")
        assertEquals(TransitionStyle.DJ_BLEND, p.transitionStyle)
        assertTrue(p.transitionEnd - p.transitionStart < 8.0, "overlap ${p.transitionEnd - p.transitionStart}")
    }

    @Test
    fun aBeatmatchedPhraseWithNoMeasuredCueFallsBackToTheAdaptivePlan() {
        // A trusted opening grid the model measured with no bar lines at all.
        val next = track(120.0).let { it.copy(headBeats = BeatSide(emptyList(), emptyList(), 120.0, 0.9)) }
        val p = plan(track(120.0), next, phraseBars = PHRASE_BARS_SHORT)
        assertTrue(!p.blocked, "blocked: ${p.reason}")
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

    @Test
    fun advancedNeedsBothGridsTrustedForACloseTempoBlend() {
        // Equal tempi; the incoming opening measured at 0.9, the outgoing grid at 0.1.
        val next = track(120.0).let { it.copy(headBeats = BeatSide(it.downbeats, it.downbeats, 120.0, 0.9)) }
        val p = plan(track(120.0).copy(beatConfidence = 0.1), next)
        assertNotEquals(TransitionStyle.DJ_BLEND, p.transitionStyle)
        // Both trusted: still a blend.
        assertEquals(TransitionStyle.DJ_BLEND, plan(track(120.0), next).transitionStyle)
    }

    @Test
    fun aBlendNeverCuesTheIncomingSongInsideItsLeadInSilence() {
        // The incoming song is silent until [start] and its drop comes a second
        // later: the blend's 8 s overlap reached back to start - 3 s.
        for (start in listOf(5.0, 10.0, 20.0)) {
            val next = track(120.0).copy(
                audibleStartTime = start,
                firstBeat = start,
                mixInTime = start + 1.0,
                mixInCandidates = listOf(MixCandidate(start + 1.0, 1.0, "drop")),
            )
            val p = plan(track(120.0), next)
            assertEquals(TransitionStyle.DJ_BLEND, p.transitionStyle, "start $start")
            assertTrue(p.incomingCueTime >= start - 1e-9, "start $start: cue ${p.incomingCueTime}")
        }
    }

    @Test
    fun aCorruptTempoNeverHangsThePlanner() {
        // Cached tempi that are infinite or so small their ratio underflows to 0.
        for (bad in listOf(Double.MIN_VALUE, Double.POSITIVE_INFINITY, Double.MAX_VALUE)) {
            plan(track(120.0), track(120.0).copy(bpm = bad))
            plan(track(120.0).copy(bpm = bad), track(120.0))
            val head = track(120.0).let { it.copy(headBeats = BeatSide(it.downbeats, it.downbeats, bad, 0.9)) }
            // An underflowed or infinite opening tempo leaves no grid to compare:
            // never a disagreement. MAX_VALUE has no defined answer; the plans
            // above and below cover that it doesn't hang.
            if (bad == Double.MIN_VALUE || bad == Double.POSITIVE_INFINITY) {
                assertTrue(!openingTempoDisagrees(head), "no grid to compare for $bad")
            }
            plan(track(120.0), head)
        }
    }

    @Test
    fun aPlainCrossfadeKeepsTheIncomingSkipLimit() {
        // An untrusted pair (plain crossfade) into a 60 s song silent until 20 s:
        // cued no further in than the 15 s every other plan may skip.
        val a = track(120.0).copy(beatConfidence = 0.0)
        val b = track(120.0, duration = 60.0).copy(beatConfidence = 0.0, audibleStartTime = 20.0, firstBeat = 20.0)
        val p = plan(a, b)
        assertTrue(p.incomingCueTime <= latestIncomingCue(60.0) + 1e-9, "cue ${p.incomingCueTime}")
    }

    @Test
    fun aMixInSnappedToTheOpeningDownbeatKeepsItsOwnTime() {
        // The best candidate sits just after a downbeat at 0 s: snapping would make it no drop at all.
        val next = track(120.0).copy(downbeats = listOf(0.0, 2.0, 4.0), mixInTime = 0.6,
            mixInCandidates = listOf(MixCandidate(0.6, 1.0, "drop")))
        val point = incomingMixInPoint(next)
        assertTrue(point != null && point > 0.0, "mix-in point $point")
    }

    @Test
    fun aFixedPhraseNeverEndsPastTheOutgoingSongsContentEnd() {
        // 120 BPM with a bar line that drifted early (98.3 s, not 98.5): the one
        // before the 100.4 s content end is out of reach, the phrase starts on a
        // boundary between bar lines (84.0 s), and the snap lands it on the bar
        // line at 100.5 s, eight bars from 84.5 s but past the content end.
        val bars = generateSequence(0.5) { it + 2.0 }.takeWhile { it < 98.0 }.toList() +
            listOf(98.3) + generateSequence(100.5) { it + 2.0 }.takeWhile { it < 110.0 }.toList()
        val outgoing = track(120.0, duration = 112.0).copy(
            downbeats = bars,
            phraseBoundaries = listOf(84.0),
            contentEndTime = 100.4,
        )
        val p = planTransition(
            analysis = outgoing,
            nextAnalysis = track(120.0),
            currentTime = 60.0,
            duration = outgoing.duration,
            mode = CrossfadeMode.SMART,
            advanced = true,
            phraseBars = PHRASE_BARS_SHORT,
        )
        assertTrue(p.transitionEnd <= 100.4 + 0.05, "ends at ${p.transitionEnd}: ${p.reason}")
    }

    private fun onOpeningGrid(grid: List<Double>, t: Double) = grid.any { kotlin.math.abs(it - t) < 1e-6 }

    private fun openingGridTrack(headDownbeats: List<Double>): TrackAnalysis {
        // Whole-track bar lines at 0.5 + 2k s, barely trusted; the opening's own
        // at 1.0 + 2k s, trusted: the pair is admitted on the opening's.
        val base = track(120.0).copy(beatConfidence = 0.1, mixInTime = 8.5,
            mixInCandidates = listOf(MixCandidate(8.5, 1.0, "drop")))
        return base.copy(headBeats = BeatSide(
            beats = headDownbeats.flatMap { listOf(it, it + 0.5, it + 1.0, it + 1.5) },
            downbeats = headDownbeats,
            bpm = 120.0,
            confidence = 0.9,
        ))
    }

    @Test
    fun aBeatmatchedBlendIsPhasedOnTheOpeningGridThatAdmittedIt() {
        val head = generateSequence(1.0) { it + 2.0 }.takeWhile { it < 60.0 }.toList()
        val next = openingGridTrack(head)
        val planned = planWsolaTransition(track(120.0), next, 200.0, 200.0, advanced = true)
        assertTrue(planned is WsolaPlanResult.Planned, "planned $planned")
        assertTrue(onOpeningGrid(head, planned.incomingDropTime), "drop ${planned.incomingDropTime}")
        assertTrue(onOpeningGrid(head, planned.incomingCueTime), "cue ${planned.incomingCueTime}")
    }

    @Test
    fun aDropWithNoOpeningBarLineNearItGetsNoBeatmatchedBlend() {
        // The opening grid stops at 5 s: nothing of it near the 8.5 s drop.
        val next = openingGridTrack(listOf(1.0, 3.0, 5.0))
        val planned = planWsolaTransition(track(120.0), next, 200.0, 200.0, advanced = true)
        assertEquals(WsolaPlanResult.Refused("incoming-head-grid"), planned)
    }

    @Test
    fun anAdaptiveBlendCuesOnTheOpeningGridItWasTrustedOn() {
        // Whole-track bar lines at 0.5 + 2k s; the opening's own, measured, at 0.75 + 2k s.
        val head = generateSequence(0.75) { it + 2.0 }.takeWhile { it < 40.0 }.toList()
        val next = track(120.0).copy(
            mixInTime = 16.5,
            mixInCandidates = listOf(MixCandidate(16.5, 1.0, "drop")),
            headBeats = BeatSide(head.flatMap { listOf(it, it + 0.5, it + 1.0, it + 1.5) }, head, 120.0, 0.9),
        )
        for (advanced in listOf(false, true)) {
            val p = planTransition(
                analysis = track(120.0),
                nextAnalysis = next,
                currentTime = 100.0,
                duration = 200.0,
                mode = CrossfadeMode.SMART,
                advanced = advanced,
            )
            assertEquals(TransitionStyle.DJ_BLEND, p.transitionStyle, "advanced=$advanced: ${p.reason}")
            val offBar = ((p.incomingCueTime - 0.75) % 2.0 + 2.0) % 2.0
            assertTrue(offBar < 1e-6 || offBar > 2.0 - 1e-6, "advanced=$advanced: cue ${p.incomingCueTime} is off the opening's bar lines")
        }
    }

    @Test
    fun aStrictSnapFindsTheBarLineInReachWhenANearerOneIsPastTheLimit() {
        // 120 BPM; the drop at 14.99 s, the cue limit 15.0 s, bar lines at 14.05 s and 15.90 s.
        val next = track(120.0).copy(
            downbeats = listOf(2.05, 6.05, 10.05, 14.05, 15.90),
            mixInTime = 14.99,
            mixInCandidates = listOf(MixCandidate(14.99, 1.0, "drop")),
        )
        assertEquals(14.05, incomingMixInPoint(next, latest = 15.0, grid = next.downbeats, strict = true))
    }
}
