package com.music.bitchord.ui.player

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

class TrackChangeMotionTest {
    // ---- The pure decisions.

    @Test
    fun aDragPastTheThresholdSkipsTheWayItWent() {
        assertEquals(1, trackSwipeDirection(total = -80f, velocity = 0f, threshold = 72f, flingVelocity = 1000f))
        assertEquals(-1, trackSwipeDirection(total = 80f, velocity = 0f, threshold = 72f, flingVelocity = 1000f))
        assertEquals(0, trackSwipeDirection(total = -40f, velocity = 0f, threshold = 72f, flingVelocity = 1000f))
    }

    @Test
    fun aShortFlickSkipsOnlyIfStillGoingTheSameWay() {
        assertEquals(1, trackSwipeDirection(total = -30f, velocity = -1500f, threshold = 72f, flingVelocity = 1000f))
        assertEquals(-1, trackSwipeDirection(total = 30f, velocity = 1500f, threshold = 72f, flingVelocity = 1000f))
        // Dragged left, flung back right: that's a change of mind, not a skip.
        assertEquals(0, trackSwipeDirection(total = -30f, velocity = 1500f, threshold = 72f, flingVelocity = 1000f))
        assertEquals(0, trackSwipeDirection(total = -30f, velocity = -900f, threshold = 72f, flingVelocity = 1000f))
    }

    @Test
    fun aQuickDragHeldStillBeforeReleaseIsNotAFlick() {
        // Moving fast, released at once: a flick.
        val fresh = releaseVelocity(velocity = -1500f, sinceLastMoveMs = 8)
        assertEquals(1, trackSwipeDirection(total = -40f, velocity = fresh, threshold = 72f, flingVelocity = 1000f))
        // The same drag held for 500ms first: it springs back.
        val stale = releaseVelocity(velocity = -1500f, sinceLastMoveMs = 500)
        assertEquals(0, trackSwipeDirection(total = -40f, velocity = stale, threshold = 72f, flingVelocity = 1000f))
    }

    @Test
    fun theCardFollowsOneToOneThenGivesWay() {
        assertEquals(100f, cardSwipeOffset(100f, knee = 400f))
        assertEquals(-400f, cardSwipeOffset(-400f, knee = 400f))
        assertEquals(400f + 100f * CARD_PAST_KNEE, cardSwipeOffset(500f, knee = 400f), 1e-3f)
    }

    @Test
    fun theBannerPictureNeverMovesPastItsZoomsSlack() {
        val width = 1080f
        val slack = bannerSlack(width)
        assertEquals(width * 0.02f, slack, 1e-3f)
        assertEquals(15f, bannerSwipeOffset(100f, width), 1e-3f)
        assertEquals(slack, bannerSwipeOffset(10_000f, width), 1e-3f)
        assertEquals(-slack, bannerSwipeOffset(-10_000f, width), 1e-3f)
        // At any shift within the slack, the zoom covers both edges exactly.
        for (shift in listOf(0f, 5f, 15f, slack)) {
            val zoom = bannerZoomFor(shift, width)
            assertTrue(width * (zoom - 1f) / 2f >= shift - 1e-3f, "edge shows at $shift")
        }
        // Past the slack the picture can only be drawn partly see-through,
        // so a bare edge is never shown under a whole picture.
        assertEquals(1f, bannerCoverage(slack, width))
        assertEquals(0f, bannerCoverage(width * BANNER_EXIT_FRACTION, width))
        assertTrue(bannerCoverage(slack + 1f, width) < 1f)
        assertEquals(1f, bannerZoomFor(0f, width))
        assertEquals(BANNER_ZOOM, bannerZoomFor(500f, width), 1e-4f)
    }

    @Test
    fun aChangesDirectionComesFromTheHintThenTheQueue() {
        assertEquals(-1, trackChangeDirection(hint = -1, hintAgeMs = 200, oldIndex = 3, newIndex = 4))
        // A stale hint is ignored in favour of where the track sits.
        assertEquals(1, trackChangeDirection(hint = -1, hintAgeMs = 5_000, oldIndex = 3, newIndex = 4))
        assertEquals(-1, trackChangeDirection(hint = 0, hintAgeMs = 0, oldIndex = 5, newIndex = 2))
        assertEquals(1, trackChangeDirection(hint = 0, hintAgeMs = 0, oldIndex = 5, newIndex = 6))
        // A new queue can't be placed: it reads as moving on.
        assertEquals(1, trackChangeDirection(hint = 0, hintAgeMs = 0, oldIndex = -1, newIndex = 0))
    }

    // ---- The transition, run against a frame clock.

    private class SteppingClock : MonotonicFrameClock {
        var time = 0L
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            // A real frame's worth of wall time, so the waits measured in
            // milliseconds and the animations measured in frames agree.
            delay(1)
            time += 16_000_000L
            return onFrame(time)
        }
    }

    /** Runs [block] with a motion, sampling its offset and alpha every millisecond. */
    private fun withMotion(
        configure: TrackChangeMotion.() -> Unit = {},
        block: suspend CoroutineScope.(TrackChangeMotion, Samples) -> Unit,
    ) = runBlocking(SteppingClock()) {
        coroutineScope {
            val motion = TrackChangeMotion("a", this)
            motion.widthPx = 1000f
            motion.configure()
            motion.onTrackChanged("a", 0)
            val samples = Samples()
            val sampler = launch {
                while (true) {
                    samples.offsets += motion.offset()
                    samples.alphas += motion.alpha()
                    samples.keys += motion.shownKey
                    delay(1)
                }
            }
            block(motion, samples)
            sampler.cancel()
            coroutineContext.cancelChildren()
        }
    }

    private class Samples {
        val offsets = ArrayList<Float>()
        val alphas = ArrayList<Float>()
        val keys = ArrayList<String>()
    }

    private suspend fun settle(ms: Long = 900) {
        delay(ms)
        Snapshot.sendApplyNotifications()
    }

    @Test
    fun aSwipePastTheThresholdSlidesOutLeftAndTheNextCoverComesInFromTheRight() = withMotion { motion, s ->
        motion.dragStart()
        motion.drag(-200f, threshold = 72f)
        assertEquals(-200f, motion.offset())
        motion.commit(1)
        delay(60)
        motion.onTrackChanged("b", 1)
        settle()
        // Left the screen to the left, and came back in from the right.
        assertTrue(s.offsets.min() <= -900f, "left the screen: ${s.offsets.min()}")
        val firstB = s.keys.indexOf("b")
        assertTrue(s.offsets.drop(firstB).any { it > 300f }, "came in from the right")
        // The old cover is the one leaving: B shows only once A is off screen.
        assertTrue(s.offsets[firstB] <= -900f || s.offsets[firstB] > 300f)
        assertEquals(0f, motion.offset(), 0.5f)
        assertEquals(1f, motion.alpha())
        assertEquals("b", motion.shownKey)
    }

    @Test
    fun aShortSwipeSpringsBackAndChangesNothing() = withMotion { motion, s ->
        motion.dragStart()
        motion.drag(-40f, threshold = 72f)
        motion.springBack()
        settle()
        assertEquals(0f, motion.offset(), 0.5f)
        assertTrue(s.offsets.all { it >= -41f })
        assertEquals("a", motion.shownKey)
    }

    @Test
    fun aSwipeWithNoChangeComesBackTheWayItLeft() = withMotion { motion, s ->
        // Previous with the song past its restart point: the player restarts
        // the song, and no change arrives.
        motion.dragStart()
        motion.drag(200f, threshold = 72f)
        motion.commit(-1)
        settle(1_400)
        assertEquals(0f, motion.offset(), 0.5f)
        assertEquals(1f, motion.alpha())
        assertEquals("a", motion.shownKey)
        val farthest = s.offsets.indexOfFirst { it >= 900f }
        assertTrue(farthest >= 0, "left to the right")
        // Back from the right, the side it left by: never crosses to the left
        // beyond the enter spring's own small overshoot.
        assertTrue(s.offsets.drop(farthest).all { it >= -10f }, "crossed: ${s.offsets.drop(farthest).min()}")
    }

    @Test
    fun theSkipButtonAndAutoAdvancePlayTheSameSlide() = withMotion { motion, s ->
        motion.expect(-1)
        motion.onTrackChanged("b", 0)
        settle()
        assertTrue(s.offsets.max() >= 900f, "previous: out to the right")
        s.offsets.clear()
        // Auto-advance: no hint, the queue moved on.
        motion.onTrackChanged("c", 1)
        settle()
        assertTrue(s.offsets.min() <= -900f, "next: out to the left")
        assertEquals("c", motion.shownKey)
        assertEquals(0f, motion.offset(), 0.5f)
    }

    @Test
    fun theBannerPictureShiftsAndFadesInsideItsFrame() = withMotion({ banner = true }) { motion, s ->
        motion.dragStart()
        motion.drag(-5_000f, threshold = 72f)
        assertEquals(-bannerSlack(1000f), motion.offset(), 1e-3f)
        motion.commit(1)
        motion.onTrackChanged("b", 1)
        settle()
        assertTrue(s.offsets.all { kotlin.math.abs(it) <= 1000f * BANNER_EXIT_FRACTION + 0.5f })
        assertTrue(s.alphas.min() <= 0.01f, "faded through the backdrop")
        assertEquals(1f, motion.alpha())
        assertEquals(0f, motion.offset(), 0.5f)
        // Whole only within the slack: no frame shows a bare edge under a whole picture.
        val slack = bannerSlack(1000f)
        s.offsets.zip(s.alphas).forEach { (offset, alpha) ->
            if (kotlin.math.abs(offset) > slack + 0.5f) assertTrue(alpha < 1f, "whole at $offset")
        }
    }

    @Test
    fun reduceAnimationDipsInPlaceWithoutMovingSideways() = withMotion({ reduced = true }) { motion, s ->
        motion.onTrackChanged("b", 1)
        settle()
        assertTrue(s.offsets.all { it == 0f })
        assertTrue(s.alphas.min() <= 0.01f)
        assertEquals(1f, motion.alpha())
        assertEquals("b", motion.shownKey)
    }

    @Test
    fun aChangeNobodyCanSeeJustSwapsTheCover() = withMotion({ canAnimate = { false } }) { motion, s ->
        motion.onTrackChanged("b", 1)
        assertEquals("b", motion.shownKey)
        settle(100)
        assertTrue(s.offsets.all { it == 0f })
        assertTrue(s.alphas.all { it == 1f })
    }

    @Test
    fun aCoverThatNeverLoadsStillComesInAfterTheWait() = withMotion({ coverLoaded = { false } }) { motion, _ ->
        motion.onTrackChanged("b", 1)
        settle(1_200)
        assertEquals(0f, motion.offset(), 0.5f)
        assertEquals(1f, motion.alpha())
    }

    /**
     * Composition-visible writes per transition: everything the layers draw
     * with is read in the draw phase, so the only state composition can see
     * change is [TrackChangeMotion.shownKey] — once per track change.
     */
    @Test
    fun aTransitionWritesCompositionStateOncePerChange() = withMotion { motion, _ ->
        var shownKeyWrites = 0
        var last = motion.shownKey
        val observer = Snapshot.registerApplyObserver { _, _ ->
            if (motion.shownKey != last) {
                shownKeyWrites++
                last = motion.shownKey
            }
        }
        motion.dragStart()
        repeat(30) { motion.drag(-10f * it, threshold = 72f); yield() }
        motion.commit(1)
        motion.onTrackChanged("b", 1)
        settle()
        motion.onTrackChanged("c", 2)
        settle()
        observer.dispose()
        assertEquals(2, shownKeyWrites)
    }
}
