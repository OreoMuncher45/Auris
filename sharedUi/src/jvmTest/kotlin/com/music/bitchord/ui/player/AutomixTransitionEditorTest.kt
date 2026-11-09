package com.music.bitchord.ui.player

import com.music.bitchord.data.settings.AutomixPlanDigest
import com.music.bitchord.data.settings.TrackAnalysisDigest
import com.music.bitchord.playback.smart.AutomixOverride
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The arithmetic behind the editor's window.
 *
 * The drawing itself is Compose and out of reach here; what is not is the small
 * amount of geometry the drawing, the bar chip and the planner all have to
 * agree about. Those three are the places a wrong number turns into a transition
 * in the wrong place rather than into a picture that looks slightly off, so they
 * are the part worth pinning down.
 */
class AutomixTransitionEditorTest {

    /** A steady 120 bpm track: half-second beats, two-second bars, downbeats on every bar. */
    private fun track(
        bpm: Double = 120.0,
        duration: Double = 300.0,
    ) = TrackAnalysisDigest(
        bpm = bpm,
        beatInterval = 60.0 / bpm,
        duration = duration,
        contentEnd = duration - 2.0,
        downbeatTimes = generateSequence(0.5) { it + 4 * (60.0 / bpm) }
            .takeWhile { it < duration - 1.0 }
            .toList(),
    )

    private fun plan(start: Double = 100.0, end: Double = 116.0, bars: Int = 8) =
        AutomixPlanDigest(
            bars = bars,
            transitionStart = start,
            transitionEnd = end,
        )

    @Test
    fun aBarIsFourBeats() {
        assertEquals(2.0, automixBarSeconds(track()), 1e-9)
        assertEquals(4.0, automixBarSeconds(track(bpm = 60.0)), 1e-9)
    }

    @Test
    fun aTrackWithNoGridHasNoBar() {
        assertEquals(0.0, automixBarSeconds(TrackAnalysisDigest()), 1e-9)
    }

    @Test
    fun barsAreCountedBetweenTwoEdges() {
        assertEquals(8, automixBarsBetween(10.0, 26.0, 2.0))
        assertEquals(0, automixBarsBetween(26.0, 10.0, 2.0), "an edge order is not a negative size")
        assertEquals(0, automixBarsBetween(0.0, 10.0, 0.0))
    }

    @Test
    fun theWindowIsThePlansWhenTheListenerHasChosenNothing() {
        val window = automixWindow(AutomixOverride.NONE, track(), plan())
        assertEquals(100.0, window.start, 1e-9)
        assertEquals(116.0, window.end, 1e-9)
    }

    @Test
    fun bothEdgesOfADrawnBoxAreReadBackExactly() {
        val choice = AutomixOverride(transitionStart = 51.0, transitionEnd = 69.0)
        val window = automixWindow(choice, track(), plan())
        assertEquals(51.0, window.start, 1e-9)
        assertEquals(69.0, window.end, 1e-9)
        assertEquals(18.0, window.length, 1e-9)
    }

    @Test
    fun anEndOnItsOwnIsFilledInBackwardsFromTheSize() {
        // The exit the listener placed is theirs; the size is the one they
        // asked for; the handoff follows from the two.
        val choice = AutomixOverride(bars = 4, transitionEnd = 70.0)
        val window = automixWindow(choice, track(), plan())
        assertEquals(70.0, window.end, 1e-9)
        assertEquals(62.0, window.start, 1e-9)
    }

    @Test
    fun aStartOnItsOwnKeepsThePlannedExit() {
        val choice = AutomixOverride(transitionStart = 50.0)
        val window = automixWindow(choice, track(), plan())
        assertEquals(50.0, window.start, 1e-9)
        assertEquals(116.0, window.end, 1e-9)
    }

    @Test
    fun theMagnetTakesTheNearestBarLine() {
        val digest = track()
        // Downbeats every two seconds from 0.5; beats in between are not
        // targets, because a transition is made of bars and a beat is a few
        // pixels wide at any ordinary zoom.
        assertEquals(10.5, automixSnapToBar(10.4, digest)!!, 1e-9)
        assertEquals(10.5, automixSnapToBar(11.4, digest)!!, 1e-9)
        assertEquals(12.5, automixSnapToBar(11.6, digest)!!, 1e-9)
    }

    @Test
    fun theMagnetFollowsTheGridItWasGivenRatherThanTheTempo() {
        val digest = track().copy(downbeatTimes = listOf(3.0, 5.0, 7.0))
        assertEquals(5.0, automixSnapToBar(4.9, digest)!!, 1e-9)
        assertEquals(3.0, automixSnapToBar(3.4, digest)!!, 1e-9)
        assertEquals(7.0, automixSnapToBar(6.9, digest)!!, 1e-9)
    }

    @Test
    fun theMagnetWithoutAGridSaysSo() {
        assertNull(automixSnapToBar(5.0, TrackAnalysisDigest()))
    }

    @Test
    fun aWindowKeepsTheLengthTheBarsItHas() {
        val window = automixWindow(
            AutomixOverride(bars = 12, transitionStart = 40.0, transitionEnd = 64.0),
            track(),
            plan(),
        )
        assertTrue(window.length > 0.0)
        assertEquals(12, automixBarsBetween(window.start, window.end, automixBarSeconds(track())))
    }

    // -----------------------------------------------------------------------
    // The arriving lane
    // -----------------------------------------------------------------------

    /**
     * The arriving track is cued to [cue] and its handoff lands at the end of
     * the blend, so the cue is one blend-length before the handoff.
     */
    private fun cueFor(handoff: Double, blendSeconds: Double): Double = handoff - blendSeconds

    @Test
    fun resizingTheWindowLeavesTheArrivingLaneWhereItIs() {
        // The report: "if I'm resizing the transition window, it moves the
        // bottom waveform". The bottom waveform is the arriving track, and it
        // moved because it was anchored to the cue — and the cue is one
        // blend-length before the handoff, so every change of blend length
        // moved it.
        val handoff = 140.0
        val before = automixIncomingAxis(
            time = 130.0,
            windowStart = 100.0,
            windowEnd = 116.0,
            handoff = handoff,
            cue = cueFor(handoff, 16.0),
        )
        // The same window, eight seconds longer: the handoff edge has not
        // moved, and neither has the arriving lane.
        val after = automixIncomingAxis(
            time = 130.0,
            windowStart = 92.0,
            windowEnd = 116.0,
            handoff = handoff,
            cue = cueFor(handoff, 16.0),
        )
        assertEquals(before, after, 1e-9)
    }

    @Test
    fun movingTheWholeWindowStillMovesTheArrivingLane() {
        // The arriving track really does begin somewhere else when the handoff
        // moves, and a lane that stayed behind would be lying about it.
        val handoff = 140.0
        val before = automixIncomingAxis(
            time = 130.0,
            windowStart = 100.0,
            windowEnd = 116.0,
            handoff = handoff,
            cue = cueFor(handoff, 16.0),
        )
        val after = automixIncomingAxis(
            time = 130.0,
            windowStart = 90.0,
            windowEnd = 106.0,
            handoff = handoff,
            cue = cueFor(handoff, 16.0),
        )
        assertEquals(before - 10.0, after, 1e-9)
    }

    @Test
    fun theArrivingLanesHandoffLandsAtTheEndOfTheWindow() {
        // The planner's own geometry: the arriving track is cued so that the
        // point of its arrangement where the blend ends arrives exactly as the
        // blend ends.
        val handoff = 140.0
        val windowEnd = 116.0
        val axis = automixIncomingAxis(
            time = handoff,
            windowStart = 100.0,
            windowEnd = windowEnd,
            handoff = handoff,
            cue = cueFor(handoff, 16.0),
        )
        assertEquals(windowEnd, axis, 1e-9)
    }

    @Test
    fun theTwoAnchorsAgreeWhenTheWindowIsThePlansOwn() {
        // With no handoff to anchor to, the cue is used — and when the
        // window is the plan's own, the two answers are the same picture.
        val handoff = 140.0
        val blend = 16.0
        val cue = cueFor(handoff, blend)
        val byHandoff = automixIncomingAxis(
            time = cue + 4.0,
            windowStart = 100.0,
            windowEnd = 116.0,
            handoff = handoff,
            cue = cue,
        )
        val byCue = automixIncomingAxis(
            time = cue + 4.0,
            windowStart = 100.0,
            windowEnd = 116.0,
            handoff = null,
            cue = cue,
        )
        assertEquals(byCue, byHandoff, 1e-9)
    }
}
