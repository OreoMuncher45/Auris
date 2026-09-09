package com.music.bitchord

import com.music.bitchord.ui.player.reconcileLyricPosition
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricClockTest {
    @Test fun delayedPollDoesNotReactivatePreviousLine() {
        // A line starts at 10 seconds; the display already crossed its boundary.
        val reconciled = reconcileLyricPosition(10_018, 9_985)
        assertEquals(10_018L, reconciled)
        assertEquals(1, listOf(0L, 10_000L).indexOfLast { it <= reconciled })
    }

    @Test fun repeatedCorrectionsDoNotAccumulateDrift() {
        var displayed = 10_018L
        for (report in listOf(9_985L, 10_485L, 10_985L)) {
            displayed = reconcileLyricPosition(displayed, report)
            displayed = maxOf(displayed, report + 500L)
        }
        assertEquals(11_485L, displayed)
    }

    @Test fun backwardSeekResetsImmediately() {
        assertEquals(4_000L, reconcileLyricPosition(10_018, 4_000))
    }

    @Test fun forwardSeekResetsImmediately() {
        assertEquals(20_000L, reconcileLyricPosition(10_018, 20_000))
    }

    @Test fun newTrackResetsImmediately() {
        assertEquals(0L, reconcileLyricPosition(120_000, 0))
    }

    @Test fun pollAheadOfDisplayCatchesUp() {
        assertEquals(10_050L, reconcileLyricPosition(10_018, 10_050))
    }
}
