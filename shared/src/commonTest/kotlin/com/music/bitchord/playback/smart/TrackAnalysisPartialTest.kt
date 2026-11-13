package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the panel draws from an analysis that arrived in pieces.
 *
 * A streamed track is measured twice: its opening by the head pass, before the
 * rest of the file is on disk, and its ending by the tail pass — which exists
 * because the ending is the region no other writer ever fetches. The panel draws
 * the outgoing track across the *end* of its own timeline, so an opening-only
 * envelope lands entirely off-screen and the lane reads as empty even though the
 * analysis found the tempo and the key. That is the report these cases pin down.
 */
class TrackAnalysisPartialTest {

    private fun window(from: Double, seconds: Double, step: Double = 1.0): List<EnergySample> =
        generateSequence(from) { it + step }
            .takeWhile { it < from + seconds }
            .map { EnergySample(it, 0.5) }
            .toList()

    /** A head pass's answer: the opening measured, the ending unknown. */
    private fun headOnly(): TrackAnalysis = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY,
        trackId = "track",
        duration = 210.0,
        bpm = 126.0,
        beatInterval = 60.0 / 126.0,
        beatConfidence = 0.8,
        firstBeat = 0.4,
        downbeats = listOf(0.4, 2.3),
        key = "8A",
        introEndTime = 12.0,
        headEnergyCurve = window(0.0, 30.0),
    )

    /** A tail pass's answer: the ending measured, and the exit numbers with it. */
    private fun tailOnly(): TrackAnalysis = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY,
        trackId = "track",
        duration = 210.0,
        bpm = 63.0,
        downbeats = listOf(180.0, 182.0),
        contentEndTime = 206.0,
        outroStartTime = 188.0,
        mixOutTime = 203.0,
        mixOutCandidates = listOf(MixCandidate(203.0, 0.7, "outro")),
        tailEnergyCurve = window(180.0, 30.0),
    )

    @Test
    fun aMeasuredEndingIsDrawnWithoutWaitingForTheOpening() {
        // The whole point of the tail pass: the ending is the only thing the
        // panel has to draw for a track it is playing, so it must not be held
        // behind the opening — which is the half that arrives first and the half
        // that lands off-screen.
        val curve = tailOnly().panelEnergyCurve

        assertTrue(curve.isNotEmpty())
        assertEquals(180.0, curve.first().time, 1e-9)
        assertEquals(209.0, curve.last().time, 1e-9)
    }

    @Test
    fun bothWindowsAreDrawnInTheTracksOwnSeconds() {
        val curve = headOnly().mergedWith(tailOnly()).panelEnergyCurve

        // Disjoint, and in order: the opening at the front, the ending at the
        // back, and nothing invented in between for the middle nobody measured.
        assertEquals(60, curve.size)
        assertTrue(curve.take(30).all { it.time < 30.0 })
        assertTrue(curve.drop(30).all { it.time >= 180.0 })
        assertEquals(curve.map { it.time }.sorted(), curve.map { it.time })
    }

    @Test
    fun theWholeTracksCurveIsPreferredToTheWindowsItSupersedes() {
        val whole = headOnly().copy(energyCurve = window(0.0, 210.0))

        val curve = whole.panelEnergyCurve

        assertEquals(210, curve.size)
        assertEquals(209.0, curve.last().time, 1e-9)
    }

    @Test
    fun mergingKeepsTheEntryNumbersAndTakesTheExitOnes() {
        val merged = headOnly().mergedWith(tailOnly())

        // The head pass's tempo, because a tail window's own estimate is
        // measured over the least representative music in the track.
        assertEquals(126.0, merged.bpm)
        assertEquals("8A", merged.key)
        // The exit numbers, which the head pass cannot have measured at all.
        assertEquals(206.0, merged.contentEndTime)
        assertEquals(188.0, merged.outroStartTime)
        assertEquals(listOf(203.0), merged.mixOutCandidates.map { it.time })
        // Both windows kept, each where its own times say.
        assertEquals(30, merged.headEnergyCurve.size)
        assertEquals(30, merged.tailEnergyCurve.size)
    }

    @Test
    fun bothGridsSurviveTheMergeSoEitherEndCanBeSnappedTo() {
        val merged = headOnly().mergedWith(tailOnly())

        assertEquals(listOf(0.4, 2.3, 180.0, 182.0), merged.downbeats)
    }

    @Test
    fun theCurvesAreKeptWhicheverOrderThePassesFinishIn() {
        // The merge itself is order-dependent — `this` wins where both halves
        // measured the same field, so the caller is what decides that the head
        // goes first. What must not depend on the order is the pair of windows:
        // a tail arriving first must not lose the opening when the head lands,
        // or the lane would go blank on the second of the two passes.
        val tailFirst = tailOnly().mergedWith(headOnly())
        val headFirst = headOnly().mergedWith(tailOnly())

        assertEquals(60, tailFirst.panelEnergyCurve.size)
        assertEquals(60, headFirst.panelEnergyCurve.size)
        assertTrue(tailFirst.headEnergyCurve.isNotEmpty())
        assertTrue(tailFirst.tailEnergyCurve.isNotEmpty())
        // The entry numbers, though, are only preserved when the head is the
        // one merged into — which is what [TrackAnalyzer] does with the two
        // pieces it keeps apart.
        assertEquals(126.0, headFirst.bpm)
        assertEquals(63.0, tailFirst.bpm)
        assertEquals(206.0, tailFirst.contentEndTime)
    }

    @Test
    fun anUnmeasuredTrackDrawsNothing() {
        assertTrue(TrackAnalysis(trackId = "track").panelEnergyCurve.isEmpty())
    }

    /** Peaks at a fine spacing, the way a measured pass returns them. */
    private fun peaks(from: Double, seconds: Double, step: Double = 0.05): List<EnergySample> =
        generateSequence(from) { it + step }
            .takeWhile { it < from + seconds }
            .map { EnergySample(it, 0.5) }
            .toList()

    @Test
    fun anOpeningOnlyMeasurementIsFilledAcrossTheWholeTrack() {
        // The lane draws the *end* of the outgoing track, so a head pass's
        // peaks — the first thirty seconds and nothing else — put every column
        // off-screen and the lane reads as blank even though the tempo and key
        // are on screen. Once there is a tempo, the shape is carried to the
        // track's last second.
        val head = headOnly().copy(headPeakCurve = peaks(0.0, 30.0))
        val curve = head.enforcedWaveformCurve()

        // Ten columns a second across a three-and-a-half minute track.
        assertTrue(curve.size > 2000)
        assertEquals(210.0, curve.last().time, 1e-6)
        // The measured stretch is still the measured stretch: a stand-in may
        // only go where nothing was measured.
        assertEquals(0.5, curve.first { it.time >= 10.0 }.energy, 1e-9)
        assertEquals(0.5, curve.first { it.time >= 29.0 }.energy, 1e-9)
        // And the ending, which no pass has measured, is no longer empty.
        assertTrue(curve.last().energy > 0.0)
    }

    @Test
    fun aCurveThatAlreadyCoversTheTrackIsLeftAlone() {
        // A whole-track pass is the track's own shape. Filling it would only
        // coarsen measured peaks onto the panel's grid.
        val whole = headOnly().copy(peakCurve = peaks(0.0, 210.0, 0.25))

        assertEquals(whole.panelPeakCurve, whole.enforcedWaveformCurve())
    }

    @Test
    fun withoutATempoNothingIsInvented() {
        // A stand-in with no tempo behind it is a picture of nothing.
        val unknown = TrackAnalysis(trackId = "track", duration = 200.0)

        assertTrue(unknown.enforcedWaveformCurve().isEmpty())

        val measured = listOf(EnergySample(0.0, 0.4))
        assertEquals(measured, unknown.enforcedWaveformCurve(measured))
    }
}
