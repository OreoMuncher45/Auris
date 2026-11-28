package com.music.bitchord

import com.music.bitchord.playback.AudioCache
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A recording too long to fetch whole is analysable once both ends are on
 * disk: the capped opening and the ending fetched past the cap.
 */
class AnalysisTailCoverageTest {

    private val cap = 100L
    private val tail = 20L

    private fun covered(length: Long, prefix: Long, tailFrom: Long?) =
        AudioCache.headAndTailCovered(length, prefix, cap, tail) { from, bytes ->
            // Contiguous bytes from [from], cached only in [tailFrom, length):
            // a read at or past the end gets nothing, as the cache would answer.
            if (tailFrom == null || from < tailFrom) 0L else maxOf(0L, minOf(from + bytes, length) - from)
        }

    @Test
    fun bothEndsOfALongCopyCoverIt() {
        assertTrue(covered(length = 300, prefix = 100, tailFrom = 280))
    }

    @Test
    fun theCappedOpeningAloneDoesNot() {
        // What a capped round used to call success: no ending, no outro.
        assertFalse(covered(length = 300, prefix = 100, tailFrom = null))
    }

    @Test
    fun aShortOpeningDoesNotEvenWithTheEnding() {
        assertFalse(covered(length = 300, prefix = 60, tailFrom = 280))
    }

    @Test
    fun aCopyWithinTheCapIsJudgedAsWholeElsewhere() {
        assertFalse(covered(length = 90, prefix = 90, tailFrom = 70))
    }

    @Test
    fun aCopyJustOverTheCapNeedsOnlyWhatIsPastIt() {
        assertTrue(covered(length = 110, prefix = 100, tailFrom = 100))
    }
}
