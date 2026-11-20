package com.music.bitchord

import com.music.bitchord.playback.autoplayQueueNeedsRefresh
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoplayFallbackTest {
    @Test
    fun `enabled autoplay refreshes when current track has no queue after it`() {
        assertTrue(
            autoplayQueueNeedsRefresh(
                enabled = true,
                repeatAll = false,
                currentIndex = 2,
                itemCount = 3,
                loadInProgress = false,
            ),
        )
    }

    @Test
    fun `an existing queue or active load does not start a duplicate refresh`() {
        assertFalse(autoplayQueueNeedsRefresh(true, false, 1, 3, false))
        assertFalse(autoplayQueueNeedsRefresh(true, false, 2, 3, true))
    }

    @Test
    fun `disabled autoplay repeat all and a missing current track do not refresh`() {
        assertFalse(autoplayQueueNeedsRefresh(false, false, 0, 1, false))
        assertFalse(autoplayQueueNeedsRefresh(true, true, 0, 1, false))
        assertFalse(autoplayQueueNeedsRefresh(true, false, -1, 0, false))
    }

    @Test
    fun `an edit that shrinks AutoPlay's tail refreshes even with tracks left`() {
        assertTrue(autoplayQueueNeedsRefresh(true, false, 1, 6, false, autoplayAhead = 4, autoplayAheadBefore = 7))
        // Unchanged or grown tails, a full tail, and an active load do not.
        assertFalse(autoplayQueueNeedsRefresh(true, false, 1, 6, false, autoplayAhead = 4, autoplayAheadBefore = 4))
        assertFalse(autoplayQueueNeedsRefresh(true, false, 1, 13, false, autoplayAhead = 10, autoplayAheadBefore = 11))
        assertFalse(autoplayQueueNeedsRefresh(true, false, 1, 6, true, autoplayAhead = 4, autoplayAheadBefore = 7))
        assertFalse(autoplayQueueNeedsRefresh(true, true, 1, 6, false, autoplayAhead = 4, autoplayAheadBefore = 7))
    }
}
