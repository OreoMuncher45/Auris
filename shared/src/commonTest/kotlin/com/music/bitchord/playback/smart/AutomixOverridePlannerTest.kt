package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a listener's own choice has to do to the plan.
 *
 * The planner's answer is the default because the planner knows the two
 * recordings. A choice is a request on top of that: it is honoured where it
 * can be executed, and where it cannot, the plan says what was actually done
 * rather than what was asked for.
 */
class AutomixOverridePlannerTest {

    /** A synthetic, fully analysed track on a steady grid. */
    private fun track(
        id: String,
        bpm: Double,
        key: String,
        vocal: Double,
        duration: Double = 300.0,
    ): TrackAnalysis {
        val beat = 60.0 / bpm
        val downbeats = generateSequence(0.5) { it + 4 * beat }.takeWhile { it < duration - 1 }.toList()
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

    /** A long, calm, matched pair — the shape in which the whole range fits. */
    private fun calmPair(): Pair<TrackAnalysis, TrackAnalysis> {
        val current = track("out", 120.0, "C major", vocal = 0.1).let {
            it.copy(
                energyCurve = it.energyCurve.map { sample ->
                    if (sample.time >= it.duration - 4.0) sample.copy(energy = 0.1) else sample
                },
            )
        }
        val next = track("in", 120.0, "C major", vocal = 0.1).let {
            it.copy(
                mixInTime = it.downbeats[37],
                energyCurve = it.energyCurve.map { sample ->
                    if (sample.time <= 4.0) sample.copy(energy = 0.1) else sample
                },
            )
        }
        return current to next
    }

    private fun plan(
        current: TrackAnalysis,
        next: TrackAnalysis,
        override: AutomixOverride? = null,
    ) = planTransition(
        analysis = current,
        nextAnalysis = next,
        currentTrack = TransitionTrackInfo(current.trackId, (current.duration * 1000).toLong()),
        nextTrack = TransitionTrackInfo(next.trackId, (next.duration * 1000).toLong()),
        currentTime = 10.0,
        duration = current.duration,
        mode = CrossfadeMode.SMART,
        advanced = true,
        override = override,
    )

    @Test
    fun theListenersSizeIsHonoured() {
        val (current, next) = calmPair()
        val plan = plan(current, next, AutomixOverride(bars = 32))
        assertEquals(32, plan.transitionBars)
        assertEquals(32 * BEATS_PER_BAR, plan.transitionBeats)
    }

    @Test
    fun aSizeBelowTheRangeComesBackAtFour() {
        // A stored two is a stale or hand-edited value, not a musical size.
        // The planner rounds it up to its own floor rather than ignoring the
        // choice altogether.
        val (current, next) = calmPair()
        val plan = plan(current, next, AutomixOverride(bars = 2))
        assertEquals(MIN_TRANSITION_BARS, plan.transitionBars)
    }

    @Test
    fun aSizeThatDoesNotFitComesBackShorterRatherThanNotAtAll() {
        val current = track("short-out", 120.0, "C major", vocal = 0.1, duration = 45.0)
        val next = track("short-in", 120.0, "C major", vocal = 0.1, duration = 45.0)
        val plan = plan(current, next, AutomixOverride(bars = 32))
        assertTrue(plan.transitionBars in MIN_TRANSITION_BARS until MAX_TRANSITION_BARS)
        assertTrue(!plan.blocked, "a request that does not fit is still a transition")
    }

    @Test
    fun theListenersBlendIsHonouredWhereItCanBeExecuted() {
        val (current, next) = calmPair()
        val plan = plan(current, next, AutomixOverride(style = TransitionStyle.DJ_BLEND))
        assertEquals(TransitionStyle.DJ_BLEND, plan.transitionStyle)
    }

    @Test
    fun aBlendThatCannotBeExecutedComesBackAsWhatWasDone() {
        // Two grids that cannot be matched have no shared beat to blend on, so
        // the plan says the filtered handoff rather than claiming a blend it
        // did not render.
        val current = track("far-out", 120.0, "C major", vocal = 0.1)
        val next = track("far-in", 97.0, "F♯ major", vocal = 0.1)
        val plan = plan(current, next, AutomixOverride(style = TransitionStyle.DJ_BLEND))
        assertEquals(TransitionStyle.DJ_FILTER, plan.transitionStyle)
    }

    @Test
    fun theToneShapeTravelsWithThePlan() {
        val (current, next) = calmPair()
        val plan = plan(current, next, AutomixOverride(eq = TransitionEqShape.BRIGHT))
        assertEquals(TransitionEqShape.BRIGHT, plan.eqShape)
    }

    @Test
    fun aFlatToneShapeIsNoToneShape() {
        val (current, next) = calmPair()
        val plan = plan(current, next, AutomixOverride(eq = TransitionEqShape.FLAT))
        assertNull(plan.eqShape, "a flat shape is the same as no shape")
    }

    @Test
    fun theEffectWinsOverTheFilterShape() {
        // An echo tail is a filter ride; asking for one and being given a plain
        // sweep would be a choice quietly ignored.
        val current = track("echo-out", 120.0, "C major", vocal = 0.1)
        val next = track("echo-in", 97.0, "F♯ major", vocal = 0.1)
        val plan = plan(
            current,
            next,
            AutomixOverride(
                filter = FilterTransitionVariant.SWEEP,
                effects = AutomixEffect.ECHO_TAIL,
            ),
        )
        assertEquals(TransitionStyle.DJ_FILTER, plan.transitionStyle)
        assertEquals(FilterTransitionVariant.ECHO_RIDE, plan.filterVariant)
    }

    @Test
    fun askingForNoEffectTakesTheEchoBackOut() {
        val current = track("plain-out", 120.0, "C major", vocal = 0.1)
        val next = track("plain-in", 97.0, "F♯ major", vocal = 0.1)
        val plan = plan(
            current,
            next,
            AutomixOverride(
                filter = FilterTransitionVariant.ECHO_RIDE,
                effects = AutomixEffect.NONE,
            ),
        )
        assertEquals(FilterTransitionVariant.SWEEP, plan.filterVariant)
    }

    @Test
    fun notThisSongLeavesTheTransitionToThePlainCrossfade() {
        val (current, next) = calmPair()
        val plan = plan(current, next, AutomixOverride(enabled = false))
        assertEquals(TransitionStyle.EQUAL_POWER, plan.transitionStyle)
        assertEquals(0, plan.transitionBars, "no beat-matched overlap at all")
        assertTrue(plan.reason.contains("automix-off-for-track"), plan.reason)
    }

    @Test
    fun aChosenStartIsRefusedWhenNoLegalOverlapFitsBehindIt() {
        val (current, next) = calmPair()
        // A start so late that there is no room for four whole bars before the
        // mix point: the planner's own placement stands.
        val plan = plan(current, next, AutomixOverride(transitionStart = current.duration - 1.0))
        assertTrue(!plan.blocked)
        assertEquals(plan.transitionBars * BEATS_PER_BAR, plan.transitionBeats)
        assertNotNull(plan.transitionStart)
    }
}
