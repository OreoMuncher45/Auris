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

    @Test
    fun theArrivingLanesOriginIsTheWindowStartLessTheCue() {
        // The arriving track is cued so that the point of it the listener hears
        // first lands where the transition begins. The lane is drawn from the
        // arriving track's own beginning, so its zero sits that far back.
        assertEquals(100.0 - 40.0, automixIncomingOrigin(100.0, 40.0), 1e-9)
    }

    @Test
    fun theCueLandsExactlyWhereTheTransitionBegins() {
        // The one geometry the lane and the engine have to agree about: the
        // arriving track's cue is the moment it is heard, and that moment is
        // the window's start.
        val start = 100.0
        val cue = 40.0
        val origin = automixIncomingOrigin(start, cue)
        assertEquals(start, automixIncomingAxis(time = cue, origin = origin), 1e-9)
    }

    @Test
    fun theLaneSlidesWithTheTransitionRatherThanWithItsLength() {
        // Moving the *start* of the window moves the transition, so the
        // arriving track enters later and the whole lane slides with it.
        // Moving the *end* of it only makes the blend longer: the track still
        // enters where it entered.
        val cue = 40.0
        val movedStart = automixIncomingAxis(time = cue, origin = automixIncomingOrigin(110.0, cue))
        val sameStart = automixIncomingAxis(time = cue, origin = automixIncomingOrigin(100.0, cue))
        assertEquals(sameStart + 10.0, movedStart, 1e-9)
    }

    @Test
    fun theListenersOwnDragComesDownByWhatTheLaneWentUp() {
        // Dragging the lane right moves the arriving track later on the
        // outgoing clock, so the material heard at the transition is earlier
        // in the arriving track's own timeline: the cue comes down by exactly
        // what the lane went up.
        assertEquals(40.0 - 1.5, automixIncomingCue(startedAtCue = 40.0, moved = 1.5), 1e-9)
        assertEquals(40.0 + 1.5, automixIncomingCue(startedAtCue = 40.0, moved = -1.5), 1e-9)
    }

    @Test
    fun aTrackCannotBeCuedBeforeItsOwnBeginning() {
        // The one limit on the gesture: the arriving track has no audio before
        // its own start, so a drag that would take it there stops there.
        assertEquals(0.0, automixIncomingCue(startedAtCue = 0.0, moved = 5.0), 1e-9)
        assertEquals(0.0, automixIncomingCue(startedAtCue = 2.0, moved = 5.0), 1e-9)
    }

    @Test
    fun whatTheEngineIsToldIsTheTotalShift() {
        // The planner's field is a delta on its own plan, so the second drag
        // has to carry the first one with it: the value handed up is the
        // committed shift plus this drag's own.
        assertEquals(1.5, automixIncomingShift(committed = 0.0, moved = 1.5), 1e-9)
        assertEquals(3.0, automixIncomingShift(committed = 1.5, moved = 1.5), 1e-9)
        assertEquals(-1.0, automixIncomingShift(committed = 1.5, moved = -2.5), 1e-9)
    }
}
