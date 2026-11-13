package com.music.bitchord.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How the waveform is read onto the drawing's columns.
 *
 * The drawing is a lane of pixels; the curve behind it is a list of samples.
 * When the samples are sparser than the pixels, most pixels have no sample of
 * their own — and a run of one pixel is a path with no area in it, which is
 * nothing at all on the screen. These cases pin down that a curve of any
 * resolution the analyser produces fills its columns, and that a stretch
 * nobody measured is still left blank.
 */
class WaveformColumnsTest {

    /** The analyser's energy curve: a quarter of a second between samples. */
    private fun sparseCurve(
        from: Double,
        seconds: Double,
        step: Double = 0.25,
    ): Pair<List<Float>, List<Double>> {
        val times = generateSequence(from) { it + step }
            .takeWhile { it < from + seconds }
            .toList()
        return times.map { 0.5f } to times
    }

    /** The analyser's peaks: twenty milliseconds between samples. */
    private fun denseCurve(
        from: Double,
        seconds: Double,
        step: Double = 0.02,
    ): Pair<List<Float>, List<Double>> {
        val times = generateSequence(from) { it + step }
            .takeWhile { it < from + seconds }
            .toList()
        return times.map { 0.5f } to times
    }

    @Test
    fun aCurveSparserThanTheDrawingStillFillsItsColumns() {
        // The case that read as an empty lane: the restored analysis has no
        // peaks, so the panel drew the energy curve, whose samples are a
        // quarter of a second apart against a pixel far finer than that.
        val (peaks, times) = sparseCurve(from = 100.0, seconds = 40.0)
        val columns = 800

        val grid = waveformColumns(
            peaks = peaks,
            times = times,
            from = 100.0,
            span = 40.0,
            columns = columns,
            mapToAxis = { it },
        )

        // Every pixel of the stretch the curve covers has something in it.
        val covered = grid.filled.count { it }
        assertTrue(covered > columns * 0.9, "only $covered of $columns columns were filled")
        // And the shape is the curve's own: a flat half-height, not nothing.
        assertTrue(grid.tops.any { it > 0.4f })
    }

    @Test
    fun aDenseCurveFillsItsColumnsToo() {
        val (peaks, times) = denseCurve(from = 0.0, seconds = 30.0)
        val columns = 600

        val grid = waveformColumns(
            peaks = peaks,
            times = times,
            from = 0.0,
            span = 30.0,
            columns = columns,
            mapToAxis = { it },
        )

        assertEquals(columns, grid.filled.count { it })
    }

    @Test
    fun aStretchNobodyMeasuredIsStillBlank() {
        // The head pass measures the opening and the tail pass the ending.
        // The stretch between them was never measured, and drawing across it
        // would invent a silence that is not in the music.
        val peaks = mutableListOf<Float>()
        val times = mutableListOf<Double>()
        for (time in generateSequence(0.0) { it + 0.25 }.takeWhile { it <= 5.0 }) {
            peaks += 0.5f
            times += time
        }
        for (time in generateSequence(100.0) { it + 0.25 }.takeWhile { it <= 105.0 }) {
            peaks += 0.5f
            times += time
        }

        val grid = waveformColumns(
            peaks = peaks,
            times = times,
            from = 0.0,
            span = 105.0,
            columns = 420,
            mapToAxis = { it },
        )

        // The opening and the ending are drawn.
        assertTrue(grid.filled[0])
        assertTrue(grid.filled[20])
        // The middle is not.
        assertFalse(grid.filled[100])
        assertFalse(grid.filled[200])
        assertFalse(grid.filled[300])
    }

    @Test
    fun samplesOutsideTheWindowAreLeftOut() {
        // A curve that ends before the view begins has nothing to say about
        // it: the lane is blank rather than drawn with the wrong stretch.
        val (peaks, times) = sparseCurve(from = 0.0, seconds = 50.0)

        val grid = waveformColumns(
            peaks = peaks,
            times = times,
            from = 100.0,
            span = 40.0,
            columns = 400,
            mapToAxis = { it },
        )

        assertEquals(0, grid.filled.count { it })
    }

    @Test
    fun aCurveThatCoversPartOfTheWindowIsDrawnOnlyThere() {
        val (peaks, times) = sparseCurve(from = 100.0, seconds = 10.0)

        val grid = waveformColumns(
            peaks = peaks,
            times = times,
            from = 100.0,
            span = 40.0,
            columns = 400,
            mapToAxis = { it },
        )

        // The first quarter of the view, and no further.
        assertTrue(grid.filled[0])
        assertTrue(grid.filled[90])
        assertFalse(grid.filled[110])
        assertFalse(grid.filled[399])
    }

    @Test
    fun theArrivingLanesCurveIsReadOnTheOutgoingTracksClock() {
        // The arriving lane is laid on the outgoing track's clock: its own
        // seconds are shifted by the lane's origin. A column is where the
        // shifted time lands, not where the arriving track's own time does.
        val (peaks, times) = sparseCurve(from = 0.0, seconds = 60.0)

        val grid = waveformColumns(
            peaks = peaks,
            times = times,
            from = 60.0,
            span = 40.0,
            columns = 400,
            mapToAxis = { time -> 60.0 + time },
        )

        // The arriving track's first second sits on the outgoing track's
        // minute, so the view from 60 s shows the start of the curve.
        assertTrue(grid.filled[0])
        assertTrue(grid.filled.count { it } > 350)
    }

    @Test
    fun anEmptyCurveFillsNothing() {
        val grid = waveformColumns(
            peaks = emptyList(),
            times = emptyList(),
            from = 0.0,
            span = 30.0,
            columns = 100,
            mapToAxis = { it },
        )

        assertEquals(0, grid.filled.count { it })
    }
}
