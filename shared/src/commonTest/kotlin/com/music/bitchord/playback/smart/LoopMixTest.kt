package com.music.bitchord.playback.smart

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The web player's engine/loopMix.test.ts, ported: seams, wraps, folds, vocal
 * rejection and the 1-bar fallback. The web's AudioBuffer loudness becomes the
 * analysis's level windows here.
 */
class LoopMixTest {

    /** Loud everywhere except where [quiet] says, at the analyzer's level rate. */
    private fun levels(duration: Double, quiet: List<Pair<Double, Double>> = emptyList()): List<CurveWindow> {
        val frames = (duration * LEVEL_FRAME_RATE).roundToInt()
        val values = FloatArray(frames) { index ->
            val t = index / LEVEL_FRAME_RATE
            if (quiet.any { (a, b) -> t >= a && t < b }) 0f else levelFromRms(0.3).toFloat()
        }
        return listOf(CurveWindow(0.0, LEVEL_FRAME_RATE, values))
    }

    private fun analysis(
        bpm: Double,
        duration: Double,
        vocals: List<CurveWindow> = emptyList(),
        quiet: List<Pair<Double, Double>> = emptyList(),
    ) = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY,
        duration = duration,
        bpm = bpm,
        beatInterval = 60 / bpm,
        beatConfidence = 0.8,
        downbeats = listOf(0.5),
        firstBeat = 0.5,
        vocalWindows = vocals,
        levelWindows = levels(duration, quiet),
    )

    /** A vocal window that is [level] everywhere except [clean] spans. */
    private fun vocals(from: Double, to: Double, level: Float, clean: List<Pair<Double, Double>> = emptyList()): CurveWindow {
        val fps = 10.0
        val values = FloatArray(((to - from) * fps).roundToInt()) { index ->
            val t = from + index / fps
            if (clean.any { (a, b) -> t >= a && t < b }) 0.05f else level
        }
        return CurveWindow(from, fps, values)
    }

    /** Synced lyrics with nothing sung in the searched stretches: every loop is clear. */
    private val clear = emptyList<LoopMix.Span>()

    private fun plan(
        out: TrackAnalysis,
        inn: TrackAnalysis,
        sungOut: List<LoopMix.Span>? = clear,
        sungIn: List<LoopMix.Span>? = clear,
    ) = LoopMix.plan(out, out.duration, inn, inn.duration, 0.0, sungOut, sungIn)

    @Test
    fun loopsWholeBarsOutgoingNearItsEndIncomingAtItsStart() {
        val plan = assertNotNull(plan(analysis(124.0, 200.0), analysis(124.0, 180.0)))
        val bar = 60.0 / 124 * 4
        for (pick in listOf(plan.out, plan.inn)) {
            val bars = (pick.loopStart - 0.5) / bar
            assertTrue(abs(bars - bars.roundToInt()) < 1e-9)
            assertEquals(LoopMix.LOOP_BARS * bar, pick.loopEnd - pick.loopStart, 1e-9)
        }
        assertTrue(plan.out.loopEnd <= 199.5)
        assertTrue(plan.out.loopStart > 200 - 60)
        assertEquals(0.5, plan.inn.loopStart, 1e-9)
    }

    @Test
    fun prefersBarsTheVocalMaskCallsInstrumental() {
        // sung throughout except 150-170 s on the way out and 20-35 s on the way in
        val out = analysis(120.0, 200.0, listOf(vocals(130.0, 200.0, 0.8f, listOf(150.0 to 170.0))))
        val inn = analysis(120.0, 180.0, listOf(vocals(0.0, 45.0, 0.8f, listOf(20.0 to 35.0))))
        val plan = assertNotNull(plan(out, inn, null, null))
        assertTrue(plan.out.loopStart >= 150)
        assertTrue(plan.out.loopEnd <= 170 + 0.8)
        assertTrue(plan.out.vocal <= LoopMix.MAX_SUNG_SHARE)
        assertTrue(plan.inn.loopStart >= 20)
        assertTrue(plan.inn.loopEnd <= 35 + 0.8)
        assertEquals(LoopMix.Check.MASK, plan.out.checkedBy)
    }

    @Test
    fun skipsAQuietFadeOutForALoopThatGrooves() {
        val out = analysis(120.0, 200.0, quiet = listOf(185.0 to 200.0))
        val plan = assertNotNull(plan(out, analysis(120.0, 180.0)))
        assertTrue(plan.out.loopEnd <= 185)
    }

    @Test
    fun aMeasuredSilentBarStillRulesOutALoopWhoseOtherBarsAreUnmeasured() {
        // Levels end at 192 s, the last 8 s of them silent: a loop that starts in
        // that silence and runs on past the levels is not a groove.
        val out = analysis(120.0, 200.0).copy(levelWindows = levels(192.0, quiet = listOf(184.0 to 192.0)))
        val plan = assertNotNull(plan(out, analysis(120.0, 180.0)))
        assertTrue(plan.out.loopEnd <= 185.0, "loop end ${plan.out.loopEnd}")
    }

    @Test
    fun rampsAnyTempoGapFoldingToDoubleOrHalfTimeWhenCloser() {
        val near = assertNotNull(plan(analysis(120.0, 200.0), analysis(134.0, 180.0)))
        assertEquals(1.0, near.fold)
        assertEquals(ceil((134.0 - 120) / 2.5).toInt(), near.rampBars)
        // 111 -> 169 is 58 BPM up, but only 26.5 down to 169's half time: that's the ramp taken
        val wide = assertNotNull(plan(analysis(111.0, 200.0), analysis(169.0, 180.0)))
        assertEquals(2.0, wide.fold)
        assertEquals(ceil((111 - 169.0 / 2) / 2.5).toInt(), wide.rampBars)
        val slow = assertNotNull(plan(analysis(170.0, 200.0), analysis(80.0, 180.0)))
        assertEquals(0.5, slow.fold)
        // the incoming loop can only let go at its own boundary
        for (p in listOf(near, wide, slow)) {
            val inLoopOutBars = p.inn.bars / p.fold
            assertEquals(0.0, p.totalBars % inLoopOutBars, 1e-9)
        }
    }

    @Test
    fun aTempoGapTooWideForTheLongestRampGetsNoLoopMix() {
        // 215 -> 150: the closest fold is double time (300), 85 BPM away, 34 bars at 2.5 BPM a bar, past the 24-bar ramp.
        assertNull(plan(analysis(215.0, 200.0), analysis(150.0, 180.0)))
    }

    @Test
    fun cutsEachLoopOnDetectedDownbeatsTempoDriftAndAll() {
        // a live-played song: the beat stretches 0.5% over the minute, so a constant grid would miss
        val beats = ArrayList<Double>()
        var t = 140.0
        var beat = 60.0 / 120
        while (t < 199.5) {
            beats += t
            t += beat
            beat *= 1.0002
        }
        val downbeats = beats.filterIndexed { index, _ -> index % 4 == 0 }
        val out = analysis(120.0, 200.0).copy(tailBeats = BeatSide(beats, downbeats, 120.0, 0.9))
        val plan = assertNotNull(plan(out, analysis(120.0, 180.0)))
        assertTrue(plan.out.loopStart in downbeats)
        assertTrue(plan.out.loopEnd in downbeats)
        assertEquals(LoopMix.LOOP_BARS, downbeats.indexOf(plan.out.loopEnd) - downbeats.indexOf(plan.out.loopStart))
        // the loop's tempo is its own length over 16 beats
        assertEquals((plan.out.loopEnd - plan.out.loopStart) / 16, plan.out.beatSec, 1e-12)
    }

    @Test
    fun neverCutsOnADownbeatTheModelPutOnTheWrongBeat() {
        val beat = 0.5
        // every bar line at 140 + 2k s, except the one at 190 s, which the model put two beats late
        val downbeats = List(30) { 140 + it * 4 * beat }.map { if (abs(it - 190) < 0.01) 191.0 else it }
        val out = analysis(120.0, 200.0).copy(tailBeats = BeatSide(downbeats, downbeats, 120.0, 0.9))
        val plan = assertNotNull(plan(out, analysis(120.0, 180.0)))
        assertTrue(plan.out.loopStart != 191.0 && plan.out.loopEnd != 191.0)
        assertEquals(LoopMix.LOOP_BARS * 4 * beat, plan.out.loopEnd - plan.out.loopStart, 1e-9)
    }

    @Test
    fun aMeasuredTailOffTheOpeningGridLoopsOnItsOwnBarLines() {
        // The opening grid has bar lines at 0.5 + 2k s; the tail resumed a
        // quarter second late, and the model found only two bars of it.
        val downbeats = listOf(146.25, 148.25, 150.25)
        val out = analysis(120.0, 200.0).copy(tailBeats = BeatSide(downbeats, downbeats, 120.0, 0.9))
        val plan = assertNotNull(plan(out, analysis(120.0, 180.0)))
        assertTrue(plan.out.loopStart in downbeats, "loop start ${plan.out.loopStart}")
        assertTrue(plan.out.loopEnd in downbeats, "loop end ${plan.out.loopEnd}")
    }

    @Test
    fun aTailWithOneMeasuredBarLineGetsNoLoopFromTheOpeningGrid() {
        // The model found one bar line in the search, a quarter second off the
        // opening grid: no measured loop fits, and the opening grid is not trusted there.
        val out = analysis(120.0, 200.0).copy(tailBeats = BeatSide(listOf(196.25), listOf(196.25), 120.0, 0.9))
        assertNull(plan(out, analysis(120.0, 180.0)))
    }

    @Test
    fun clockWrapsOnlyAtLoopEndsAndTheIncomingSongArrivesAtItsOwnSpeed() {
        val plan = assertNotNull(plan(analysis(111.0, 200.0), analysis(169.0, 180.0)))
        val clock = LoopMix.Clock(plan)
        // each beat's position follows from the last at the beat's own rate; a wrap only at a loop end
        for (side in LoopMix.Side.entries) {
            val pick = if (side == LoopMix.Side.OUT) plan.out else plan.inn
            var previous = LoopMix.positionAt(clock, side, 0.0)!!
            for (k in 1 until clock.beats) {
                val position = LoopMix.positionAt(clock, side, clock.beatTimes[k])!!
                val step = if (side == LoopMix.Side.OUT) pick.beatSec else plan.fold * pick.beatSec
                var expected = previous + step
                if (expected >= pick.loopEnd - 1e-9) expected -= pick.loopEnd - pick.loopStart
                assertEquals(expected, position, 1e-6)
                previous = position
            }
        }
        // the ramp ends at the incoming song's own tempo, so its last looped beat plays at rate 1
        val last = clock.beats - 1
        val rate = clock.tempo(last) * plan.fold * plan.inn.beatSec / 60
        assertEquals(1.0, rate, 1e-9)
        // and the unwrapped incoming position at release is a whole number of its loops
        val loops = (clock.inReleasePosition - plan.inn.loopStart) / (plan.inn.loopEnd - plan.inn.loopStart)
        assertEquals(loops.roundToInt().toDouble(), loops, 1e-9)
        assertEquals(plan.inn.loopEnd + (clock.elementAt - clock.releaseAt), clock.elementTrackTime, 1e-9)
        assertTrue(clock.handoffAt > 0 && clock.handoffAt < clock.releaseAt)
    }

    @Test
    fun maskStatsCountsFramesOverTheThresholdAndIsNullWhereTheMaskDoesNotReach() {
        val windows = listOf(vocals(0.0, 10.0, 0.8f, listOf(0.0 to 5.0)))
        assertEquals(0.5, LoopMix.maskStats(windows, 0.0, 10.0)!!.share, 1e-9)
        assertEquals(0.0, LoopMix.maskStats(windows, 0.0, 5.0)!!.share, 1e-9)
        assertNull(LoopMix.maskStats(windows, 8.0, 14.0))
    }

    @Test
    fun maskCoverageCountsOverlappingWindowsOnce() {
        // Two windows overlapping over 6-10 s, then nothing measured: 4-14 s is
        // measured for 6 of its 10 seconds, not the 10 the two windows add up to.
        val windows = listOf(vocals(0.0, 10.0, 0.05f), vocals(6.0, 10.0, 0.05f))
        assertNull(LoopMix.maskStats(windows, 4.0, 14.0))
        assertNotNull(LoopMix.maskStats(windows, 2.0, 10.0))
    }

    @Test
    fun maskShareCountsOverlappingQuietFramesOnce() {
        // 0-9 s and 5-10 s overlap over 5-9 s; four frames after 9 s are sung.
        // That is 4 of 100 distinct frames, not 4 of the 140 the windows add up to.
        val first = vocals(0.0, 9.0, 0.05f)
        val second = CurveWindow(5.0, 10.0, FloatArray(50) { if (it >= 40 && it % 2 == 0 && it < 48) 0.8f else 0.05f })
        assertEquals(0.04, LoopMix.maskStats(listOf(first, second), 0.0, 10.0)!!.share, 1e-9)
    }

    @Test
    fun aSungRunEndsWhereNothingWasMeasured() {
        // Sung for the last 0.2 s of 0-9 s and the first 0.2 s of 10-20 s, nothing measured between.
        val first = vocals(0.0, 9.0, 0.05f, emptyList()).let { w ->
            CurveWindow(w.startTime, w.frameRate, FloatArray(w.values.size) { if (it >= w.values.size - 2) 0.8f else 0.05f })
        }
        val second = CurveWindow(10.0, 10.0, FloatArray(100) { if (it < 2) 0.8f else 0.05f })
        assertEquals(0.2, LoopMix.maskStats(listOf(first, second), 0.0, 20.0)!!.longestRunSec, 1e-9)
        // and a run that carries straight on into the next window is still one run
        val third = CurveWindow(9.0, 10.0, FloatArray(10) { if (it < 2) 0.8f else 0.05f })
        assertEquals(0.4, LoopMix.maskStats(listOf(first, third), 0.0, 10.0)!!.longestRunSec, 1e-9)
    }

    @Test
    fun positionIsNothingBeforeTheMixAndTheLoopStartOnItsFirstBeat() {
        val plan = assertNotNull(plan(analysis(111.0, 200.0), analysis(169.0, 180.0)))
        val clock = LoopMix.Clock(plan)
        assertNull(LoopMix.positionAt(clock, LoopMix.Side.OUT, -0.1))
        assertEquals(plan.out.loopStart, LoopMix.positionAt(clock, LoopMix.Side.OUT, 0.0)!!, 1e-9)
        assertEquals(plan.inn.loopStart, LoopMix.positionAt(clock, LoopMix.Side.IN, 0.0)!!, 1e-9)
    }

    @Test
    fun positionStaysInsideEachLoopWrappingBackToItsStart() {
        val plan = assertNotNull(plan(analysis(111.0, 200.0), analysis(169.0, 180.0)))
        val clock = LoopMix.Clock(plan)
        var sawWrap = false
        var last = -1.0
        var t = 0.0
        while (t < clock.releaseAt) {
            val out = LoopMix.positionAt(clock, LoopMix.Side.OUT, t)!!
            assertTrue(out >= plan.out.loopStart - 1e-9 && out <= plan.out.loopEnd + 1e-9)
            if (out < last - 1) sawWrap = true
            last = out
            val inn = LoopMix.positionAt(clock, LoopMix.Side.IN, t)!!
            assertTrue(inn >= plan.inn.loopStart - 1e-9 && inn <= plan.inn.loopEnd + 1e-9)
            t += 0.05
        }
        assertTrue(sawWrap)
    }

    @Test
    fun theIncomingSongGoesOnFromItsLoopEndInRealTimeAndTheOutgoingOneStops() {
        val plan = assertNotNull(plan(analysis(111.0, 200.0), analysis(169.0, 180.0)))
        val clock = LoopMix.Clock(plan)
        assertNull(LoopMix.positionAt(clock, LoopMix.Side.OUT, clock.releaseAt + 0.1))
        assertEquals(plan.inn.loopEnd, LoopMix.positionAt(clock, LoopMix.Side.IN, clock.releaseAt)!!, 1e-9)
        assertEquals(clock.elementTrackTime, LoopMix.positionAt(clock, LoopMix.Side.IN, clock.elementAt)!!, 1e-9)
    }

    @Test
    fun beatPhaseIsZeroOnEveryScheduledBeat() {
        val plan = assertNotNull(plan(analysis(111.0, 200.0), analysis(169.0, 180.0)))
        val clock = LoopMix.Clock(plan)
        assertEquals(0.0, LoopMix.beatPhaseAt(clock, clock.beatTimes[5])!!, 1e-9)
        val mid = (clock.beatTimes[5] + clock.beatTimes[6]) / 2
        assertEquals(0.5, LoopMix.beatPhaseAt(clock, mid)!!, 1e-9)
        assertNull(LoopMix.beatPhaseAt(clock, -1.0))
    }

    @Test
    fun skipsALoopASyncedLineOverlapsAndOneALineIsStillEndingInto() {
        // out song: sung until 172.2 s, then a line from 189 s; the latest clean 4 bars end before 189
        val sung = listOf(LoopMix.Span(150.0, 172.2), LoopMix.Span(189.0, 199.0))
        val plan = assertNotNull(plan(analysis(120.0, 200.0), analysis(120.0, 180.0), sung, clear))
        for (span in sung) assertTrue(plan.out.loopStart - 0.3 >= span.end || plan.out.loopEnd <= span.start)
        assertEquals(LoopMix.Check.LYRICS, plan.out.checkedBy)
    }

    @Test
    fun callsASoftVerseSung() {
        // mask 0.3 everywhere but 176-186 s, as Afsos measured
        val out = analysis(120.0, 200.0, listOf(vocals(130.0, 200.0, 0.3f, listOf(176.0 to 186.0))))
        val plan = assertNotNull(plan(out, analysis(120.0, 180.0), null, clear))
        assertTrue(plan.out.loopStart >= 176.5)
        assertTrue(plan.out.loopEnd <= 186)
        assertEquals(LoopMix.Check.MASK, plan.out.checkedBy)
    }

    @Test
    fun fallsBackToA1BarLoopWhenOnlyOneCleanBarExists() {
        // sung everywhere in the search but 180.5-182.5 (one bar)
        val sung = listOf(LoopMix.Span(100.0, 180.2), LoopMix.Span(182.6, 199.0))
        val plan = assertNotNull(plan(analysis(120.0, 200.0), analysis(120.0, 180.0), sung, clear))
        assertEquals(1, plan.out.bars)
        assertEquals(4 * 0.5, plan.out.loopEnd - plan.out.loopStart, 1e-9)
    }

    @Test
    fun anOpenLinesGuessedTailIsLeftToTheMask() {
        // Line-synced lyrics: a line every 8 s from 140 s, none with an end, so each reads as
        // sung until the next. The mask says the singing stops 3 s into each line.
        val lines = (0 until 8).map { LoopMix.Line((140_000 + it * 8_000).toLong(), null, blank = false) }
        val spans = LoopMix.sungSpans(lines)
        val quiet = (0 until 8).map { (140.0 + it * 8 + 3) to (140.0 + it * 8 + 8) }
        val out = analysis(120.0, 200.0, listOf(vocals(130.0, 200.0, 0.8f, quiet)))
        val plan = assertNotNull(plan(out, analysis(120.0, 180.0), spans, clear))
        assertEquals(LoopMix.Check.LYRICS_AND_MASK, plan.out.checkedBy)
        // never over a line's first words
        for (span in spans) assertTrue(plan.out.loopStart - 0.3 >= span.certainUntil || plan.out.loopEnd <= span.start)
        // and without a mask, the guessed tails still block it
        assertNull(plan(analysis(120.0, 200.0, quiet = listOf(176.0 to 200.0)), analysis(120.0, 180.0), spans, clear))
    }

    @Test
    fun makesNoLoopMixWhenNoBarIsInstrumental() {
        assertNull(plan(analysis(120.0, 200.0), analysis(120.0, 180.0), listOf(LoopMix.Span(0.0, 200.0)), clear))
    }

    @Test
    fun turnsSyncedLinesIntoPaddedSpansALineWithoutAnEndSungUntilTheNext() {
        val spans = LoopMix.sungSpans(
            listOf(
                LoopMix.Line(10_000, 12_000, blank = false),
                LoopMix.Line(13_000, null, blank = false),
                LoopMix.Line(16_000, null, blank = true),
                LoopMix.Line(20_000, 21_000, blank = false),
            ),
        )
        assertEquals(9.85, spans[0].start, 1e-9)
        assertEquals(12.35, spans[0].end, 1e-9)
        assertEquals(16.35, spans[1].end, 1e-9)
        assertEquals(3, spans.size)
    }

    @Test
    fun musicVideosGetNoLoopMixOnEitherSide() {
        val out = analysis(120.0, 200.0)
        val inn = analysis(120.0, 180.0)
        assertNull(LoopMix.plan(out, 200.0, inn, 180.0, 0.0, clear, clear, outVideo = true))
        assertNull(LoopMix.plan(out, 200.0, inn, 180.0, 0.0, clear, clear, inVideo = true))
    }

    @Test
    fun aGridTooUncertainToBeatMatchGetsNoLoopMix() {
        val unsure = analysis(120.0, 200.0).copy(beatConfidence = 0.0)
        assertNull(plan(unsure, analysis(120.0, 180.0)))
        assertNull(plan(analysis(120.0, 200.0), analysis(120.0, 180.0).copy(beatConfidence = 0.0)))
    }

    @Test
    fun noMaskAndNoLyricsMeansNoLoop() {
        assertNull(plan(analysis(120.0, 200.0), analysis(120.0, 180.0), null, null))
    }

    @Test
    fun theOutgoingLoopGoesByTheTailGridsOwnTrust() {
        val downbeats = (0 until 30).map { 140.5 + it * 2.0 }
        val trusted = analysis(120.0, 200.0).copy(beatConfidence = 0.0, tailBeats = BeatSide(downbeats, downbeats, 120.0, 0.9))
        assertNotNull(plan(trusted, analysis(120.0, 180.0)))
        val unsure = analysis(120.0, 200.0).copy(tailBeats = BeatSide(downbeats, downbeats, 120.0, 0.0))
        assertNull(plan(unsure, analysis(120.0, 180.0)))
    }

    @Test
    fun aMeasuredSideWithNoBarLinesIsTrustedOnlyAsFarAsTheWholeTrackGridItFallsBackTo() {
        // Beats but no downbeats: the loop would be cut on the whole-track grid, so its trust decides.
        val beats = (0 until 60).map { 140.5 + it * 0.5 }
        val out = analysis(120.0, 200.0).copy(beatConfidence = 0.1, tailBeats = BeatSide(beats, emptyList(), 120.0, 0.9))
        assertNull(plan(out, analysis(120.0, 180.0)))
        val inn = analysis(120.0, 180.0).copy(beatConfidence = 0.1, headBeats = BeatSide(beats.map { it - 140.0 }, emptyList(), 120.0, 0.9))
        assertNull(plan(analysis(120.0, 200.0), inn))
        // The whole-track grid trusted: the same sides plan a loop.
        assertNotNull(plan(out.copy(beatConfidence = 0.9), analysis(120.0, 180.0)))
    }

    @Test
    fun aMeasuredTailWithNoBarLineInTheSearchStillGetsNoLoopFromTheOpeningGrid() {
        // The model's bar lines all lie before the playhead: the opening grid is not trusted past them.
        val downbeats = (0 until 8).map { 140.5 + it * 2.0 }
        val out = analysis(120.0, 200.0).copy(tailBeats = BeatSide(downbeats, downbeats, 120.0, 0.9))
        val inn = analysis(120.0, 180.0)
        assertNotNull(LoopMix.plan(out, out.duration, inn, inn.duration, 0.0, clear, clear))
        assertNull(LoopMix.plan(out, out.duration, inn, inn.duration, 160.0, clear, clear))
    }

    @Test
    fun anOpenLineFarFromTheNextIsLeftToTheMaskAllTheWayThere() {
        val spans = LoopMix.sungSpans(
            listOf(
                LoopMix.Line(10_000, null, blank = false),
                LoopMix.Line(40_000, null, blank = false),
            ),
        )
        assertEquals(40.35, spans[0].end, 1e-9)
        assertEquals(12.35, spans[0].certainUntil, 1e-9)
        // The last line, with nothing after it, until the song ends; only its opening is certain.
        assertEquals(Double.POSITIVE_INFINITY, spans[1].end)
        assertEquals(42.35, spans[1].certainUntil, 1e-9)
    }

    @Test
    fun maskCoverageCountsOnlyTheNewPartOfAnOffsetFrame() {
        // Two 10 ms frames at 0 ms and 6 ms cover 16 ms of a 24 ms stretch: 67 %, under the 80 % needed.
        val a = CurveWindow(0.0, 100.0, floatArrayOf(0f))
        val b = CurveWindow(0.006, 100.0, floatArrayOf(0f))
        assertNull(LoopMix.maskStats(listOf(a, b), 0.0, 0.024))
        // Disjoint frames still count in full.
        val c = CurveWindow(0.01, 100.0, floatArrayOf(0f))
        assertNotNull(LoopMix.maskStats(listOf(a, c), 0.0, 0.02))
    }

    @Test
    fun aMeasuredLoopChecksTheBeatBeforeItOnItsOwnGrid() {
        // The song-wide grid says 150 BPM (0.4 s beats); the measured tail runs at 60 BPM (1 s).
        val downbeats = (0 until 25).map { 100.0 + it * 4.0 }
        fun withVocal(sung: Pair<Double, Double>?) = analysis(150.0, 200.0, listOf(
            vocals(90.0, 200.0, 0.05f).let { w ->
                CurveWindow(w.startTime, w.frameRate, FloatArray(w.values.size) { i ->
                    val t = w.startTime + i / w.frameRate
                    if (sung != null && t >= sung.first && t < sung.second) 0.9f else 0.05f
                })
            },
        )).copy(tailBeats = BeatSide(downbeats, downbeats, 60.0, 0.9))
        val inn = analysis(60.0, 180.0)
        val first = assertNotNull(LoopMix.plan(withVocal(null), 200.0, inn, 180.0, 0.0, null, clear))
        val x = first.out.loopStart
        // Half a second of voice ending 0.45 s before that loop: inside its preceding beat on the
        // tail's grid, outside a 0.4 s beat on the song-wide one.
        val plan = assertNotNull(LoopMix.plan(withVocal((x - 0.95) to (x - 0.45)), 200.0, inn, 180.0, 0.0, null, clear))
        assertTrue(plan.out.loopStart != x, "kept the loop with a voice in its preceding beat")
    }

    @Test
    fun theIncomingLoopStartsInsideTheOpeningSearch() {
        // Sung everywhere but one bar at 48.5-50.5 s: past the 45 s limit for a loop start.
        val inn = analysis(120.0, 180.0, listOf(vocals(0.0, 70.0, 0.9f, listOf(48.0 to 50.6))))
        val plan = LoopMix.plan(analysis(120.0, 200.0), 200.0, inn, 180.0, 0.0, clear, null)
        // Clean from its preceding beat on, so only the 45 s start limit rules the late loop out.
        assertNull(plan, "incoming loop at ${plan?.inn?.loopStart}")
    }

    @Test
    fun aLoopSpansItsBarsInMeasuredBarLinesNotJustTime() {
        // Bar lines the model put every two beats: 16 beats later is 8 of them, not 4.
        val downbeats = (0 until 60).map { 140.5 + it * 1.0 }
        val out = analysis(120.0, 200.0).copy(tailBeats = BeatSide(downbeats, downbeats, 120.0, 0.9))
        assertNull(plan(out, analysis(120.0, 180.0)))
    }

    @Test
    fun maskFramesStraddlingTheSearchEdgesCountForTheirInsidePart() {
        // 100 ms frames; the stretch 0.05-0.25 s starts and ends mid-frame.
        val w = CurveWindow(0.0, 10.0, floatArrayOf(0.9f, 0.05f, 0.9f))
        val stats = assertNotNull(LoopMix.maskStats(listOf(w), 0.05, 0.25))
        // 50 ms sung at each edge, 100 ms clear between: half of it sung.
        assertEquals(0.5, stats.share, 1e-9)
    }

    @Test
    fun aSungLaterWindowIsNotHiddenByAQuietEarlierOneOverTheSameTime() {
        val quiet = CurveWindow(0.0, 10.0, FloatArray(10) { 0.05f })
        val sung = CurveWindow(0.5, 10.0, FloatArray(5) { 0.9f })
        val stats = assertNotNull(LoopMix.maskStats(listOf(quiet, sung), 0.0, 1.0))
        assertEquals(0.5, stats.share, 1e-9)
    }

    @Test
    fun aSungRunEndsAtAnUnmeasuredGapHoweverShort() {
        // 8 Hz frames: sung 0-0.125 s and 0.15625-0.28125 s, nothing measured between.
        val a = CurveWindow(0.0, 8.0, floatArrayOf(0.9f))
        val b = CurveWindow(0.15625, 8.0, floatArrayOf(0.9f))
        val stats = assertNotNull(LoopMix.maskStats(listOf(a, b), 0.0, 0.28125))
        assertEquals(0.125, stats.longestRunSec, 1e-9)
    }

    @Test
    fun overlappingLevelWindowsCountEachInstantOnce() {
        // Two windows at 10 frames a second: 0-2 s at one level, 1-3 s at another.
        val loud = 0.9f
        val quiet = 0.5f
        val a = CurveWindow(0.0, 10.0, FloatArray(20) { loud })
        val b = CurveWindow(1.0, 10.0, FloatArray(20) { quiet })
        val l = rmsFromLevel(loud.toDouble())
        val q = rmsFromLevel(quiet.toDouble())
        // 0-2 s from the first window, 2-3 s from the second: 2:1, not 1:1.
        val expected = kotlin.math.sqrt((20 * l * l + 10 * q * q) / 30)
        assertEquals(expected, LoopMix.meanRms(listOf(a, b), 0.0, 3.0), 1e-12)
        assertEquals(expected, LoopMix.meanRms(listOf(b, a), 0.0, 3.0), 1e-12)
    }

    @Test
    fun theIncomingSearchEndsAFullLoopOnTheMeasuredOpeningsOwnBars() {
        // The opening measured at 96 BPM (2.5 s bars) against 120 song-wide (2 s):
        // the only clean bar line in the search is 45 s, and its 4 bars end at 55 s.
        val downs = (0..40).map { it * 2.5 }
        val beats = (0..160).map { it * 0.625 }
        val inn = analysis(120.0, 180.0).copy(headBeats = BeatSide(beats, downs, 96.0, 0.9))
        val why = StringBuilder()
        val p = LoopMix.plan(analysis(120.0, 200.0), 200.0, inn, 180.0, 0.0, clear, listOf(LoopMix.Span(0.0, 43.0)), why = why)
        assertNotNull(p, "no plan: $why")
        assertEquals(45.0, p.inn.loopStart, 1e-9)
        assertEquals(LoopMix.LOOP_BARS, p.inn.bars)
    }

    @Test
    fun noLoopIsCutFromAudioWhoseLevelWasNeverMeasured() {
        // Synced lyrics vouch for the whole song, but the outgoing song carries no level windows.
        val out = analysis(120.0, 200.0).copy(levelWindows = emptyList())
        val why = StringBuilder()
        assertNull(LoopMix.plan(out, 200.0, analysis(120.0, 180.0), 180.0, 0.0, clear, clear, why = why))
        assertTrue("no level" in why.toString(), why.toString())
    }
}
