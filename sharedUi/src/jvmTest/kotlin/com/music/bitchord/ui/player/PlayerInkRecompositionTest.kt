package com.music.bitchord.ui.player

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A change of ink must recompose only the texts and glyphs that wear it.
 *
 * The player hands its ink down as a state for exactly this reason (see
 * [LocalPlayerInk]). This drives the real [rememberPlayerInk] and
 * [rememberPlayerInkGlow] on a headless [Recomposer] and frame clock, from a
 * screen body shaped like `NowPlayingScreen`'s: it owns the sleeve's ink and
 * the live palette, provides the result, and has bystanders under it that
 * never touch the ink.
 *
 * Two changes are driven: a playing clip publishing a new ink, which happens
 * up to four times a second while it plays, and a track change.
 */
class PlayerInkRecompositionTest {

    private object NoNodes : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    private var screenCount = 0
    /** The ink the screen provides, so the harness can tell when it has settled. */
    private var inkState: State<Color>? = null
    private var readerCount = 0
    private var bystanderCount = 0

    @Composable
    private fun Reader() {
        readerCount++
        LocalPlayerInk.current.value
        LocalPlayerInkGlow.current.value
    }

    @Composable
    private fun Bystander() {
        bystanderCount++
    }

    @Composable
    private fun Screen(
        sleeveInk: MutableState<Color>,
        live: MutableState<LivePalette?>,
        fromClip: Boolean,
    ) {
        screenCount++
        val ink = rememberPlayerInk(
            palette = live,
            fromClip = fromClip,
            sleeve = NowPlayingPalette(fg = sleeveInk.value),
        ).ink
        inkState = ink
        val glow = rememberPlayerInkGlow(ink)
        CompositionLocalProvider(LocalPlayerInk provides ink, LocalPlayerInkGlow provides glow) {
            repeat(READERS) { Reader() }
            repeat(BYSTANDERS) { Bystander() }
        }
    }

    private class Run(val screen: Int, val readers: Int, val bystanders: Int, val frames: Int)

    private fun drive(fromClip: Boolean, change: (MutableState<Color>, MutableState<LivePalette?>) -> Unit): Run =
        runBlocking(Dispatchers.Unconfined) {
            val clock = BroadcastFrameClock()
            val recomposer = Recomposer(coroutineContext + clock)
            val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
            val sleeveInk = mutableStateOf(Color.White)
            val live = mutableStateOf<LivePalette?>(
                LivePalette(NowPlayingPalette(fg = LIVE_INK_BEFORE), LiveColorSource.CANVAS),
            )
            val composition = Composition(NoNodes, recomposer)
            composition.setContent { Screen(sleeveInk, live, fromClip) }
            var now = 0L
            // Frames until the provided ink has landed on [target], then a few
            // more so nothing still in flight is missed. The ink is judged on
            // Dispatchers.Default, so the wait is on that result arriving and
            // the animation finishing — bounded, rather than a fixed sleep.
            suspend fun settleOn(target: Color): Int {
                val deadline = System.nanoTime() + SETTLE_TIMEOUT_NS
                var frames = 0
                var after = 0
                while (after < TRAILING_FRAMES) {
                    check(System.nanoTime() < deadline) {
                        "the ink never settled on $target (stuck at ${inkState?.value})"
                    }
                    Thread.sleep(1)
                    repeat(4) { yield() }
                    clock.sendFrame(now)
                    repeat(4) { yield() }
                    Snapshot.sendApplyNotifications()
                    now += FRAME_NS
                    frames++
                    if (inkState?.value == target) after++
                }
                return frames
            }
            settleOn(if (fromClip) LIVE_INK_BEFORE else Color.White)
            screenCount = 0
            readerCount = 0
            bystanderCount = 0

            change(sleeveInk, live)
            Snapshot.sendApplyNotifications()
            val frames = settleOn(NEW_INK)

            composition.dispose()
            recomposer.cancel()
            runner.cancel()
            Run(screenCount, readerCount, bystanderCount, frames)
        }

    @Test
    fun liveInkChangeRecomposesOnlyItsReaders() {
        val run = drive(fromClip = true) { _, live ->
            live.value = LivePalette(NowPlayingPalette(fg = NEW_INK), LiveColorSource.CANVAS)
        }
        println("live ink change over ${run.frames} frames: screen=${run.screen}, readers=${run.readers}, bystanders=${run.bystanders}")
        assertTrue(run.readers > READERS, "the readers should animate to the new ink")
        assertEquals(0, run.screen, "a clip's new ink must not recompose the screen that provides it")
        assertEquals(0, run.bystanders)
    }

    @Test
    fun trackChangeRecomposesTheScreenOnce() {
        val run = drive(fromClip = false) { sleeve, _ -> sleeve.value = NEW_INK }
        println("track ink change over ${run.frames} frames: screen=${run.screen}, readers=${run.readers}, bystanders=${run.bystanders}")
        assertTrue(run.readers > READERS, "the readers should animate to the new ink")
        // Once, for the new sleeve ink it reads itself — never per frame.
        assertEquals(1, run.screen)
        assertEquals(0, run.bystanders)
    }

    private companion object {
        const val READERS = 5
        const val BYSTANDERS = 40
        const val FRAME_NS = 16_666_667L
        const val SETTLE_TIMEOUT_NS = 5_000_000_000L
        const val TRAILING_FRAMES = 3
        val LIVE_INK_BEFORE = Color(0xFFFFC0C0)
        val NEW_INK = Color(0xFF203040)
    }
}
