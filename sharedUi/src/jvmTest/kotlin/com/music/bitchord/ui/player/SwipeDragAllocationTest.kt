package com.music.bitchord.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.MonotonicFrameClock
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

/**
 * Before/after for the sleeve's drag: bytes allocated per pointer move.
 *
 * "Before" is the old code path replayed line for line: a conflated channel of
 * targets, and a new coroutine running a new spring for every target. "After"
 * is [TrackChangeMotion.drag]. Each move is followed by one frame, the way a
 * finger delivers roughly one move per frame.
 */
class SwipeDragAllocationTest {
    private class SteppingClock : MonotonicFrameClock {
        var time = 0L
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            yield()
            time += 16_000_000L
            return onFrame(time)
        }
    }

    @Test
    fun measureAllocationPerMove() {
        val moves = 2_000
        val before = runBlocking(SteppingClock()) {
            coroutineScope {
                val settle = Animatable(0f)
                val targets = Channel<Float>(Channel.CONFLATED)
                launch {
                    for (target in targets) {
                        val newest = targets.tryReceive().getOrNull() ?: target
                        launch {
                            if (newest != settle.targetValue) {
                                settle.animateTo(newest, spring(stiffness = Spring.StiffnessMediumLow))
                            }
                        }
                    }
                }
                val bytes = allocatedBytesSuspending {
                    repeat(moves) { i ->
                        targets.trySend(i * 0.35f)
                        yield()
                    }
                }
                coroutineContext.cancelChildren()
                bytes / moves
            }
        }
        val after = runBlocking(SteppingClock()) {
            coroutineScope {
                val motion = TrackChangeMotion(this)
                motion.dragStart()
                val bytes = allocatedBytesSuspending {
                    repeat(moves) { i ->
                        motion.drag(i.toFloat())
                        yield()
                    }
                }
                coroutineContext.cancelChildren()
                bytes / moves
            }
        }
        println("PERF sleeve drag, bytes per pointer move: before=$before after=$after")
        assertTrue(after * 4 < before, "the drag allocates far less per move (before=$before after=$after)")
    }

    private suspend fun allocatedBytesSuspending(block: suspend () -> Unit): Long {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        block()
        val start = bean.getThreadAllocatedBytes(id)
        block()
        return bean.getThreadAllocatedBytes(id) - start
    }
}
