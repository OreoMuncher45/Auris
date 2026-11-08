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
    fun snappingLandsOnTheNearestBeat() {
        val digest = track()
        assertEquals(11.0, automixSnapToBeat(11.1, digest)!!, 1e-9)
        assertEquals(11.5, automixSnapToBeat(11.4, digest)!!, 1e-9)
        // A downbeat is a beat too, and the nearest one wins like any other.
        assertEquals(10.5, automixSnapToBeat(10.4, digest)!!, 1e-9)
    }

    @Test
    fun snappingFollowsTheGridItWasGivenRatherThanTheTempo() {
        // Downbeats that the tempo alone would not predict — the point being
        // that the dots on screen are the grid, and the grid is what an edge
        // lands on. The beats between them are still read off the interval.
        val digest = track().copy(downbeatTimes = listOf(3.0, 5.0, 7.0))
        assertEquals(5.0, automixSnapToBeat(4.9, digest)!!, 1e-9)
        assertEquals(3.5, automixSnapToBeat(3.4, digest)!!, 1e-9)
        assertEquals(5.5, automixSnapToBeat(5.4, digest)!!, 1e-9)
    }

    @Test
    fun snappingWithoutAGridSaysSo() {
        assertNull(automixSnapToBeat(5.0, TrackAnalysisDigest()))
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
}
