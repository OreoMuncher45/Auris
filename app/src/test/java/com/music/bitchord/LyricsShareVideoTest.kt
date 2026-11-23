package com.music.bitchord

import com.music.bitchord.ui.player.LyricsShareLine
import com.music.bitchord.ui.player.shareTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The lyrics share video's clock: which stretches of the track play, and what
 * the output timeline maps back onto. Ported with upstream's `shareTimeline`.
 */
class LyricsShareVideoTest {

    private fun line(start: Long, end: Long) =
        LyricsShareLine(text = "words", subText = null, startMs = start, endMs = end)

    private val gap = LyricsShareLine(text = "", subText = null, isGap = true)

    /** Two sung lines with a break between them. */
    private val lines = listOf(line(1_000, 2_000), gap, line(5_000, 6_000))

    @Test fun theBreakBetweenThemLinesIsCut() {
        val timeline = requireNotNull(shareTimeline(lines))
        assertEquals(listOf(1_000L to 2_000L, 5_000L to 6_000L), timeline.segments)
        assertEquals(2_000L, timeline.durationMs)
    }

    @Test fun theOutputClockMapsBackOntoTheTrack() {
        val timeline = requireNotNull(shareTimeline(lines))
        assertEquals(1_000L, timeline.trackMsAt(0))
        assertEquals(1_500L, timeline.trackMsAt(500_000))
        assertEquals(5_000L, timeline.trackMsAt(1_000_000))
    }

    @Test fun unsyncedLinesHaveNoTimeline() {
        val plain = LyricsShareLine(text = "words", subText = null)
        assertNull(shareTimeline(listOf(plain)))
    }
}
