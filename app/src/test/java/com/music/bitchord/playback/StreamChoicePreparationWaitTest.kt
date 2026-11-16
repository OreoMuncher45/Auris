package com.music.bitchord.playback

import com.music.bitchord.data.sources.SourceStream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StreamChoice.awaitPreparation] wakes when a preparation ends or pins a
 * stream, rather than polling. Each test uses its own ids because
 * [StreamChoice] is process-wide.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamChoicePreparationWaitTest {

    @Test
    fun returnsAtOnceWhenNothingIsBeingPrepared() = runTest {
        val waited = async(start = CoroutineStart.UNDISPATCHED) {
            StreamChoice.awaitPreparation("wait-none")
        }
        assertTrue(waited.isCompleted)
    }

    @Test
    fun wakesWhenThePreparationPinsAStream() = runTest {
        val id = "wait-pinned"
        StreamChoice.beginPreparing(id)
        val waited = async { StreamChoice.awaitPreparation(id) }
        advanceTimeBy(500)
        runCurrent()
        assertTrue(waited.isActive)

        StreamChoice.remember(id, SourceStream("https://example.invalid/a"), substituted = true)
        runCurrent()
        assertTrue(waited.isCompleted)
        assertEquals(500L, testScheduler.currentTime)
        StreamChoice.endPreparing(id)
        StreamChoice.forget(id)
    }

    @Test
    fun wakesWhenThePreparationIsWithdrawnEmpty() = runTest {
        val id = "wait-withdrawn"
        StreamChoice.beginPreparing(id)
        val waited = async { StreamChoice.awaitPreparation(id) }
        runCurrent()
        assertTrue(waited.isActive)

        StreamChoice.endPreparing(id)
        runCurrent()
        assertTrue(waited.isCompleted)
    }

    @Test
    fun anotherTracksPinDoesNotEndTheWait() = runTest {
        val id = "wait-other"
        StreamChoice.beginPreparing(id)
        val waited = async { StreamChoice.awaitPreparation(id) }
        runCurrent()

        StreamChoice.remember("wait-other-track", SourceStream("https://example.invalid/b"), substituted = true)
        runCurrent()
        assertTrue(waited.isActive)

        StreamChoice.endPreparing(id)
        runCurrent()
        assertTrue(waited.isCompleted)
        StreamChoice.forget("wait-other-track")
    }

    @Test
    fun theCallersTimeoutStillBoundsTheWait() = runTest {
        val id = "wait-timeout"
        StreamChoice.beginPreparing(id)
        val result = withTimeoutOrNull(1_500) { StreamChoice.awaitPreparation(id) }
        assertNull(result)
        assertEquals(1_500L, testScheduler.currentTime)
        StreamChoice.endPreparing(id)
    }
}
