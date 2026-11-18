package com.music.bitchord.download

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * A menu clears only the download failure it showed (UI 12 review). The
 * state flow's compare-and-set compares maps by equality, so two failures
 * with the same reason must not be equal, or a newer one could be cleared
 * through an A -> Queued -> B interleaving.
 */
class DismissFailureTest {

    private val id = "abc123"

    @Test
    fun twoFailuresWithTheSameReasonAreDifferentValues() {
        val a = DownloadState.Failed("Network error")
        val b = DownloadState.Failed("Network error")
        assertNotEquals(a, b)
        assertNotEquals(mapOf(id to a), mapOf(id to b))
    }

    @Test
    fun theShownFailureIsCleared() {
        val shown = DownloadState.Failed("Network error")
        assertEquals(emptyMap<String, DownloadState>(), withoutShownFailure(mapOf(id to shown), id, shown))
    }

    @Test
    fun aNewerFailureWithTheSameReasonStays() {
        val shown = DownloadState.Failed("Network error")
        // Retried, queued, and failed again the same way while the menu closed.
        val newer = DownloadState.Failed("Network error")
        val active = mapOf<String, DownloadState>(id to newer)
        assertSame(active, withoutShownFailure(active, id, shown))
    }

    @Test
    fun aStaleDismissalCannotClearANewerFailureThroughTheStateFlow() {
        // The interleaving MutableStateFlow.update can hit: the transform runs
        // against failure A, then a retry queues and fails the same way (B)
        // before the compare-and-set lands.
        val shown = DownloadState.Failed("Network error")
        val active = MutableStateFlow<Map<String, DownloadState>>(mapOf(id to shown))
        val read = active.value
        val next = withoutShownFailure(read, id, shown)

        active.value = mapOf(id to DownloadState.Queued)
        val newer = DownloadState.Failed("Network error")
        active.value = mapOf(id to newer)

        assertFalse("the stale compare-and-set fails", active.compareAndSet(read, next))
        assertSame(newer, active.value[id])
    }

    @Test
    fun aQueuedRetryStays() {
        val shown = DownloadState.Failed("Network error")
        val active = mapOf<String, DownloadState>(id to DownloadState.Queued)
        assertSame(active, withoutShownFailure(active, id, shown))
    }
}
