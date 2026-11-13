package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pair that lost its Automix.
 *
 * A streamed track is measured in pieces: its opening from a head pass, its
 * ending from a tail pass. The pair below is the shape those two passes
 * actually produce for two tracks whose tempi are within five per cent of each
 * other — and the planner used to refuse that pair outright.
 *
 * ## What went wrong
 *
 * A beat-locked blend is cued so that the arriving track's arrangement lands
 * at the blend's end, so the arriving track has to have room between the
 * moment it starts making sound and that arrival: its **runway**. The runway
 * cap only applied to pairs whose tempi were within five per cent — the pairs
 * a shared grid was going to make work beautifully — so the better the tempo
 * match, the more certainly the transition was refused.
 *
 * A refused plan is not a plan with no window: it is a *blocked* plan, which
 * carries no marker at all and which the controller arms nothing for. So the
 * pair got no Automix window in the seek bar and no Automix, ever, while the
 * analysis behind it was perfect.
 */
class AutomixRunwayPlannerTest {

    /** The outgoing track: a head pass and a tail pass, merged the way the analyser merges them. */
    private fun outgoing(
        id: String = "2OBMMgybDkg",
        bpm: Double = 119.0,
        key: String = "B minor",
        duration: Double = 253.0,
    ): TrackAnalysis {
        val beat = 60.0 / bpm
        val head = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = id,
            duration = duration,
            bpm = bpm,
            beatInterval = beat,
            beatConfidence = 0.8,
            downbeats = generateSequence(0.5) { it + 4 * beat }.takeWhile { it < 30.0 }.toList(),
            firstBeat = 0.5,
            key = key,
            keyConfidence = 0.8,
            audibleStartTime = 0.2,
            pickupTime = 0.2,
        )
        val tailStart = duration - 30.0
        val tail = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = id,
            duration = duration,
            bpm = bpm,
            beatInterval = beat,
            beatConfidence = 0.8,
            downbeats = generateSequence(tailStart + 0.5) { it + 4 * beat }
                .takeWhile { it < duration }.toList(),
            firstBeat = tailStart + 0.5,
            key = key,
            keyConfidence = 0.8,
            contentEndTime = duration - 5.0,
            mixOutCandidates = listOf(
                MixCandidate(duration - 14.0, 0.95, "energy_cliff"),
                MixCandidate(duration - 5.0, 0.75, "content_end"),
            ),
        )
        return head.mergedWith(tail)
    }

    /** The arriving track: a head pass only, which is all a queued track has. */
    private fun incoming(
        id: String = "next",
        bpm: Double = 124.0,
        key: String = "A minor",
        duration: Double = 220.0,
        mixIn: List<MixCandidate> = emptyList(),
    ): TrackAnalysis {
        val beat = 60.0 / bpm
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = id,
            duration = duration,
            bpm = bpm,
            beatInterval = beat,
            beatConfidence = 0.8,
            downbeats = generateSequence(0.5) { it + 4 * beat }.takeWhile { it < 30.0 }.toList(),
            firstBeat = 0.5,
            key = key,
            keyConfidence = 0.8,
            audibleStartTime = 0.2,
            pickupTime = 0.2,
            mixInCandidates = mixIn,
        )
    }

    private fun plan(current: TrackAnalysis, next: TrackAnalysis) = planTransition(
        analysis = current,
        nextAnalysis = next,
        currentTrack = TransitionTrackInfo(current.trackId, (current.duration * 1000).toLong()),
        nextTrack = TransitionTrackInfo(next.trackId, (next.duration * 1000).toLong()),
        currentTime = 30.0,
        duration = current.duration,
        fadeSeconds = 6.0,
        mode = CrossfadeMode.SMART,
        advanced = true,
    )

    @Test
    fun aWellMatchedPairWithNoArrangementToWaitForIsStillPlanned() {
        // The reported pair: 119 BPM into 124 BPM, four per cent apart, with an
        // arriving track that opens straight into its arrangement. It used to be
        // refused, which meant no window in the seek bar and no blend at all.
        val result = plan(outgoing(), incoming())

        assertFalse(result.blocked, "the pair must not be refused: ${result.reason}")
        assertTrue(result.markerVisible, "a planned transition must carry a window")
        assertTrue(result.transitionEnd > result.transitionStart, "the window must have a length")
        assertTrue(result.fadeSeconds > 0.0, "the transition must have a length")
        assertEquals(TransitionStyle.DJ_BLEND, result.transitionStyle)
    }

    @Test
    fun anArrivingTrackThatStartsWithItsOwnPickupIsStillPlanned() {
        // A "pickup" candidate at zero is the file's opening, not an
        // arrangement arriving. Treating it as one left the runway at nothing.
        val result = plan(outgoing(), incoming(mixIn = listOf(MixCandidate(0.0, 0.4, "pickup"))))

        assertFalse(result.blocked, "the pair must not be refused: ${result.reason}")
        assertTrue(result.markerVisible)
        assertEquals(TransitionStyle.DJ_BLEND, result.transitionStyle)
    }

    @Test
    fun everyTempoDistanceIsPlanned() {
        // The runway cap used to bite only inside five per cent, so the pairs
        // that matched best were the ones that lost their transition. Every
        // distance has to be planned now.
        for (nextBpm in listOf(90.0, 110.0, 117.0, 119.0, 124.0, 125.0, 130.0, 140.0, 160.0)) {
            val result = plan(outgoing(), incoming(bpm = nextBpm))
            assertFalse(result.blocked, "$nextBpm BPM must not be refused: ${result.reason}")
            assertTrue(result.markerVisible, "$nextBpm BPM must carry a window")
            assertTrue(result.transitionEnd > result.transitionStart, "$nextBpm BPM must have a window")
        }
    }

    @Test
    fun aPlannedBlendStaysOnWholeBars() {
        val result = plan(outgoing(), incoming())
        val bars = result.fadeSeconds / (4 * (60.0 / 119.0))
        assertEquals(bars.toInt().toDouble(), bars, 1e-6, "the overlap must be whole bars")
        assertEquals(result.transitionBars * BEATS_PER_BAR, result.transitionBeats)
    }

    @Test
    fun theArrivingTrackIsCuedWhereItActuallyStarts() {
        // With no arrangement to wait for, the arriving track is cued at its own
        // first downbeat rather than somewhere inside it.
        val result = plan(outgoing(), incoming())
        assertTrue(
            result.incomingCueTime <= 1.0,
            "the arriving track was cued at ${result.incomingCueTime}s, past its own opening",
        )
    }

    @Test
    fun aPairWithAnArrangementStillWaitsForIt() {
        // The fix must not turn every pair into a cue at the first downbeat: a
        // track with a real drop is still waited for.
        val drop = 16.0
        val result = plan(outgoing(), incoming(mixIn = listOf(MixCandidate(drop, 0.8, "intro_drop"))))
        assertFalse(result.blocked, "the pair must not be refused: ${result.reason}")
        assertTrue(
            result.incomingHandoffTime > drop,
            "the handoff must sit past the drop, not before it",
        )
        assertTrue(result.transitionBars > 4, "a drop is worth more than the floor")
    }
}
